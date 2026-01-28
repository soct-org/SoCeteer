package soct.system.vivado.abstracts

import java.nio.file.{Files, Path}


/**
 * Trait for components that have a Xilinx part name
 */
trait IsXilinx {
  /**
   * The part name of this Xilinx IP
   */
  def partName: String

  /**
   * The type of this Xilinx IP, e.g., "ip", "interface", "inline_hdl", etc.
   */
  val tpe: String
}

/**
 * Trait for Xilinx Inline HDL components
 */
trait XInlineHDL extends IsXilinx {
  override val tpe: String = "inline_hdl"
}

/**
 * Trait for Xilinx IP components
 */
trait Xip extends IsXilinx {
  override val tpe: String = "ip"
}

/**
 * Trait for Xilinx Interface components
 */
trait XIntf extends IsXilinx {
  override val tpe: String = "interface"
}


/**
 * Trait for custom module components
 */
trait IsModule extends HasCollaterals {
  /**
   * The reference name of this module - as defined in the collateral files
   */
  def reference: String
}


/**
 * Trait for components that have collateral files
 */
trait HasCollaterals {
  /**
   * Dump collateral files for this component to the specified output directory.
   * Inheriting classes should call super.dumpCollaterals with their own subdirectory name.
   *
   * @param outDir  The output directory path
   * @param dirName Optional subdirectory name for this component's collaterals - if None, no directory is created
   * @return Some(Path) to the collaterals directory if created, None otherwise
   */
  def dumpCollaterals(outDir: Path, dirName: Option[String] = None): Option[Path] = {
    if (dirName.isDefined) {
      val collateralsDir = outDir.resolve(dirName.get)
      if (!collateralsDir.toFile.exists()) {
        Files.createDirectories(collateralsDir)
      }
      return Some(collateralsDir)
    }
    None
  }
}

/**
 * Trait for components that have a friendly name
 */
trait HasFriendlyName {
  /**
   * A friendly name for this component, derived from the class name or overridden
   */
  def friendlyName: String = this.getClass.getSimpleName.replaceAll("\\$", "")
}