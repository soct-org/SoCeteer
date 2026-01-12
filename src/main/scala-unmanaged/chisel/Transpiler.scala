package soct

import chisel3.{Data, Element}
import chisel3.reflect.DataMirror
import circt.stage.ChiselStage
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule

import java.nio.file.{Files, Path}
import scala.collection.mutable

object ChiselCompat {
  def collectLeafMembers(data: Data): Seq[Data] = {
    DataMirror.collectLeafMembers(data)
  }
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
      case (ext, contents) => store(paths.systemDir.resolve(s"${c.topModuleName}.$ext"), contents())
    }
  }

  def emitLowFirrtl(c: SOCTLauncher.SOCTConfig, paths: SOCTPaths): Unit = {
    // Only used for Chisel 3 compiler
  }

  def emitVerilog(c: SOCTLauncher.SOCTConfig, paths: SOCTPaths): Unit = {
    log.info(s"Using Firtool at ${paths.firtoolBinary} to generate Verilog")

    // Args for --lowering-options=
    var loweringOptions = mutable.Seq("disallowPortDeclSharing")

    // Other firtool args
    var otherArgs = if (c.args.singleVerilogFile) {
      val outFile = paths.verilogSrc.resolve(s"${c.topModuleName}.sv")
      mutable.Seq(s"-o=${outFile.toString}", "--verilog", "--disable-layers=Verification")
    } else {
      mutable.Seq(s"-o=${paths.verilogSrc.toString}", "--split-verilog")
    } ++ Seq("--disable-annotation-unknown", "--format=fir", "-O=release")

    if (!c.args.includeLocationInfo) {
      loweringOptions +:= "locationInfoStyle=none"
    }

    if (loweringOptions.nonEmpty) {
      otherArgs +:= s"--lowering-options=${loweringOptions.mkString(",")}"
    }

    val args = Seq(paths.firtoolBinary.toString) ++ c.args.userFirtoolArgs ++ otherArgs ++ Seq(paths.firrtlFile.toString)
    new ProcessBuilder(args: _*)
      .inheritIO()
      .start()
      .waitFor()
  }
}
