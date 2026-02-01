package soct.system.vivado.abstracts

import soct.system.vivado.SOCTBdBuilder

/**
 * Case class representing a clock domain in the design
 *
 * @param freqMHz    The frequency of the clock domain in MHz
 * @param tclVarName Optional name of the dereferenced TCL variable representing this clock domain in the block design, e.g, "$clock_freq"
 */
class ClockDomain(val freqMHz: Double, val tclVarName: Option[String] = None)(implicit bd: SOCTBdBuilder) {
  if (tclVarName.isDefined) {
    bd.args.addBdVar(tclVarName.get, "The core clock frequency in MHz", freqMHz.toString)
  }
}


