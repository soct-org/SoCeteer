package soct.system.vivado.features

import org.chipsalliance.cde.config.Parameters
import soct.vivado.abstracts.{BdIntfPin, BdPinOut}
import soct.vivado.fpga.FPGA
import soct.vivado.misc.Irq
import soct.vivado.SOCTBdBuilder
import soct.system.vivado.{CommonDesign, MemPath}

/**
 * Everything a feature's wire phase can reach: the common design plus the pins and
 * memory topology only some features need.
 */
case class FeatureWireContext(c: CommonDesign, coreClock: BdPinOut,
                              peripheryClock: BdPinOut, memPaths: Seq[MemPath])

/**
 * One optional Vivado subsystem (UART, SD card, video, USB, PS window): its presence,
 * device-tree contribution and block-design wiring live in one class instead of being
 * spread across the system traits.
 *
 * Presence is decided ONCE, by the companion's `ifPresent` - a present feature exists,
 * an absent one is `None`, and nothing downstream re-derives the decision. CONSTRUCTION
 * IS THE DEVICE-TREE PHASE: it runs in the system trait body, before `bd.init` and
 * before any `InModuleBody`, binding resources and claiming INTC inputs. The device-tree
 * labels (`L<n>`) follow the global Device construction order and the INTC inputs follow
 * claim order, so the construction sequence in [[soct.system.vivado.SOCTVivadoSystemDTS]]
 * is part of the generated-output contract.
 *
 * The wire hooks run inside `InModuleBody`, called by the shared wiring at fixed sites;
 * every default is a no-op. Sites whose scaladoc names an ORDER CONTRACT hand out
 * position-dependent names (SmartConnect port indices, reset-synchronizer fan-out
 * slices), so their call sites must not move relative to the surrounding wiring.
 */
abstract class VivadoFeature(implicit val p: Parameters, val bd: SOCTBdBuilder) {
  /** Short name, used in diagnostics and interrupt claims. */
  def name: String

  /** The INTC inputs this feature claimed at construction, in claim order (the system
   * asserts that feature order and claim order agree). */
  def claimedIrqs: Seq[Irq]

  /** Site: `initCommonDesign`, before the [[CommonDesign]] is assembled. Creates BD
   * components the shared wiring interacts with. */
  def createComponents(fpga: FPGA, axiMMIO: BdIntfPin): Unit = ()

  /** Site: `wirePeripheryFabric`, between the SmartConnect resets and the INTC reset.
   * ORDER CONTRACT: reset edges added here define the reset synchronizer's fan-out
   * slice numbering. */
  def wirePeripheryFabric(peripheryClock: BdPinOut, c: CommonDesign): Unit = ()

  /** Site: `wireInterrupts`, after the INTC hookup. Inputs are addressed by claimed
   * index, so this hook is order-free. */
  def wireIrq(c: CommonDesign): Unit = ()

  /** Site: `wireMmioAndDma`. ORDER CONTRACT: `M_AXI.next()` calls allocate SmartConnect
   * master ports in call order. */
  def wireMmio(c: CommonDesign): Unit = ()

  /** Site: the concrete system's `InModuleBody`, via `wireFeatureMains`. ORDER CONTRACT:
   * the wiring order there allocates SmartConnect ports, reset slices and timing
   * constraint slots. */
  def wireMain(ctx: FeatureWireContext): Unit = ()
}
