/*
 * openTCS copyright information:
 * Copyright (c) 2006 Fraunhofer IML
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.kernel;

import static com.google.common.base.Preconditions.checkState;
import com.google.inject.BindingAnnotation;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static java.util.Objects.requireNonNull;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import org.opentcs.access.CredentialsException;
import org.opentcs.access.Kernel;
import org.opentcs.access.LocalKernel;
import org.opentcs.access.UnsupportedKernelOpException;
import org.opentcs.access.rmi.CallPermissions;
import org.opentcs.access.rmi.ClientID;
import org.opentcs.access.rmi.RemoteKernel;
import org.opentcs.components.kernel.KernelExtension;
import org.opentcs.customizations.ApplicationHome;
import org.opentcs.data.user.UserAccount;
import org.opentcs.data.user.UserExistsException;
import org.opentcs.data.user.UserPermission;
import org.opentcs.data.user.UserUnknownException;
import org.opentcs.kernel.persistence.UserAccountPersister;
import org.opentcs.kernel.persistence.XMLFileUserAccountPersister;
import org.opentcs.util.CyclicTask;
import org.opentcs.util.RMIRegistries;
import org.opentcs.util.eventsystem.AcceptingTCSEventFilter;
import org.opentcs.util.eventsystem.EventBuffer;
import org.opentcs.util.eventsystem.EventFilter;
import org.opentcs.util.eventsystem.EventListener;
import org.opentcs.util.eventsystem.RefusingTCSEventFilter;
import org.opentcs.util.eventsystem.TCSEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is the standard implementation of the {@link RemoteKernel
 * RemoteKernel} interface.
 * It is basically a wrapper object around an openTCS kernel instance that is
 * exported via RMI and adds some checks of the client's credentials before
 * allowing a method call to be passed through to the actual kernel.
 * <p>
 * Upon creation, an instance of this class registers itself with the RMI
 * registry by the name declared as {@link RemoteKernel#registrationName
 * RemoteKernel.registrationName}.
 * </p>
 *
 * <hr>
 *
 * <h4>Configuration entries</h4>
 * <dl>
 * <dt><b>sweepInterval:</b></dt>
 * <dd>The interval for cleaning out inactive clients (in ms), defaulting to
 * five minutes.</dd>
 * </dl>
 * <hr>
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
class StandardRemoteKernel
    implements KernelExtension,
               EventListener<TCSEvent>,
               InvocationHandler {

  /**
   * This class's Logger.
   */
  private static final Logger log
      = LoggerFactory.getLogger(StandardRemoteKernel.class);
  /**
   * The kernel's data directory.
   */
  private final File dataDir;
  /**
   * The local kernel implementing the actual functionality.
   */
  private final Kernel localKernel;
  /**
   * The RMI registry's host and port.
   */
  private final RegistryAddress registryAddress;
  /**
   * The persister loading and storing account data.
   */
  private final UserAccountPersister accountPersister;
  /**
   * The directory of users allowed to connect/operate with the kernel.
   */
  private final Map<String, UserAccount> knownUsers = new HashMap<>();
  /**
   * The directory of authenticated clients (a mapping of ClientIDs to user
   * names).
   */
  private final Map<ClientID, ClientEntry> knownClients = new HashMap<>();
  /**
   * A task that periodically cleans up the list of known clients and event
   * buffers.
   */
  private final ClientCleanerTask cleanerTask;
  /**
   * The proxy passing method calls to our invoke().
   */
  private final RemoteKernel proxy;
  /**
   * The registry with which this <code>RemoteKernel</code> registers.
   */
  private Registry rmiRegistry;
  /**
   * This kernel extension's <em>enabled</em> flag.
   */
  private boolean enabled;

  /**
   * Creates and registers a new RMI object for a locally running kernel.
   *
   * @param homeDirectory The kernel's home directory (for saving user account
   * data). Will be created if it doesn't exist, yet.
   * @param kernel The local kernel.
   * @param sweepInterval The interval for cleaning out inactive clients (in ms).
   * Must be at least 1000.
   * @param registryAddress The RMI registry's host and port.
   */
  @Inject
  StandardRemoteKernel(@ApplicationHome File homeDirectory,
                       LocalKernel kernel,
                       @ClientSweepInterval long sweepInterval,
                       RegistryAddress registryAddress) {
    requireNonNull(homeDirectory, "homeDirectory");
    this.localKernel = requireNonNull(kernel, "kernel");
    this.registryAddress = requireNonNull(registryAddress, "registryAddress");
    dataDir = new File(homeDirectory, "data");
    if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
      throw new IllegalArgumentException(dataDir.getPath()
          + " is not an existing directory and could not be created, either.");
    }

    // Read the known user accounts.
    accountPersister = new XMLFileUserAccountPersister(dataDir);
    Set<UserAccount> accounts;
    try {
      accounts = accountPersister.loadUserAccounts();
    }
    catch (IOException exc) {
      throw new IllegalStateException(
          "IOException trying to load user account data", exc);
    }
    if (accounts.isEmpty()) {
      accounts.add(new UserAccount(RemoteKernel.GUEST_USER,
                                   RemoteKernel.GUEST_PASSWORD,
                                   EnumSet.allOf(UserPermission.class)));
      try {
        accountPersister.saveUserAccounts(accounts);
      }
      catch (IOException exc) {
        throw new IllegalArgumentException(
            "IOException saving user account data", exc);
      }
    }
    for (UserAccount curAccount : accounts) {
      knownUsers.put(curAccount.getUserName(), curAccount);
    }
    // Prepare a CleanerTask.
    cleanerTask = new ClientCleanerTask(sweepInterval);

    proxy = (RemoteKernel) Proxy.newProxyInstance(Kernel.class.getClassLoader(),
                                                  new Class<?>[] {RemoteKernel.class},
                                                  this);
  }

  // Implementation of interface KernelExtension starts here.
  @Override
  public void initialize() {
    if (enabled) {
      return;
    }
    // Register as event listener with the kernel.
    log.debug("Registering as event listener with local kernel.");
    localKernel.addEventListener(this,
                                 new AcceptingTCSEventFilter());
    // Start the thread that periodically cleans up the list of known clients
    // and event buffers.
    log.debug("Starting cleanerThread.");
    Thread cleanerThread = new Thread(cleanerTask, "cleanerThread");
    cleanerThread.setPriority(Thread.MIN_PRIORITY);
    cleanerThread.start();
    // Ensure a registry is running.
    log.debug("Checking for RMI registry on host " + registryAddress.getHost());
    Optional<Registry> registry;
    if (Objects.equals(registryAddress.getHost(), "localhost")) {
      registry = RMIRegistries.lookupOrInstallRegistry(registryAddress.getPort());
    }
    else {
      registry = RMIRegistries.lookupRegistry(registryAddress.getHost(),
                                              registryAddress.getPort());
    }
    checkState(registry.isPresent(), "RMI registry unavailable");
    rmiRegistry = registry.get();
    // Export this instance via RMI.
    try {
      log.debug("Exporting proxy...");
      UnicastRemoteObject.exportObject(proxy, 0);
      log.debug("Binding instance with RMI registry...");
      rmiRegistry.rebind(RemoteKernel.REGISTRATION_NAME, proxy);
    }
    catch (RemoteException exc) {
      log.error("Could not export or bind with RMI registry", exc);
    }
    enabled = true;
  }

  @Override
  public void terminate() {
    if (!enabled) {
      return;
    }
    try {
      log.debug("Shutting down RMI interface...");
      rmiRegistry.unbind(RemoteKernel.REGISTRATION_NAME);
      UnicastRemoteObject.unexportObject(proxy, true);
    }
    catch (RemoteException | NotBoundException exc) {
      log.warn("Exception shutting down RMI interface", exc);
    }
    log.debug("Terminating cleaner task...");
    cleanerTask.terminate();
    log.debug("Unregistering event listener...");
    localKernel.removeEventListener(this);
    enabled = false;
  }

  @Override
  public boolean isInitialized() {
    return enabled;
  }

  // Implementation of interface EventListener<TCSObjectEvent> starts here.
  @Override
  public void processEvent(TCSEvent event) {
    log.debug("method entry");
    // Forward the event to all clients' event buffers.
    synchronized (knownClients) {
      for (ClientEntry curEntry : knownClients.values()) {
        curEntry.eventBuffer.processEvent(event);
      }
    }
  }

  // Implementation of interface InvocationHandler starts here
  @Override
  public Object invoke(Object proxyInstance, Method method, Object[] args)
      throws Throwable {
    try {
      if (KernelExtension.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      }
      else if (EventListener.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      }
      else if (RemoteKernel.class.equals(method.getDeclaringClass())) {
        CallPermissions perms = method.getAnnotation(CallPermissions.class);
        Class<?>[] paramTypes = method.getParameterTypes();
        if (perms == null) {
          log.warn(method
              + " not annotated with CallPermissions, allowing by default");
        }
        else if (paramTypes.length == 0
            || !ClientID.class.equals(method.getParameterTypes()[0])) {
          log.debug("First parameter not a ClientID, allowing by default: "
              + method);
        }
        else {
          checkCredentialsForAnnotation((ClientID) args[0], perms);
        }
        // Check if this class has a method with the same signature. If yes,
        // directly call that.
        Method invMethod = getLocalMethod(method);
        if (invMethod != null) {
          // If we have a matching method in this class, call that.
          return invMethod.invoke(this, args);
        }
        else {
          // If we don't have a matching method in this class, there should be
          // one in the Kernel interface - pass through to the local kernel, but
          // strip the first parameter (the client ID), first.
          invMethod = getLocalKernelMethod(method);
          Object[] invArgs = new Object[args.length - 1];
          System.arraycopy(args, 1, invArgs, 0, invArgs.length);
          return invMethod.invoke(localKernel, invArgs);
        }
      }
      else {
        throw new UnsupportedKernelOpException("Unexpected declaring class: "
            + method.getDeclaringClass().getName());
      }
    }
    catch (InvocationTargetException exc) {
      throw exc.getCause();
    }
  }

  // Explicit implementation of methods declared in RemoteKernel start here.
  /**
   * Introduce the calling client to the server and authenticate for operations.
   * <p>
   * Declared in {@link RemoteKernel} and proxied here.
   * </p>
   *
   * @param userName The user's name.
   * @param password The user's password.
   * @return An identification object that is required for subsequent method
   * calls.
   * @throws CredentialsException If authentication with the given username and
   * password failed.
   */
  public ClientID login(String userName, String password)
      throws CredentialsException {
    log.debug("method entry");
    if (userName == null) {
      throw new NullPointerException("userName is null");
    }
    if (password == null) {
      throw new NullPointerException("password is null");
    }
    synchronized (knownClients) {
      UserAccount account = knownUsers.get(userName);
      if (account == null || !account.getPassword().equals(password)) {
        log.info("Authentication for user " + userName + " failed.");
        throw new CredentialsException("User authentication for user "
            + userName + " failed.");
      }
      // Generate a new ID for the client.
      ClientID clientId = new ClientID(userName);
      // Add an entry for the newly connected client.
      ClientEntry clientEntry = new ClientEntry(userName,
                                                account.getPermissions());
      knownClients.put(clientId, clientEntry);
      log.debug("New client named " + clientId.getClientName() + " logged in");
      return clientId;
    }
  }

  /**
   * Logout the client with the given ID from the server.
   * After calling this method, the client with the given ID will be not be
   * allowed to call methods on this <code>RemoteKernel</code> any more.
   * <p>
   * Declared in {@link RemoteKernel} and proxied here.
   * </p>
   *
   * @param clientID The client's ID.
   */
  public void logout(ClientID clientID) {
    log.debug("method entry");
    if (clientID == null) {
      throw new NullPointerException("clientID is null");
    }
    // Forget the client so it won't be able to call methods on this kernel and
    // won't receive events any more.
    synchronized (knownClients) {
      knownClients.remove(clientID);
    }
  }

  /**
   * Returns the permissions the client with the given ID is granted.
   * <p>
   * Declared in {@link RemoteKernel} and proxied here.
   * </p>
   *
   * @param clientID The calling client's identification object.
   * @return A set of permissions the client is granted; if the given ID is
   * invalid, the returned Set will be empty.
   */
  public Set<UserPermission> getUserPermissions(ClientID clientID) {
    log.debug("method entry");
    if (clientID == null) {
      throw new NullPointerException("clientID is null");
    }
    Set<UserPermission> result;
    synchronized (knownClients) {
      ClientEntry clientEntry = knownClients.get(clientID);
      if (clientEntry != null) {
        // Set the 'alive' flag for the cleaning thread.
        clientEntry.setAlive(true);
        result = clientEntry.permissions;
      }
      else {
        result = EnumSet.noneOf(UserPermission.class);
      }
    }
    return result;
  }

  public void createUser(ClientID clientID,
                         String userName,
                         String userPassword,
                         Set<UserPermission> userPermissions)
      throws UserExistsException, CredentialsException {
    log.debug("method entry");
    if (userName == null) {
      throw new NullPointerException("userName is null");
    }
    if (userPassword == null) {
      throw new NullPointerException("userPassword is null");
    }
    if (userPermissions == null) {
      throw new NullPointerException("userPermissions is null");
    }
    // Check if a user with the given name already exists.
    UserAccount account = knownUsers.get(userName);
    if (account != null) {
      log.warn("attempt to create existing user '" + userName + "'");
      throw new UserExistsException("user exists: '" + userName + "'");
    }
    account = new UserAccount(userName, userPassword, userPermissions);
    knownUsers.put(userName, account);
  }

  public void setUserPassword(ClientID clientID,
                              String userName,
                              String userPassword)
      throws UserUnknownException, CredentialsException {
    log.debug("method entry");
    // Check if it's the user himself changing his own password.
    boolean userChangesOwnPass;
    synchronized (knownClients) {
      ClientEntry clientEntry = knownClients.get(clientID);
      userChangesOwnPass = userName.equals(clientEntry.userName);
    }
    // If the user is not changing his own password, check for permissions.
    if (!userChangesOwnPass) {
      log.debug("user changes foreign password, checking permissions");
      checkCredentialsForRole(clientID, UserPermission.MANAGE_USERS);
    }
    // Check if a user with the given name really exists.
    UserAccount account = knownUsers.get(userName);
    if (account == null) {
      log.warn("unknown user: " + userName);
      throw new UserUnknownException("unknown user: '" + userName + "'");
    }
    account.setPassword(userPassword);
  }

  public void setUserPermissions(ClientID clientID,
                                 String userName,
                                 Set<UserPermission> userPermissions)
      throws UserUnknownException, CredentialsException {
    log.debug("method entry");
    // Check if a user with the given name really exists.
    UserAccount account = knownUsers.get(userName);
    if (account == null) {
      log.warn("unknown user: " + userName);
      throw new UserUnknownException("unknown user: '" + userName + "'");
    }
    account.setPermissions(userPermissions);
  }

  public void removeUser(ClientID clientID, String userName)
      throws UserUnknownException, CredentialsException {
    log.debug("method entry");
    UserAccount account = knownUsers.remove(userName);
    if (account == null) {
      log.warn("unknown user: " + userName);
      throw new UserUnknownException("unknown user: '" + userName + "'");
    }
  }

  public void setEventFilter(ClientID clientID,
                             EventFilter<TCSEvent> eventFilter)
      throws CredentialsException {
    log.debug("method entry");
    if (eventFilter == null) {
      throw new NullPointerException("eventFilter is null");
    }
    synchronized (knownClients) {
      knownClients.get(clientID).eventBuffer.setFilter(eventFilter);
    }
  }

  /**
   * Fetches events buffered for the client.
   * <p>
   * Declared in {@link RemoteKernel} and proxied here.
   * </p>
   *
   * @param clientID The identification object of the client calling the method.
   * @param timeout A timeout (in ms) for which to wait for events to arrive.
   * @return A list of events (in the order they arrived).
   * @throws CredentialsException If the given client ID does not identify a
   * known client.
   */
  public List<TCSEvent> pollEvents(ClientID clientID, long timeout)
      throws CredentialsException {
    log.debug("method entry");
    ClientEntry clientEntry;
    EventBuffer<TCSEvent> eventBuffer;
    synchronized (knownClients) {
      if (timeout < 0) {
        throw new IllegalArgumentException("timeout is less than 0");
      }
      clientEntry = knownClients.get(clientID);
      if (clientEntry == null) {
        throw new CredentialsException("Unknown client ID: " + clientID);
      }
      eventBuffer = clientEntry.eventBuffer;
    }
    // Get events or wait for one to arrive if none is currently there.
    List<TCSEvent> events = eventBuffer.getEvents(timeout);
    // Set the client's 'alive' flag.
    synchronized (knownClients) {
      clientEntry.setAlive(true);
    }
    return events;
  }

  // Private methods start here.
  private Method getLocalMethod(Method remoteKernelMethod) {
    assert remoteKernelMethod != null;
    try {
      return StandardRemoteKernel.class.getMethod(remoteKernelMethod.getName(),
                                                  remoteKernelMethod.getParameterTypes());
    }
    catch (NoSuchMethodException exc) {
      return null;
    }
  }

  /**
   * Check whether the user described by the given credentials is granted
   * permissions according to the specified user role.
   * <p>
   * This method also sets the 'alive' flag of the client's entry to prevent it
   * from being removed by the cleaner thread.
   * </p>
   *
   * @param clientID The client's identification object.
   * @param requiredPermission The required role/permission.
   * @throws CredentialsException If the user described by the given credentials
   * is not granted permissions according to the specified user role.
   */
  private void checkCredentialsForRole(ClientID clientID,
                                       UserPermission requiredPermission)
      throws CredentialsException {
    log.debug("method entry");
    if (clientID == null) {
      throw new NullPointerException("clientID is null");
    }
    if (requiredPermission == null) {
      throw new NullPointerException("requiredPermission is null");
    }
    // Check if the client is known
    synchronized (knownClients) {
      ClientEntry clientEntry = knownClients.get(clientID);
      if (clientEntry == null) {
        throw new CredentialsException("Client authentication failed.");
      }
      // Set the 'alive' flag for the cleaning thread.
      clientEntry.setAlive(true);
      // Check if the user's permissions are sufficient.
      Set<UserPermission> providedPerms = clientEntry.permissions;
      if (!providedPerms.contains(requiredPermission)) {
        throw new CredentialsException("Client permissions insufficient.");
      }
    }
  }

  /**
   * Check whether the user described by the given credentials is granted
   * permissions according to the given annotation.
   * <p>
   * This method also sets the 'alive' flag of the client's entry to prevent it
   * from being removed by the cleaner thread.
   * </p>
   *
   * @param clientID The client's identification object.
   * @param perms The annotation indicating the required permissions.
   * @throws CredentialsException If the user described by the given credentials
   * is not granted permissions according to the specified annotation.
   */
  private void checkCredentialsForAnnotation(ClientID clientID,
                                             CallPermissions perms)
      throws CredentialsException {
    log.debug("method entry");
    Objects.requireNonNull(clientID, "clientID is null");
    Objects.requireNonNull(perms, "perms is null");

    // Check if the client is known
    synchronized (knownClients) {
      ClientEntry clientEntry = knownClients.get(clientID);
      if (clientEntry == null) {
        throw new CredentialsException("Client authentication failed.");
      }
      // Set the 'alive' flag for the cleaning thread.
      clientEntry.setAlive(true);
      // Check if the user's permissions are sufficient.
      for (UserPermission requiredPerm : perms.value()) {
        if (!clientEntry.permissions.contains(requiredPerm)) {
          throw new CredentialsException("Client permissions insufficient. "
              + "Missing: " + requiredPerm.name());
        }
      }
    }
  }

  private static Method getLocalKernelMethod(Method method)
      throws NoSuchMethodException {
    Objects.requireNonNull(method, "method is null");

    Class<?>[] paramTypes = method.getParameterTypes();
    Class<?>[] newParamTypes = new Class<?>[paramTypes.length - 1];

    System.arraycopy(paramTypes, 1, newParamTypes, 0, newParamTypes.length);
    return Kernel.class.getMethod(method.getName(), newParamTypes);
  }

  // Private classes start here.
  /**
   * Annotation type for injecting whether to do a complete search or not.
   */
  @BindingAnnotation
  @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  static @interface ClientSweepInterval {
    // Nothing here.
  }

  /**
   * Instances of this class are used as containers for data kept about known
   * clients.
   */
  private static final class ClientEntry {

    /**
     * The name of the user that connected with the client.
     */
    private final String userName;
    /**
     * The client's permissions/privilege level.
     */
    private final Set<UserPermission> permissions;
    /**
     * The client's event buffer.
     */
    private final EventBuffer<TCSEvent> eventBuffer;
    /**
     * The client's alive flag.
     */
    private boolean alive = true;

    /**
     * Creates a new ClientEntry.
     *
     * @param name The client's name.
     * @param perms The client's permissions.
     */
    public ClientEntry(String name, Set<UserPermission> perms) {
      if (name == null) {
        throw new NullPointerException("name is null");
      }
      if (perms == null) {
        throw new NullPointerException("perms is null");
      }
      userName = name;
      permissions = perms;
      // Initially refuse to accept any events.
      eventBuffer
          = new EventBuffer<>(new RefusingTCSEventFilter());
    }

    /**
     * Checks whether the client has been seen since the last sweep of the
     * cleaner task.
     *
     * @return <code>true</code> if, and only if, the client has been seen
     * recently.
     */
    public boolean isAlive() {
      return alive;
    }

    /**
     * Sets this client's <em>alive</em> flag.
     *
     * @param isAlive The client's new <em>alive</em> flag.
     */
    public void setAlive(boolean isAlive) {
      alive = isAlive;
    }
  }

  /**
   * A pair of RMI registry host and port.
   */
  static class RegistryAddress {

    /**
     * The RMI registry host.
     */
    private final String host;
    /**
     * The RMI registry port.
     */
    private final int port;

    /**
     * Creates a new instance.
     *
     * @param host The RMI registry host.
     * @param port The RMI registry port.
     */
    RegistryAddress(String host, int port) {
      this.host = requireNonNull(host, "host");
      this.port = port;
    }

    /**
     * Returns the RMI registry host.
     *
     * @return The RMI registry host.
     */
    public String getHost() {
      return host;
    }

    /**
     * Returns the RMI registry port.
     *
     * @return The RMI registry port.
     */
    public int getPort() {
      return port;
    }
  }

  /**
   * A task for cleaning out stale client entries.
   */
  private class ClientCleanerTask
      extends CyclicTask {

    /**
     * Creates a new CleanerTask.
     *
     * @param interval The interval for cleaning out inactive clients (in ms).
     * Must be at least 1000.
     */
    private ClientCleanerTask(long interval) {
      super(interval);
      if (interval < 1000) {
        throw new IllegalArgumentException("interval less than 1000");
      }
    }

    @Override
    protected void runActualTask() {
      log.debug("CleanerTask sweeping");
      synchronized (knownClients) {
        Iterator<Map.Entry<ClientID, ClientEntry>> clientIter
            = knownClients.entrySet().iterator();
        while (clientIter.hasNext()) {
          Map.Entry<ClientID, ClientEntry> curEntry = clientIter.next();
          ClientEntry clientEntry = curEntry.getValue();
          // Only touch the entry if the buffer not currently in use by a
          // client.
          if (!clientEntry.eventBuffer.hasWaitingClient()) {
            // If the client has been seen since the last run, reset the
            // 'alive' flag.
            if (clientEntry.isAlive()) {
              clientEntry.setAlive(false);
            }
            // If the client hasn't been seen since the last run, remove its
            // ID from the list of known clients - the client has been
            // inactive for long enough.
            else {
              log.debug("removing inactive client entry (client user: "
                  + clientEntry.userName + ")");
              clientIter.remove();
            }
          }
        }
      }
    }
  }
}