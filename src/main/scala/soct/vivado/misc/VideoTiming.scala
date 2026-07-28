package soct.vivado.misc

import soct.SOCTFreq._

/**
 * A complete video timing: the active area plus the blanking structure, which together
 * determine the pixel clock (`htotal * vtotal * fps`). A resolution alone determines
 * NOTHING - blanking is a standards choice - so timings come from [[VideoTiming.forMode]]:
 * the exact CEA-861 values for the modes that standard defines (what every monitor
 * expects for them), and VESA CVT reduced blanking computed for everything else.
 *
 * Field naming follows the kernel's `display-timings` device-tree binding, which is also
 * how these values are emitted into the device tree - the timing generator, the
 * DisplayPort main stream attributes and the pixel clock all read one source.
 */
case class VideoTiming(hActive: Int, hFrontPorch: Int, hSyncLen: Int, hBackPorch: Int,
                       vActive: Int, vFrontPorch: Int, vSyncLen: Int, vBackPorch: Int,
                       hSyncPositive: Boolean, vSyncPositive: Boolean, fps: Int) {
  def hTotal: Int = hActive + hFrontPorch + hSyncLen + hBackPorch
  def vTotal: Int = vActive + vFrontPorch + vSyncLen + vBackPorch

  /** The exact clock the blanking structure implies - for CEA-861 entries this
   * reproduces the standard's clock (e.g. 720p60: 1650 * 750 * 60 = 74.25 MHz). */
  def pixelClock: Freq = Freq(hTotal.toDouble * vTotal * fps)
}

object VideoTiming {

  /** The CEA-861 modes this pipeline treats as canonical: exact standard timings, byte
   * for byte what a sink expects for these resolutions. */
  val Cta861Modes: Seq[VideoTiming] = Seq(
    VideoTiming(640, 16, 96, 48, 480, 10, 2, 33, hSyncPositive = false, vSyncPositive = false, fps = 60),
    VideoTiming(1280, 110, 40, 220, 720, 5, 5, 20, hSyncPositive = true, vSyncPositive = true, fps = 60),
    VideoTiming(1920, 88, 44, 148, 1080, 4, 5, 36, hSyncPositive = true, vSyncPositive = true, fps = 60)
  )

  /**
   * VESA CVT 1.2, reduced blanking v1: computes a timing for an arbitrary active area
   * and refresh rate. Reduced blanking assumes a fixed-frequency digital sink (every
   * LCD), shrinking the horizontal blank to 160 pixels and the vertical blank to the
   * standard's 460 us minimum - which also minimizes the pixel clock. One deliberate
   * deviation: CVT quantizes the final clock to 0.25 MHz steps (legacy interop, at the
   * cost of a slightly-off refresh rate); this pipeline keeps the exact
   * htotal * vtotal * fps product instead - the MMCM synthesizes arbitrary clocks and a
   * DisplayPort sink follows the MSA, so the quantization would only skew the refresh.
   */
  def cvtReducedBlanking(hActive: Int, vActive: Int, fps: Int): VideoTiming = {
    require(hActive > 0 && vActive > 0 && fps > 0, s"impossible mode ${hActive}x$vActive@$fps")
    val RbHBlank = 160
    val RbHFrontPorch = 48
    val RbHSync = 32
    val RbHBackPorch = 80
    val RbMinVBlankUs = 460.0
    val RbVFrontPorch = 3
    val MinVBackPorch = 6

    // Vertical sync width encodes the aspect ratio (CVT table 3-1); 10 marks
    // a non-standard aspect.
    val vSync =
      if (hActive * 3 == vActive * 4) 4
      else if (hActive * 9 == vActive * 16) 5
      else if (hActive * 10 == vActive * 16) 6
      else if (hActive * 4 == vActive * 5) 7
      else if (hActive * 9 == vActive * 15) 7
      else 10

    val framePeriodUs = 1e6 / fps
    val hPeriodEstUs = (framePeriodUs - RbMinVBlankUs) / vActive
    val vbiLines = math.floor(RbMinVBlankUs / hPeriodEstUs).toInt + 1
    val actVbiLines = math.max(vbiLines, RbVFrontPorch + vSync + MinVBackPorch)

    VideoTiming(
      hActive, RbHFrontPorch, RbHSync, RbHBackPorch,
      vActive, RbVFrontPorch, vSync, actVbiLines - RbVFrontPorch - vSync,
      // Reduced blanking inverts the standard CVT polarities: hsync +, vsync -.
      hSyncPositive = true, vSyncPositive = false, fps = fps)
  }

  /**
   * The timing for a requested mode: the exact CEA-861 structure when that standard
   * defines the mode, CVT reduced blanking computed otherwise.
   */
  def forMode(hActive: Int, vActive: Int, fps: Int): VideoTiming =
    Cta861Modes.find(t => t.hActive == hActive && t.vActive == vActive && t.fps == fps)
      .getOrElse(cvtReducedBlanking(hActive, vActive, fps))
}
