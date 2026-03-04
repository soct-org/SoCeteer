package soct.system.vivado.abstracts

import soct.system.vivado.SOCTBdBuilder

/**
 * Case class representing a clock domain in the design
 *
 * @param freqMHz    The frequency of the clock domain in MHz
 */
class ClockDomain(val freqMHz: Double)(implicit bd: SOCTBdBuilder) {
  require(freqMHz > 0, s"Clock frequency must be positive, got $freqMHz")
  require(freqMHz < 10000, s"Clock frequency seems too high (greater than 10 GHz), got $freqMHz. Did you mean MHz instead of Hz?")
}


