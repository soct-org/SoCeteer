package soct.vivado.abstracts

import soct.ClockSpec
import soct.SOCTFreq._
import soct.vivado.{SOCTBdBuilder, VivadoDesignException}

/**
 * A clock domain in the design, wrapping its [[soct.ClockSpec]] (frequency, duty cycle,
 * phase). Components that generate clocks read the whole spec - a ClkWiz output, for
 * example, emits requested duty cycle and phase when they differ from the defaults -
 * while components that merely run in the domain typically read only [[freq]].
 *
 * @param spec the clock's physical parameters
 * @throws soct.vivado.VivadoDesignException if the frequency is not positive or is implausibly high (>= 10 GHz)
 */
class ClockDomain(val spec: ClockSpec)(implicit bd: SOCTBdBuilder) {
  def this(freq: Freq)(implicit bd: SOCTBdBuilder) = this(ClockSpec(freq))

  def freq: Freq = spec.freq
  def dutyCycle: Double = spec.dutyCycle
  def phaseDeg: Double = spec.phaseDeg

  if (freq <= 0.Hz) throw VivadoDesignException(s"Clock frequency must be positive, got $freq")
  if (freq >= 10.GHz) throw VivadoDesignException(s"Clock frequency seems too high, got $freq. Did you mean MHz instead of Hz?")
}
