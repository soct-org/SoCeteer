package soct.system.vivado.misc

import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.resources._

/**
 * Describes a single interrupt line routed to an interrupt controller.
 *
 * @param parent
 * The interrupt-controller [[freechips.rocketchip.resources.Device]] that is the IRQ parent
 * (e.g. the PLIC device).
 * @param index
 * The interrupt index/ID as seen by the parent controller.
 */
final case class Irq(parent: Device, index: Int)

/**
 * Describes how an AXI(-Lite) MMIO slave device should appear in the generated device tree.
 *
 * This is intentionally "structural" rather than tied to any specific IP block, so multiple
 * components can share the same binding logic.
 *
 * @param parent
 * The parent node under which this device should appear (typically a bus device such as
 * the RocketChip external MMIO port SimpleBus).
 * @param regs
 * Register regions in the parent bus address space:
 * `(reg-name, offset, rangeBytes)`.
 * These become `reg-names` and `reg` entries in DTS.
 * @param compatibles
 * The `compatible` strings for the device node, in priority order.
 * @param irqs
 * Optional interrupts. If there is exactly one unique interrupt parent, RocketChip will emit
 * `interrupt-parent` + `interrupts`. If there are multiple parents, it will emit
 * `interrupts-extended`.
 * @param extraProps
 * Additional device-tree properties to attach verbatim. The key is the DTS property name,
 * and the values are the already-typed RocketChip [[ResourceValue]]s.
 */
final case class AxiSlaveDts(
                              parent: Device,
                              regs: Seq[(String, BigInt, BigInt)],
                              compatibles: Seq[String],
                              irqs: Seq[Irq] = Nil,
                              extraProps: Map[String, Seq[ResourceValue]] = Map.empty
                            )

/**
 * Helpers for generating [[AddressSet]]s from a `(base, size)` style description.
 */
object AddressSets {

  /**
   * Convert `(offset, rangeBytes)` into one or more [[AddressSet]]s.
   *
   * - For power-of-two `rangeBytes`, we use `AddressSet(offset, rangeBytes - 1)` (mask form).
   * - Otherwise, we fall back to `AddressSet.misaligned`, which may produce multiple sets.
   *
   * @param offset
   * Base address within the parent address space.
   * @param rangeBytes
   * Size in bytes (must be > 0).
   */
  def fromOffsetRange(offset: BigInt, rangeBytes: BigInt): Seq[AddressSet] = {
    require(rangeBytes > 0, s"range must be > 0, got $rangeBytes")

    val isPow2 = (rangeBytes & (rangeBytes - 1)) == 0
    if (isPow2) Seq(AddressSet(offset, rangeBytes - 1))
    else AddressSet.misaligned(offset, rangeBytes)
  }
}

/**
 * Binds [[AxiSlaveDts]] descriptions into RocketChip's resource system.
 *
 * The returned [[SimpleDevice]] participates in `HasDTS` / BindingScope device-tree emission.
 */
object AxiSlaveBinder {

  /**
   * Default permissions for typical memory-mapped registers.
   *
   * - readable/writable
   * - not executable
   * - not marked cacheable
   * - no atomics
   */
  val mmioPerms: ResourcePermissions =
    ResourcePermissions(r = true, w = true, x = false, c = false, a = false)

  /**
   * Create and bind a DTS node for an AXI MMIO slave.
   *
   * This:
   *  - creates a [[SimpleDevice]] under `dts.parent`
   *  - binds each `regs` entry as a named `reg/<name>` resource
   *  - binds each IRQ as an `int` resource
   *  - binds `extraProps` as raw properties
   *
   * @param devname
   * Base device name (e.g. "serial", "mmc", "eth"). The final node name may become
   * `devname@<address>` depending on the registered ranges.
   * @param dts
   * The device-tree description.
   * @param perms
   * Permissions for the `reg` resources.
   * @return
   * The created [[SimpleDevice]] handle (useful for references/aliases).
   */
  def bindSimpleDevice(devname: String, dts: AxiSlaveDts, perms: ResourcePermissions = mmioPerms): SimpleDevice = {
    require(dts.compatibles.nonEmpty, s"$devname: compatibles must be non-empty")
    require(dts.regs.nonEmpty, s"$devname: regs must be non-empty")

    // SimpleDevice's parent is typed as Some[Device] in RocketChip, so match that exactly.
    val dev = new SimpleDevice(devname, dts.compatibles) {
      override def parent: Some[Device] = Some(dts.parent)
      override def describe(resources: ResourceBindings): Description = {
        val Description(name, mapping) = super.describe(resources)
        Description(name, mapping ++ dts.extraProps)
      }
    }

    ResourceBinding {
      dts.regs.foreach { case (name, offset, rangeBytes) =>
        val sets = AddressSets.fromOffsetRange(offset, rangeBytes)
        Resource(dev, s"reg/$name").bind(ResourceAddress(sets, perms))
      }
      // 2) IRQs -> interrupts / interrupts-extended (chosen by SimpleDevice.describeInterrupts)
      dts.irqs.foreach { case Irq(intc, idx) =>
        Resource(dev, "int").bind(intc, ResourceInt(idx))
      }
    }
    dev
  }
}