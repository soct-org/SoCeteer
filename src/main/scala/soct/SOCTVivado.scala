package soct

import soct.SOCTLauncher.Config
import soct.antlr.verilog.sv2017Parser.List_of_port_declarationsContext

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.io.Source

object SOCTVivado {

  // Constants:
  val VIVADO_WRAPPER_TOP = "soct_system"


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

  def wrapVHDL(paths: SOCTPaths, config: Config, top: String, language: String = "1364-2005"): Unit = {
    val verilatorOpt = SOCTUtils.findVerilator()
    val systemFile = paths.verilogSystem
    require(Files.exists(systemFile) && !Files.isDirectory(systemFile), s"Output file $systemFile does not exist or is a directory")

    if (verilatorOpt.isDefined) {
      val (exe, root) = verilatorOpt.get

      // Add .raw to the original verilog file and copy the contents
      val rawSystem = {
        val fileName = systemFile.getFileName.toString
        val dotIndex = fileName.lastIndexOf('.')
        val (name, ext) = if (dotIndex > 0) {
          (fileName.substring(0, dotIndex), fileName.substring(dotIndex))
        } else {
          (fileName, "")
        }
        systemFile.resolveSibling(s"$name.raw$ext")
      }
      Files.copy(systemFile, rawSystem)
      Files.deleteIfExists(systemFile)

      val cmd = Seq(exe.toAbsolutePath.toString, "--language", language, "-P", "-E", rawSystem.toAbsolutePath.toString)
      val processBuilder = new ProcessBuilder(cmd: _*)
        .redirectOutput(systemFile.toFile)
        .redirectError(ProcessBuilder.Redirect.INHERIT)

      processBuilder.environment().put("VERILATOR_ROOT", root.toAbsolutePath.toString)

      val exitCode = processBuilder.start().waitFor()

      if (exitCode != 0) {
        throw new RuntimeException(s"Verilator preprocessor failed with exit code $exitCode")
      }
      SOCTVivadoHelper.transform(systemFile, paths.vivadoSystem, top)
    } else {
      println("Verilator binary not found or not executable. Using unprocessed Verilog file instead.")
      SOCTUtils.disableStdErr()
      SOCTVivadoHelper.transform(systemFile, paths.vivadoSystem, top)
      SOCTUtils.enableStdErr()
    }
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
