package soct.system.vivado

import soct.system.vivado.components.DDR4

/**
 * The TCL timing-constraint helpers of [[SOCTVivadoSystemBase]] (one file per concern:
 * device tree in [[SOCTVivadoSystemDTS]], components and wiring in
 * [[SOCTVivadoSystemWiring]]). Pure TCL plumbing - nothing here initializes state.
 */
trait SOCTVivadoSystemConstraints {
  this: SOCTVivadoSystemBase =>

  /**
   * Bind a clock-output pin (by hierarchical path) to a triple of TCL variables:
   *   - `<varBase>`: the pin handle
   *   - `<varBase>_clk`: the `get_clocks` object driving it
   *   - `<varBase>_period`: its min PERIOD
   *
   * Pure TCL plumbing — no topology-specific assumptions baked in. Used by the
   * timing-constraint helpers below to turn pin paths into reusable handles.
   *
   * @param pinPath hierarchical pin path - pass a pin's `.ref` (which is hierarchy-aware)
   *                rather than building one from `instanceName`. Matched as
   *                `-filter {NAME =~ *<path>}` with `-hier`: unlike a bare `-hier` search
   *                pattern (which must not contain a hierarchy separator), the filter form
   *                matches paths that cross BD hierarchy groups.
   * @param varBase base TCL variable name (e.g. `"core_clock"`)
   * @return (TCL commands, clockVarName, periodVarName)
   */
  protected def captureClock(pinPath: String, varBase: String): (TCLCommands, String, String) = {
    val clkVar = s"${varBase}_clk"
    val perVar = s"${varBase}_period"
    val cmd =
      s"""# Capture clock object from $pinPath
         |set $varBase [get_pins -quiet -hier -filter {NAME =~ *$pinPath}]
         |set $clkVar [get_clocks -of_objects $$$varBase]
         |set $perVar [get_property -min PERIOD $$$clkVar]
         |""".stripMargin.tcl
    (Seq(cmd), clkVar, perVar)
  }

  /**
   * Capture the core clock as TCL handles and register the capture commands.
   *
   * @param coreClockRef the pin reference of the core clock output
   * @return (clock object variable name, period variable name) for use in further constraints
   */
  protected def registerCoreClockCapture(coreClockRef: String): (String, String) = {
    val (coreClockTCL, coreClockObj, corePeriodProp) = captureClock(coreClockRef, "core_clock")
    bd.addTimingConstraints(() => coreClockTCL)
    (coreClockObj, corePeriodProp)
  }

  /**
   * Register the timing constraints of one DDR4 controller: false paths on its reset and
   * calibration pins and a bounded CDC between its UI clock and the core clock.
   *
   * @param ddr4           the controller
   * @param coreClockObj   TCL variable holding the core clock object (see [[registerCoreClockCapture]])
   * @param corePeriodProp TCL variable holding the core clock period
   */
  protected def addDdr4TimingConstraints(ddr4: DDR4, coreClockObj: String, corePeriodProp: String): Unit = {
    bd.addTimingConstraints(() => Seq(
      s"""# Timing constraints for DDR4 controller (${ddr4.bdPath})
         |set ddrmc_inst [get_cells -hier -filter {NAME =~ *${ddr4.bdPath}}]
         |if { [llength $$ddrmc_inst] != 1 } { error "expected exactly one cell matching ${ddr4.bdPath}, got: $$ddrmc_inst" }
         |set_false_path -through [get_pins $$ddrmc_inst/${ddr4.SYS_RST.pin}]
         |set_false_path -through [get_pins $$ddrmc_inst/${ddr4.C0_INIT_CALIB_COMPLETE.pin}]
         |set ddrc_clock [get_clocks -of_objects [get_pins $$ddrmc_inst/${ddr4.C0_DDR4_UI_CLK.pin}]]
         |set ddrc_clock_period [get_property -min PERIOD $$ddrc_clock]
         |set_max_delay -from $$$coreClockObj -to $$ddrc_clock -datapath_only $$ddrc_clock_period
         |set_max_delay -from $$ddrc_clock -to $$$coreClockObj -datapath_only $$$corePeriodProp
         |""".stripMargin.tcl
    ))
  }
}
