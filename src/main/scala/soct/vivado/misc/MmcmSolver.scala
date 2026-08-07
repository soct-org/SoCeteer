package soct.vivado.misc

import soct.SOCTFreq._
import soct.vivado.VivadoDesignException

/**
 * One MMCM setting: output = input * mult / (div * odiv), with the fractional fields in
 * eighths (the MMCM's fractional granularity - CLKFBOUT_MULT_F and CLKOUT0_DIVIDE_F step
 * in 0.125). The clocking wizard's reconfiguration registers express fractions in
 * thousandths; eighths convert exactly ([[multThousandths]]/[[odivThousandths]]).
 *
 * @param div          DIVCLK_DIVIDE (integer)
 * @param multEighths  CLKFBOUT_MULT_F in eighths (e.g. 95 = 11.875)
 * @param odivEighths  CLKOUT0_DIVIDE_F in eighths
 * @param achieved     the exactly achieved output frequency
 */
case class MmcmSetting(div: Int, multEighths: Int, odivEighths: Int, achieved: Freq) {
  def multIntPart: Int = multEighths / 8
  def multThousandths: Int = (multEighths % 8) * 125
  def odivIntPart: Int = odivEighths / 8
  def odivThousandths: Int = (odivEighths % 8) * 125
}

/**
 * Solves an UltraScale+ MMCM (MMCME4) configuration for one output: find integer
 * DIVCLK_DIVIDE and eighth-step CLKFBOUT_MULT_F / CLKOUT0_DIVIDE_F such that
 * `input * mult / (div * odiv)` lands closest to the target, honoring the device limits
 * (DS925): VCO in [800, 1600] MHz, PFD = input/div in [10, 500] MHz, mult in [2, 128],
 * odiv in [1, 128]. Only the MMCM's CLKOUT0 divides fractionally, which is the output
 * the clocking wizard maps its first output clock to - this solver is therefore only
 * valid for a wizard's clk_out1.
 */
object MmcmSolver {
  /** MMCME4 analog window (DS925) - public because the device tree advertises it to
   * runtime solvers, which must reject what this solver would reject. */
  val VcoMin = 800.MHz
  val VcoMax = 1600.MHz
  val PfdMin = 10.MHz
  val PfdMax = 500.MHz

  /**
   * @param input     the MMCM input frequency
   * @param target    the requested output frequency
   * @param tolerance largest acceptable relative error (e.g. 0.005 = 0.5%, the slack
   *                  CTA-861 sinks accept on a pixel clock)
   * @return the closest achievable setting
   * @throws VivadoDesignException if no setting lands within the tolerance
   */
  def solve(input: Freq, target: Freq, tolerance: Double = 0.005): MmcmSetting = {
    val inHz = input.toHz
    val targetHz = target.toHz
    var best: Option[MmcmSetting] = None
    var bestErr = Double.MaxValue

    var div = 1
    while (div <= 106) {
      val pfd = inHz / div
      if (pfd >= PfdMin.toHz && pfd <= PfdMax.toHz) {
        // For each odiv, the mult that would hit the target exactly, rounded to eighths.
        // The MMCM's fractional output divide starts at 2.000 (UG572); only the integer
        // divide reaches down to 1, so non-integer eighths below 2.000 are skipped.
        var odivEighths = 8 // 1.000
        while (odivEighths <= 128 * 8) {
          if (odivEighths >= 16 || odivEighths % 8 == 0) {
            val odiv = odivEighths / 8.0
            val exactMult = targetHz * div * odiv / inHz
            Seq(math.floor(exactMult * 8).toInt, math.ceil(exactMult * 8).toInt).foreach { multEighths =>
              if (multEighths >= 2 * 8 && multEighths <= 128 * 8) {
                val mult = multEighths / 8.0
                val vco = inHz * mult / div
                if (vco >= VcoMin.toHz && vco <= VcoMax.toHz) {
                  val achieved = vco / odiv
                  val err = math.abs(achieved - targetHz) / targetHz
                  if (err < bestErr) {
                    bestErr = err
                    best = Some(MmcmSetting(div, multEighths, odivEighths, Freq(achieved)))
                  }
                }
              }
            }
          }
          odivEighths += 1
        }
      }
      div += 1
    }

    best.filter(_ => bestErr <= tolerance).getOrElse(throw VivadoDesignException(
      f"No MMCM setting reaches $target from $input within ${tolerance * 100}%.2f%% " +
        f"(closest error: ${if (best.isDefined) f"$bestErr%.4f" else "none in range"})."))
  }
}
