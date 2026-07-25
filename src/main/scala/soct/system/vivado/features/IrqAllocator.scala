package soct.system.vivado.features

/**
 * Hands out AXI INTC input indices in claim order and accumulates the edge mask
 * (bit i set = input i carries an edge/pulse interrupt; unset bits are levels - the
 * same encoding as the IP's C_KIND_OF_INTR and the device tree's xlnx,kind-of-intr).
 *
 * Claims happen at system-construction time, while the device-tree entries are built;
 * the totals ([[count]], [[edgeMask]]) are read afterwards by the INTC's DTS node and
 * its instantiation, when every input is claimed. The claim order is the hardware
 * input order - it must match the order interrupt lines are concatenated into the
 * INTC, which is why claims are recorded by name and asserted against the feature
 * order at construction.
 */
final class IrqAllocator {
  final case class Claim(name: String, index: Int, edge: Boolean)

  private var allClaims = List.empty[Claim]

  /** Claims the next INTC input and returns its index. */
  def claim(name: String, edge: Boolean): Int = {
    val idx = allClaims.size
    allClaims = allClaims :+ Claim(name, idx, edge)
    idx
  }

  /** Number of claimed inputs (the INTC's input count). */
  def count: Int = allClaims.size

  /** The C_KIND_OF_INTR / xlnx,kind-of-intr bitmask over the claimed inputs. */
  def edgeMask: Int = allClaims.filter(_.edge).map(c => 1 << c.index).sum

  /** All claims, in claim order. */
  def claims: Seq[Claim] = allClaims
}
