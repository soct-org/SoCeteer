package soct

import chisel3.RawModule
import circt.stage.ChiselStage
import soct.SOCTLauncher.SOCTPaths
import freechips.rocketchip.subsystem.WithBootROMFile
import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.lazymodule.LazyModule

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.mutable

abstract case class Transpiler() {
}

object Transpiler {

  def evalDesign(top: String, c: SOCTLauncher.Config, paths: SOCTPaths, bootromPath: Path): Set[Path] = {
    // Contains a callable that returns a chisel model and that is used to generate the firrtl
    val gen = () => Class
      .forName(top)
      .getConstructor(classOf[Parameters])
      .newInstance(
        new WithBootROMFile(bootromPath.toString) ++
          new Config(c.configs.foldRight(Parameters.empty) {
            case (currentName, config) =>
              val currentConfig = SOCTUtils.instantiateConfig(currentName)
              currentConfig ++ config
          }))
    match {
      case m: RawModule => m
      case lm: LazyModule => LazyModule(lm).module
    }

    // Contains all the paths to the generated files
    val artifacts: mutable.HashSet[Path] = mutable.HashSet[Path]()

    // Store the generated files in the system directory and add them to the artifacts
    def store(path: Path, content: String): Unit = {
      Files.write(path, content.getBytes)
      artifacts += path.toAbsolutePath
    }

    // First pass contains default rocket bootrom, we don't need to emit anything
    if (bootromPath == paths.rocketBootrom) {
      ChiselStage.elaborate(gen(), Array(s"-ll=${c.args.logLevel}"))
    } else {
      // Second pass generates verilog and dump firrtl
      val circuit = ChiselStage.elaborate(gen(), Array(s"--firtool-binary-path=${c.args.firtoolPath}", s"-ll=${c.args.logLevel}"))
      Files.write(paths.firrtlFile, circuit.serialize.getBytes)
    }
    freechips.rocketchip.util.ElaborationArtefacts.files.foreach {
      case (ext, contents) => store(paths.systemDir.resolve(s"${c.configs.mkString("_")}.$ext"), contents())
    }
    artifacts.toSet
  }

  def emitLowFirrtl(c: SOCTLauncher.Config, paths: SOCTPaths): Unit = {
    // Only used for Chisel 3 compiler
  }

  def emitVerilog(c: SOCTLauncher.Config, paths: SOCTPaths, firtoolArgs: Seq[String]): Unit = {
    log.info(s"Using Firtool at ${paths.firtoolBinary} to generate Verilog")
    val outPath = paths.systemDir.resolve(paths.systemName)
    val verilogArgs = if (c.args.singleVerilogFile) {
      Seq("--verilog", "--disable-layers=Verification", s"-o=$outPath.sv") // verilog outputs system verilog
    } else {
      Seq("--split-verilog", s"-o=$outPath")
    }
    val args = Seq(paths.firtoolBinary.toString) ++ firtoolArgs ++
      Seq("--disable-annotation-unknown", "--format=fir", "-O=release") ++ verilogArgs ++ Seq(paths.firrtlFile.toString)
    new ProcessBuilder(args: _*)
      .inheritIO()
      .start()
      .waitFor()
  }
}
