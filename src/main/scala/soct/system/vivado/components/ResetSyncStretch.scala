package soct.system.vivado.components

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.{SOCTBdBuilder, XilinxDesignException}
import soct.system.vivado.abstracts.{BdComp, BdIntfPin, BdPinIn, BdPinOut, BdPinPort, ConnectOps, DrivenByNet, IsModule, Reset, ToSinkConnect}

import java.nio.file.{Files, Path}

case class ResetSyncStretch()(implicit bd: SOCTBdBuilder, p: Parameters) extends BdComp with IsModule with ConnectOps {
  override def reference: String = "reset_sync_stretch" // The module name inside the collateral files - DO NOT CHANGE

  object CLOCK extends BdPinIn("clock", ResetSyncStretch.this)

  object RESET_IN extends BdPinIn("dinp", ResetSyncStretch.this)

  object ARESETN extends BdPinOut("aresetn", ResetSyncStretch.this) with Reset

  object RESET extends BdPinOut("reset", ResetSyncStretch.this) with Reset

  override def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    val dest = super.dumpCollaterals(outDir, dirName = Some(friendlyName)).get
    val dir = "/reset_control/"
    val file = "reset_sync_stretch.vhd"
    val contentOpt = soct.getResource(dir + file)
    if (contentOpt.isEmpty) {
      throw XilinxDesignException(s"Could not find ResetSyncStretch collateral file: $file")
    }
    val outFile = dest.resolve(file).toFile
    Files.write(outFile.toPath, contentOpt.get.getBytes)
    Some(dest)
  }
}

object ResetSyncStretch {

  // Allow: ResetSyncStretch.RESET and ResetSyncStretch.ARESETN to connect to chisel3.Resets, as they are both resets and the module will handle the inversion
  implicit val syncResetToChiselClock: ToSinkConnect[BdPinOut with Reset, chisel3.Reset] =
    (source: BdPinOut with Reset, sink: chisel3.Reset, bd: SOCTBdBuilder) =>
      bd.addEdge(source, BdPinPort.portToBdPin(sink)(bd))
}