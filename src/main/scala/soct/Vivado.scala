package soct

import soct.rocket.WrapVHDL

import java.nio.file.{Files, Path, Paths}
import scala.io.Source

object Vivado {

  def generateHeader(systemDir: Path, boardName: String, baseConfig: String, boardParams: BoardParams): String = {
    val vsrcsDir = Utils.synPath().resolve("vsrcs")
    val vhdlsrcsDir = Utils.projectRoot().resolve("src").resolve("main").resolve("resources")

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
         |set vsrcs_dir "${vsrcsDir.toAbsolutePath}"
         |set vhdlsrcs_dir "${vhdlsrcsDir.toAbsolutePath}"
         |set tclsrcs_dir "${Utils.tclSrcsPath().toAbsolutePath}"
         |set workspace_dir "${systemDir.toAbsolutePath}"
         |set boards_dir "${Utils.boardsPath().toAbsolutePath}"
    """

    header.stripMargin
  }

  def generateTCLScript(systemDir: Path, boardName: String, baseConfig: String, boardParams: BoardParams): Path = {
    val tclFile = systemDir.resolve("system.tcl")
    val template = Source.fromFile(Paths.get(s"${Utils.tclSrcsPath()}/vivado.template.tcl").toFile)
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

  def wrapVHDL(systemDir: Path, verilatorBin: Option[Path], verilogFile: Path, baseConfig: String): Path = {
    val outFile = systemDir.resolve("riscv_system.vhdl").toAbsolutePath

    if (verilatorBin.isDefined) {
      // Copy verilog file to systemDir
      val verilogRawFile = systemDir.resolve(verilogFile.getFileName.toString.replace(".v", "-raw.v"))
      Files.copy(verilogFile, verilogRawFile)
      Files.deleteIfExists(verilogFile)
      val cmd = Seq(verilatorBin.get.toAbsolutePath.toString, "-P", "-E", verilogRawFile.toAbsolutePath.toString)
      println("Running Verilator preprocessor with command: " + cmd.mkString(" "))
      val process = new ProcessBuilder(cmd: _*).
        directory(systemDir.toFile)
        .redirectOutput(verilogFile.toFile)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
      val exitCode = process.waitFor()
      if (exitCode != 0) {
        throw new RuntimeException(s"Verilator preprocessor failed with exit code $exitCode")
      }
      WrapVHDL.main(Array("-m", baseConfig, "-o", outFile.toString, verilogFile.toAbsolutePath.toString))
      outFile
    } else {
      println("Verilator binary not found or not executable. Using unprocessed Verilog file instead.")
      Utils.disableStdErr()
      WrapVHDL.main(Array("-m", baseConfig, "-o", outFile.toString, verilogFile.toAbsolutePath.toString))
      Utils.enableStdErr()
      outFile
    }
  }

  def generateProject(tclFile: Path, vivado: Path, vivadoSettings: Option[Path]): Unit = {
    // On windows, it should be a .bat file
    if (Utils.isWindows) {
      println("Not implemented yet")
    } else if (Utils.isUnix) {
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
