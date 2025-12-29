package freechips.rocketchip.rocket {
  // TODO: delete this trait once deduplication is smart enough to avoid globally inlining matching circuits
  trait InlineInstance { self: chisel3.experimental.BaseModule =>
    chisel3.experimental.annotate(self)(Seq(firrtl.passes.InlineAnnotation(self.toNamed)))
  }
}