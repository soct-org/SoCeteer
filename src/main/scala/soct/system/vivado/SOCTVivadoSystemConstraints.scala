package soct.system.vivado

import soct.RegisteredMems
import soct.vivado._
import soct.vivado.components.DDR4
import soct.vivado.fpga.FPGAResetPortSource

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
   * Declare the design's asynchronous top-level ports as false paths.
   *
   * These signals have no timing relationship to any clock in the design: a pushbutton reset
   * that reaches every domain through a synchronizer, and a UART whose 115200-baud bit period is
   * some three orders of magnitude longer than a clock cycle, sampled by an oversampling
   * receiver. Constraining them to a clock would be a fiction.
   *
   * Saying so explicitly is not cosmetic. An unconstrained port is not analyzed, so it can never
   * violate anything and never appears in a slack figure - a design reports timing met while the
   * tool has quietly said nothing at all about those paths. Declaring them false says "analyzed,
   * and deliberately exempt", which is a claim someone can disagree with, and it keeps the
   * `check_timing` report empty enough that a genuinely unconstrained path stands out.
   *
   * @param c          the common design
   * @param boardReset the board reset input port whose name the false path targets
   */
  protected def addAsyncPortConstraints(c: CommonDesign, boardReset: FPGAResetPortSource): Unit = {
    val uartPorts = c.fpga.uartPorts.headOption.toSeq.flatMap { u =>
      Seq(s"${u.portName}_rxd", s"${u.portName}_txd")
    }
    // -quiet throughout: a board without a UART, or a design built without one, simply has no
    // such port, and that is not an error here.
    // Derived, not spelled: the reset input and the DDR4 reset outputs belong to whichever
    // ports the board and this design registered, and a board that names them differently
    // (VCU118's per-channel ddr4_sdram_c1/c2) would otherwise be handed a port name that
    // matches nothing - silently, since both the query and the constraint are -quiet.
    val ddr4Resets = p(RegisteredMems).map(m => s"${m.portName}_reset_n")
    val inputs = Seq(boardReset.portName) ++ uartPorts.filter(_.endsWith("_rxd"))
    val outputs = ddr4Resets ++ uartPorts.filter(_.endsWith("_txd"))
    bd.addTimingConstraints(() => Seq(
      s"""# Asynchronous top-level ports: no clock relationship exists, so none is asserted.
         |# Declared rather than left unconstrained, so that check_timing reports only paths
         |# nobody has thought about (see SOCTVivadoSystemConstraints.addAsyncPortConstraints).
         |${inputs.map(p => s"set_false_path -quiet -from [get_ports -quiet $p]").mkString("\n")}
         |${outputs.map(p => s"set_false_path -quiet -to [get_ports -quiet $p]").mkString("\n")}
         |""".stripMargin.tcl
    ))
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
