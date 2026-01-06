package soct.xilinx

import soct.SOCTLauncher.Config
import soct.{BoardParams, BoardSOCTPaths, SOCTPaths, SOCTUtils}

import java.nio.file.{Files, Path}
import scala.io.Source

object SOCTVivado {


  val DEFAULT_MEMORY_ADDR_64: BigInt = BigInt("0x80000000")

  val DEFAULT_MEMORY_ADDR_32: BigInt = BigInt("0x40000000")

  val DEFAULT_MMIO_ADDR = "0x60000000"

  def generateHeader(systemDir: Path, boardName: String, baseConfig: String, boardParams: BoardParams): String = {
    var header =
      s"""
         |set vivado_board_name "$boardName"
         |set xilinx_part "${boardParams.XILINX_PART}"
         |set riscv_module_name "$baseConfig"
         |set riscv_clock_frequency "${boardParams.ROCKET_FREQ_MHZ.get}"
         |set memory_size "${boardParams.MEMORY_SIZE}"
    """
    if (boardParams.BOARD_PART.isDefined) {
      header += s"""|set vivado_board_part "${boardParams.BOARD_PART.get}""""
    }
    // add the paths:
    header +=
      s"""
         |set vsrcs_dir "${SOCTPaths.get("vsrcs")}"
         |set vhdlsrcs_dir "${SOCTPaths.get("vhdlsrcs")}"
         |set tclsrcs_dir "${SOCTPaths.get("tclsrcs")}"
         |set workspace_dir "${systemDir.toAbsolutePath}"
         |set boards_dir "${SOCTPaths.get("boards")}"
    """

    header.stripMargin
  }

  def generateTCLScript(systemDir: Path, boardName: String, baseConfig: String, boardParams: BoardParams): Path = {
    val tclFile = systemDir.resolve("system.tcl")
    val template = Source.fromFile(SOCTPaths.get("tclsrcs").resolve("vivado.template.tcl").toFile)
    val header = generateHeader(systemDir, boardName, baseConfig, boardParams)
    val full = header + "\n" + template.mkString
    // write to file
    Files.write(tclFile, full.getBytes)
    tclFile
  }

  def synthesize(systemDir: Path, vivado: Path, vivadoSettings: Option[Path], maxThreads: Int): Unit = {
    throw new NotImplementedError("Synthesis is not implemented yet")
    val vivadoProject = systemDir.resolve(s"vivado-${systemDir.getFileName}.xpr")
    assert(vivadoProject.toFile.exists(), s"Vivado project file $vivadoProject does not exist, please run generateVivadoProject first")

    val tclFile = systemDir.resolve("make-synthesis.tcl")
    val synScript =
      s"""
         |set_param general.maxThreads $maxThreads
         |open_project "${vivadoProject.toAbsolutePath}"
         |update_compile_order -fileset sources_1
         |reset_run synth_1
         |launch_runs -jobs $maxThreads synth_1
         |wait_on_run synth_1
         |""".stripMargin

  }


  def generate(boardPaths: BoardSOCTPaths, config: Config): Unit = {

  }

  def generateProject(tclFile: Path, vivado: Path, vivadoSettings: Option[Path]): Unit = {
    // On windows, it should be a .bat file
    if (SOCTUtils.isWindows) {
      println("Not implemented yet")
    } else if (SOCTUtils.isUnix) {
      var cmd = s"${vivado.toAbsolutePath} -mode batch -source ${tclFile.toAbsolutePath}"
      if (vivadoSettings.isDefined) {
        cmd = s"source ${vivadoSettings.get} && $cmd"
      }
      val args = Seq("bash", "-c", cmd)
      println("Running Vivado with command: " + args.mkString(" "))
      val process = new ProcessBuilder(args: _*).directory(tclFile.getParent.toFile).inheritIO().start()
      val exitCode = process.waitFor()
      if (exitCode != 0) {
        throw new RuntimeException(s"Vivado failed with exit code $exitCode")
      }
    }
  }
}
