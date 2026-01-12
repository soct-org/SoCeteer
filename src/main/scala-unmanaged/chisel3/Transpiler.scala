package soct

import chisel3.{Data, Element}
import chisel3.reflect.DataMirror
import chisel3.stage.ChiselGeneratorAnnotation
import firrtl.AnnotationSeq
import firrtl.annotations.{Annotation, JsonProtocol}
import firrtl.stage.{FirrtlFileAnnotation, FirrtlStage, OutputFileAnnotation}
import org.chipsalliance.cde.config.Parameters

import java.nio.file.{Files, Path}
import firrtl.options.TargetDirAnnotation
import org.chipsalliance.diplomacy.lazymodule.LazyModule

import scala.collection.mutable


object ChiselCompat {
  def collectLeafMembers(data: Data): Seq[Data] = {
    DataMirror.collectMembers(data) {
        case x: Element => x
      }
      .toVector
  }
}

object Transpiler  {

  val stage = new FirrtlStage

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

    def store(path: Path, content: String): Unit = {
      Files.write(path, content.getBytes)
    }

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
      case (ext, contents) => store(paths.systemDir.resolve(s"${c.topModuleName}.$ext"), contents())
    }
  }

  def emitLowFirrtl(c: SOCTLauncher.SOCTConfig, paths: SOCTPaths): Unit = {
    log.info("Using firrtl to generate low-firrtl")
    require(paths.annoFile.toFile.exists(), s"Annotation file ${paths.annoFile} does not exist but is required for chisel3")
    require(paths.firrtlFile.toFile.exists(), s"Firrtl file ${paths.firrtlFile} does not exist but is required.")
    val annos = JsonProtocol.deserialize(paths.annoFile.toFile, allowUnrecognizedAnnotations = false)
    val firrtlAnnos: Seq[Annotation] = Seq(
      FirrtlFileAnnotation(paths.firrtlFile.toString),
      OutputFileAnnotation(paths.lowFirrtlFile.toString)
    ) ++ annos
    val out = stage.execute(Array(s"-ll=${c.args.logLevel}", "-E=low-opt"), firrtlAnnos)
    Files.write(paths.annoFile, JsonProtocol.serialize(out).getBytes)
  }

  def emitVerilog(c: SOCTLauncher.SOCTConfig, paths: SOCTPaths): Unit = {
    log.info("Using firrtl to generate verilog")
    val verilogAnnos: Seq[Annotation] = Seq(
      OutputFileAnnotation(paths.verilogSrc.resolve(s"${c.topModuleName}.v").toString),
      FirrtlFileAnnotation(paths.lowFirrtlFile.toString),
    )
    var loweringArgs = mutable.Seq("-E=verilog", "--start-from=low-opt", s"-ll=${c.args.logLevel}")

    if (!c.args.includeLocationInfo) {
      loweringArgs ++= Seq("--info-mode=ignore")
    }

    loweringArgs ++= c.args.userFirtoolArgs
    stage.execute(loweringArgs.toArray, verilogAnnos)
  }
}
