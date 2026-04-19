package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts.{BdComp, BdIntfPin, BdPinIn, BdPinOut, ConnectOps, DrivenByNet, IsModule}

import java.nio.file.{Files, Path}

case class MemResetControl()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp with IsModule with ConnectOps {
  override def reference: String = "mem_reset_control" // The module name inside the collateral files - DO NOT CHANGE

  object CLOCK extends BdPinIn("clock", MemResetControl.this)

  object CLOCK_OK extends BdPinIn("clock_ok", MemResetControl.this)

  object MMCM_LOCKED extends BdPinIn("mmcm_locked", MemResetControl.this)

  object CALIB_COMPLETE extends BdPinIn("calib_complete", MemResetControl.this)

  object UI_CLK_SYNC_RST extends BdPinIn("ui_clk_sync_rst", MemResetControl.this)

  object SYS_RESET extends BdPinIn("sys_reset", MemResetControl.this)

  object UI_CLK extends BdPinIn("ui_clk", MemResetControl.this)

  // Outputs:
  object MEM_RESET extends BdPinOut("mem_reset", MemResetControl.this)

  object ARESETN extends BdPinOut("aresetn", MemResetControl.this)

  object MEM_OK extends BdPinOut("mem_ok", MemResetControl.this)


  override def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, dirName = Some(friendlyName)).get
    val dir = "/reset_control/"
    val file = "mem_reset_control.v"
    val contentOpt = soct.getResource(dir + file)
    if (contentOpt.isEmpty) {
      throw XilinxDesignException(s"Could not find MemResetControl collateral file: $file")
    }
    val outFile = dest.resolve(file).toFile
    Files.write(outFile.toPath, contentOpt.get.getBytes)
    Some(dest)
  }
}
