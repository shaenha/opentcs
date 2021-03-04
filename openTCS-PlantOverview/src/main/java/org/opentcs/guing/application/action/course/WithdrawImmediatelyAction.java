/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.guing.application.action.course;

import com.google.inject.assistedinject.Assisted;
import java.awt.Component;
import java.awt.event.ActionEvent;
import static java.util.Objects.requireNonNull;
import javax.inject.Inject;
import javax.swing.AbstractAction;
import javax.swing.JOptionPane;
import org.opentcs.access.KernelRuntimeException;
import org.opentcs.access.SharedKernelServicePortal;
import org.opentcs.access.SharedKernelServicePortalProvider;
import org.opentcs.components.kernel.services.VehicleService;
import org.opentcs.data.TCSObjectReference;
import org.opentcs.data.model.Vehicle;
import org.opentcs.guing.application.ApplicationFrame;
import org.opentcs.guing.model.elements.VehicleModel;
import org.opentcs.guing.util.ResourceBundleUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class WithdrawImmediatelyAction
    extends AbstractAction {

  /**
   * Withdraws the current transport order from a vehicle immediately and sets its ProcState to
   * UNAVAILABLE.
   */
  public static final String ID = "course.vehicle.withdrawTransportOrderImmediately";
  /**
   * This class's logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(WithdrawImmediatelyAction.class);
  /**
   * The vehicle.
   */
  private final VehicleModel vehicleModel;
  /**
   * Provides access to a portal.
   */
  private final SharedKernelServicePortalProvider portalProvider;
  /**
   * The parent component for dialogs shown by this action.
   */
  private final Component dialogParent;

  /**
   * Creates a new instance.
   *
   * @param vehicle The selected vehicle.
   * @param portalProvider Provides access to a shared portal.
   * @param dialogParent The parent component for dialogs shown by this action.
   */
  @Inject
  public WithdrawImmediatelyAction(@Assisted VehicleModel vehicle,
                                   SharedKernelServicePortalProvider portalProvider,
                                   @ApplicationFrame Component dialogParent) {
    this.vehicleModel = requireNonNull(vehicle, "vehicle");
    this.portalProvider = requireNonNull(portalProvider, "portalProvider");
    this.dialogParent = requireNonNull(dialogParent, "dialogParent");
  }

  @Override
  public void actionPerformed(ActionEvent evt) {
    ResourceBundleUtil bundle = ResourceBundleUtil.getBundle();
    int dialogResult
        = JOptionPane.showConfirmDialog(dialogParent,
                                        bundle.getString("course.vehicle.withdrawTransportOrderImmediately.confirmation.text"),
                                        bundle.getString("course.vehicle.withdrawTransportOrderImmediately.confirmation.title"),
                                        JOptionPane.OK_CANCEL_OPTION,
                                        JOptionPane.WARNING_MESSAGE);

    if (dialogResult != JOptionPane.OK_OPTION) {
      return;
    }

    try (SharedKernelServicePortal sharedPortal = portalProvider.register()) {
      sharedPortal.getPortal().getDispatcherService().withdrawByVehicle(
          vehicleReference(sharedPortal.getPortal().getVehicleService()), true);
    }
    catch (KernelRuntimeException e) {
      LOG.warn("Unexpected exception", e);
    }
  }

  private TCSObjectReference<Vehicle> vehicleReference(VehicleService vehicleService) {
    return vehicleService.fetchObject(Vehicle.class, vehicleModel.getName()).getReference();
  }

}
