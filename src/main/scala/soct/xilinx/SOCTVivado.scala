package soct.xilinx

import soct.SOCTLauncher.SOCTConfig
import soct.{BoardSOCTPaths, SOCTPaths, SOCTUtils}
import soct.xilinx.fpga.{FPGA, ZCU104}
import java.nio.file.{Files, Path}
import scala.io.Source


/**
 * Exception thrown during evaluation of a Xilinx design
 */
case class XilinxDesignException(private val message: String = "",
                                 private val cause: Throwable = None.orNull)
  extends Exception(message, cause)


/**
 * Registry for known FPGA boards.
 */
object FPGARegistry {

  /**
   * Resolve a board by name
   * @param name Name of the board
   * @return Some(FPGA) if found, None otherwise
   */
  def resolve(name: String): Option[FPGA] = {
    val comp = name.toLowerCase

    if (comp == ZCU104.friendlyName.toLowerCase) {
      Some(ZCU104)
    } else {
      None
    }
  }

  /**
   * List of available boards
   */
  def availableBoards: Seq[String] = {
    Seq(
      ZCU104.friendlyName
    )
  }
}




object SOCTVivado {


  val DEFAULT_MEMORY_ADDR_64: BigInt = BigInt("80000000", 16)

  val DEFAULT_MEMORY_ADDR_32: BigInt = BigInt("40000000", 16)

  val DEFAULT_MMIO_ADDR = "0x60000000"

  def generate(boardPaths: BoardSOCTPaths, config: SOCTConfig): Unit = {

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
