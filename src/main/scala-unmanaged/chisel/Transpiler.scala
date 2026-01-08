package soct

import chisel3.RawModule
import circt.stage.ChiselStage
import freechips.rocketchip.subsystem.WithBootROMFile
import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.lazymodule.LazyModule

import java.nio.file.{Files, Path}
import scala.collection.mutable

abstract case class Transpiler() {
}

object Transpiler {

  def evalDesign(c: SOCTLauncher.SOCTConfig, paths: SOCTPaths): Unit = {
    // Contains a callable that returns a chisel model and that is used to generate the firrtl
    val gen = () => c.topModule match {
      case Left(m) =>
        // Get the constructor of m that accepts Parameters
        val constructor = m.getConstructor(classOf[Parameters])
        constructor.newInstance(c.params)
      case Right(lm) =>
        val constructor = lm.getConstructor(classOf[Parameters])
        LazyModule(constructor.newInstance(c.params)).module
    }

    // Store the generated files in the system directory and add them to the artifacts
    def store(path: Path, content: String): Unit = {
      Files.write(path, content.getBytes)
    }

    val circuit = ChiselStage.elaborate(gen(), Array(s"--firtool-binary-path=${c.args.firtoolPath}", s"-ll=${c.args.logLevel}"))
    Files.write(paths.firrtlFile, circuit.serialize.getBytes)

    freechips.rocketchip.util.ElaborationArtefacts.files.foreach {
      case (ext, contents) => store(paths.systemDir.resolve(s"${c.configName}.$ext"), contents())
    }
  }

  def emitLowFirrtl(c: SOCTLauncher.SOCTConfig, paths: SOCTPaths): Unit = {
    // Only used for Chisel 3 compiler
  }

  def emitVerilog(c: SOCTLauncher.SOCTConfig, paths: SOCTPaths, firtoolArgs: Seq[String]): Unit = {
    log.info(s"Using Firtool at ${paths.firtoolBinary} to generate Verilog")
    val verilogArgs = if (c.args.singleVerilogFile) {
      Seq("--verilog", "--disable-layers=Verification", s"-o=${paths.verilogSystem.toString}")
    } else {
      Seq("--split-verilog", s"-o=${paths.verilogSystem.toString}")
    }
    val args = Seq(paths.firtoolBinary.toString) ++ firtoolArgs ++
      Seq("--disable-annotation-unknown", "--format=fir", "-O=release") ++ verilogArgs ++ Seq(paths.firrtlFile.toString)
    new ProcessBuilder(args: _*)
      .inheritIO()
      .start()
      .waitFor()
  }
}
