package soct.system.vivado.features

/**
 * The fabric MMIO register map: every peripheral occupies one 64 KiB region under the
 * Rocket MMIO port. The device-tree `reg` entries and the block design's address
 * assignment both flow from these values through a peripheral's [[soct.vivado.misc.DTSInfo]]
 * (components emit their `assign_bd_address` from the same DTSInfo), so the two views
 * cannot disagree. Base addresses are part of the software contract - drivers, the
 * boot arguments' earlycon and the generated device tree all embed them.
 */
object VivadoMmioMap {
  /** One region per peripheral; also every region's size. */
  val RegionSize: Long = 0x10000L

  val SdBase: Long = 0x60000000L
  val UartBase: Long = 0x60010000L
  val VdmaBase: Long = 0x60020000L
  val VtcBase: Long = 0x60030000L
  val VideoStatusBase: Long = 0x60040000L
  val IntcBase: Long = 0x60050000L
  val SysResetBase: Long = 0x60060000L
}
