package soct.system.vivado.misc

import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.resources._
import soct.system.vivado.{TCLCommands, VivadoDesignException}
import soct.system.vivado.abstracts.BdIntfPin

/**
 * Describes a single interrupt line routed to an interrupt controller.
 *
 * @param parent
 * The interrupt-controller [[freechips.rocketchip.resources.Device]] that is the IRQ parent:
 * the AXI INTC device, behind which every fabric peripheral interrupt cascades (see
 * [[soct.system.vivado.components.AXIIntc]] for why the PLIC cannot take them directly).
 * @param index
 * The line's 0-based INTC input number (the interrupt concat input the device drives).
 * Unlike the PLIC's sources, INTC inputs start at 0, so the DTS emits the index as-is -
 * as the first of two cells (`interrupts = <index 0>`; the Xilinx INTC binding fixes
 * `#interrupt-cells = 2` and documents the second cell as unused, the trigger type being
 * hardware configuration carried by `xlnx,kind-of-intr` on the controller node).
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
final case class DTSInfo(
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
   * @throws soct.system.vivado.VivadoDesignException if `rangeBytes` is not positive
   */
  def fromOffsetRange(offset: BigInt, rangeBytes: BigInt): Seq[AddressSet] = {
    if (rangeBytes <= 0) throw VivadoDesignException(s"range must be > 0, got $rangeBytes")

    val isPow2 = (rangeBytes & (rangeBytes - 1)) == 0
    if (isPow2) Seq(AddressSet(offset, rangeBytes - 1))
    else AddressSet.misaligned(offset, rangeBytes)
  }
}

/**
 * Binds [[DTSInfo]] descriptions into RocketChip's resource system.
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
   * Expand every bound interrupt index in a device description to the two-cell Xilinx
   * INTC form `<index 0>` (`#interrupt-cells = 2`; the binding documents the second cell
   * as unused). Done at describe time rather than by binding a second ResourceInt:
   * identical bindings are deduplicated by the resource system, which collapsed
   * `interrupts = <0 0>` to `<0>` for input 0.
   *
   * @param mapping a device description's property map
   * @return the map with the `interrupts` values expanded
   */
  def withXilinxIntcCells(mapping: Map[String, Seq[ResourceValue]]): Map[String, Seq[ResourceValue]] =
    mapping.get("interrupts") match {
      case Some(ints) => mapping.updated("interrupts", ints.flatMap(v => Seq(v, ResourceInt(0))))
      case None => mapping
    }

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
   * @throws soct.system.vivado.VivadoDesignException if `dts` has no compatibles or no register regions
   */
  def bindSimpleDevice(devname: String, dts: DTSInfo, perms: ResourcePermissions = mmioPerms): SimpleDevice = {
    if (dts.compatibles.isEmpty) throw VivadoDesignException(s"$devname: compatibles must be non-empty")
    if (dts.regs.isEmpty) throw VivadoDesignException(s"$devname: regs must be non-empty")

    // SimpleDevice's parent is typed as Some[Device] in RocketChip, so match that exactly.
    val dev = new SimpleDevice(devname, dts.compatibles) {
      override def parent: Some[Device] = Some(dts.parent)
      override def describe(resources: ResourceBindings): Description = {
        val Description(name, mapping) = super.describe(resources)
        Description(name, withXilinxIntcCells(mapping) ++ dts.extraProps)
      }
    }

    ResourceBinding {
      dts.regs.foreach { case (name, offset, rangeBytes) =>
        val sets = AddressSets.fromOffsetRange(offset, rangeBytes)
        Resource(dev, s"reg/$name").bind(ResourceAddress(sets, perms))
      }
      dts.irqs.foreach { case Irq(intc, idx) =>
        // Only the input index is bound; the second cell the Xilinx binding requires is
        // appended at describe time (see withXilinxIntcCells).
        Resource(dev, "int").bind(intc, ResourceInt(idx))
      }
    }
    dev
  }
}