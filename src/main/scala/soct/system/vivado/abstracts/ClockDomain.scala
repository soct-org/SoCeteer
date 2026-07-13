package soct.system.vivado.abstracts

import soct.SOCTFreq._
import soct.system.vivado.{SOCTBdBuilder, VivadoDesignException}

/**
 * A clock domain in the design.
 *
 * @param freq The frequency of the clock domain
 * @throws soct.system.vivado.VivadoDesignException if the frequency is not positive or is implausibly high (>= 10 GHz)
 */
class ClockDomain(val freq: Freq)(implicit bd: SOCTBdBuilder) {
  if (freq <= 0.Hz) throw VivadoDesignException(s"Clock frequency must be positive, got $freq")
  if (freq >= 10.GHz) throw VivadoDesignException(s"Clock frequency seems too high, got $freq. Did you mean MHz instead of Hz?")
}


