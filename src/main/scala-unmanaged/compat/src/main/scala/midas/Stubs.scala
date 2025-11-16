package midas.targetutils

import chisel3.Printable

/**
 * Minimal no-op performance counter helper used by generators.
 * Accepts chisel Bool or Scala Boolean and a name/description.
 */
object PerfCounter {
  def apply(cond: chisel3.Bool, name: String, description: String): Unit = { () }
  def apply(cond: Boolean, name: String, description: String): Unit = { () }
}

/**
 * Minimal SynthesizePrintf shim that passes through its arguments.
 */
object SynthesizePrintf {
  def apply(fmt: String, args: chisel3.Data*): Printable = {
    Printable.pack(fmt, args:_*)
  }
}
