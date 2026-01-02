package soct

import chisel3.RawModule
import chisel3.stage.ChiselGeneratorAnnotation
import firrtl.AnnotationSeq
import firrtl.annotations.{Annotation, JsonProtocol}
import firrtl.stage.{FirrtlFileAnnotation, FirrtlStage, OutputFileAnnotation}
import freechips.rocketchip.subsystem.WithBootROMFile
import org.chipsalliance.cde.config.{Config, Parameters}

import java.nio.file.{Files, Path}
import firrtl.options.TargetDirAnnotation
import org.chipsalliance.diplomacy.lazymodule.LazyModule

import scala.collection.mutable
import scala.annotation.unused

abstract case class Transpiler() {
}

object Transpiler  {

  val stage = new FirrtlStage

  def evalDesign(top: String, c: SOCTLauncher.Config, paths: SOCTPaths, bootromPath: Path): Set[Path] = {
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

    val artifacts: mutable.HashSet[Path] = mutable.HashSet[Path]()

    def store(path: Path, content: String): Unit = {
      Files.write(path, content.getBytes)
      artifacts += path.toAbsolutePath
    }

    log.info("Using chisel to generate firrtl")
    val annos = Seq(
      new chisel3.stage.phases.Elaborate,
      new chisel3.stage.phases.Convert
    ).foldLeft(
        Seq(TargetDirAnnotation(paths.systemDir.toString), ChiselGeneratorAnnotation(() => gen())): AnnotationSeq) {
        case (annos, phase) => phase.transform(annos)
      }
      .flatMap {
        // They are unserializable, so we don't include them
        case firrtl.stage.FirrtlCircuitAnnotation(c) =>
          store(paths.firrtlFile, c.serialize)
          None
        case _: chisel3.stage.ChiselCircuitAnnotation => None
        case _: chisel3.stage.DesignAnnotation[_] => None
        case a => Some(a)
      }
    store(paths.annoFile, firrtl.annotations.JsonProtocol.serialize(annos))
    freechips.rocketchip.util.ElaborationArtefacts.files.foreach {
      case (ext, contents) => store(paths.systemDir.resolve(s"${c.configs.mkString("_")}.$ext"), contents())
    }
    artifacts.toSet
  }

  def emitLowFirrtl(c: SOCTLauncher.Config, paths: SOCTPaths): Unit = {
    log.info("Using firrtl to generate low-firrtl")
    require(paths.annoFile.toFile.exists(), s"Annotation file ${paths.annoFile} does not exist but is required for chisel3")
    require(paths.firrtlFile.toFile.exists(), s"Firrtl file ${paths.firrtlFile} does not exist but is required.")
    val annos = JsonProtocol.deserialize(paths.annoFile.toFile, allowUnrecognizedAnnotations = false)
    val firrtlAnnos: Seq[Annotation] = Seq(
      FirrtlFileAnnotation(paths.firrtlFile.toString),
      OutputFileAnnotation(paths.lowFirrtlFile.toString)
    ) ++ annos
    stage.execute(Array(s"-ll=${c.args.logLevel}", "-E=low-opt"), firrtlAnnos)
  }

  def emitVerilog(c: SOCTLauncher.Config, paths: SOCTPaths, @unused firtoolPlugins: Seq[String]): Unit = {
    log.info("Using firrtl to generate verilog")
    val verilogAnnos: Seq[Annotation] = Seq(
      OutputFileAnnotation(paths.verilogSystem.toString),
      FirrtlFileAnnotation(paths.lowFirrtlFile.toString)
    )
    stage.execute(Array(s"-ll=${c.args.logLevel}", "-E=verilog", "--start-from=low-opt"), verilogAnnos)
  }
}
