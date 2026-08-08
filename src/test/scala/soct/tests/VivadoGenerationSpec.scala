package soct.tests

import org.chipsalliance.cde.config.Config
import org.scalatest.flatspec.AnyFlatSpec
import soct.vivado.VivadoDesignException
import soct.{HasSDCardPMOD, NeedsFatFS, SOCTLauncher, SOCTPaths}

import java.nio.file.{Files, Path}

/** What a board without a 3.3 V PMOD bank uses instead of the SD-carrying default:
 * a configuration that never enables the controller. Top-level with a zero-argument
 * constructor so `--with-config` can instantiate it by reflection. */
class WithoutSdCard extends Config((_, _, _) => {
  case HasSDCardPMOD => None
  case NeedsFatFS => false
})

/**
 * Generation-level board coverage: every registered board must generate a Vivado project
 * (no Vivado run - `--vivado` is omitted, so only elaboration, Verilog emission and TCL/XDC
 * generation are exercised), and board-impossible feature requests must fail loudly at
 * generation time. This is the guard against board facts leaking into the shared
 * generation path - a leak either breaks the non-ZCU104 generation or surfaces as a
 * missing loud error here.
 */
class VivadoGenerationSpec extends AnyFlatSpec {

  private val testWorkspace: Path = SOCTPaths.get("test-workspace").resolve("vivado-gen")

  /** The design's own exception, unwrapped from the reflective-instantiation layers around
   * elaboration (the top module is constructed by reflection, so a refusal thrown in its
   * constructor arrives wrapped in InvocationTargetException). */
  private def designError(body: => Unit): VivadoDesignException = {
    val thrown = intercept[Exception](body)
    Iterator.iterate[Throwable](thrown)(_.getCause)
      .takeWhile(_ != null)
      .collectFirst { case e: VivadoDesignException => e }
      .getOrElse(fail(s"expected a VivadoDesignException in the cause chain, got: $thrown"))
  }

  private def generate(board: String, withConfigs: Seq[String], extraArgs: Seq[String] = Seq.empty): Unit = {
    val args = Seq(
      "--log-level", "error",
      "--config", "soct.RocketB1",
      "-t", "vivado.bd",
      "--board", board,
      "--no-latest-soct-system",
      "--workspace", testWorkspace.toString,
    ) ++ withConfigs.flatMap(c => Seq("--with-config", c)) ++ extraArgs
    SOCTLauncher.main(args.toArray)
  }

  "ZCU104" should "generate with the default feature set" in {
    generate("ZCU104", Seq.empty)
    assert(Files.exists(testWorkspace.resolve("RocketB1-64").resolve("ZCU104").resolve("SOCTSystem.cmake")))
  }

  "VCU118" should "generate without the SD-card PMOD" in {
    // No SD controller means no sd-boot ROM; msip-boot parks the harts for JTAG loading.
    generate("VCU118", Seq("soct.tests.WithoutSdCard"), Seq("--bootrom", "msip-boot"))
    assert(Files.exists(testWorkspace.resolve("RocketB1-64").resolve("VCU118").resolve("SOCTSystem.cmake")))
  }

  it should "refuse the default SD-card PMOD on its 1.8 V PMOD bank" in {
    val e = designError(generate("VCU118", Seq.empty))
    assert(e.getMessage.contains("PmodSD"))
  }

  it should "refuse a video stream without a Zynq PS" in {
    val e = designError(
      generate("VCU118", Seq("soct.WithVideoStream", "soct.tests.WithoutSdCard"), Seq("--bootrom", "msip-boot")))
    assert(e.getMessage.contains("Zynq UltraScale+ PS"))
  }
}
