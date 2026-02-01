package soct.system.vivado

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdVars.k
import soct.{HasSOCTConfig, HasSOCTPaths, VivadoSOCTPaths}
import soct.system.vivado.fpga.FPGA
import soct.system.vivado.abstracts._

import java.nio.file.Path
import scala.collection.{View, mutable}


class SOCTBd {

  var locked = false // To prevent further modifications after finalization

  var inFinalization = false // To prevent recursive finalization

  // Set of all components in the block design
  val components = mutable.Set.empty[BdBaseComp]

  val connects: mutable.Map[BdPinPort, Seq[BdPinPort]] = mutable.Map.empty

  /**
   * Count instances of a given BdComp type, excluding the provided instance.
   *
   * @param inst The instance to exclude from the count
   * @tparam T The type of BdComp to count
   * @return The number of instances of type T, excluding the provided instance
   */
  def countInstancesOf[T <: BdBaseComp](inst: T): Int = {
    val cls = inst.getClass
    components.count(c => cls.isInstance(c) && c != inst)
  }

  /**
   * Remove a connection from the block design
   *
   * @param from  The source port to remove connections from
   * @param toOpt Optional sink port to remove connection to, if None all connections from 'from' are removed
   */
  def removeConnection(from: BdPinPort, toOpt: Option[BdPinPort] = None): Unit = {
    if (locked) {
      throw XilinxDesignException("Cannot remove connections after finalization")
    }
    toOpt match {
      case Some(to) =>
        connects.get(from) match {
          case Some(sinks) =>
            connects.update(from, sinks.filterNot(_ == to))
          case None => // No connections to remove
        }
      case None =>
        connects.remove(from)
    }
  }

  /**
   * Get all connections that satisfy a given property
   *
   * @param prop The property function to filter connections
   * @return A map of connections that satisfy the property
   */
  def connectsWithProperty(prop: (BdPinPort, Seq[BdPinPort]) => Boolean): Map[BdPinPort, Seq[BdPinPort]] = {
    connects.filter { case (from, sinks) => prop(from, sinks) }.toMap
  }

  /**
   * Get the number of sinks connected to a given source port
   *
   * @param from The source port
   * @return The number of sinks connected to the source port
   */
  def numSinks(from: BdPinPort): Int = {
    connects.get(from) match {
      case Some(sinks) => sinks.size
      case None => 0
    }
  }

  /**
   * Connect a source port to a sink port
   *
   * @param from The source port
   * @param to   The sink port
   */
  def connect(from: BdPinPort, to: BdPinPort): Unit = {
    if (locked) {
      throw XilinxDesignException("Cannot add connections after finalization")
    }
    val existing = connects.getOrElse(from, Seq.empty)
    connects.update(from, existing :+ to)
  }

  /**
   * Get all connectors (sources and sinks) for a given port
   *
   * @param port The port to get connectors for
   * @return A view of all connectors (sources and sinks) for the given port
   */
  def getConnectors(port: BdPinPort): View[BdPinPort] = {
    connects.get(port) match {
      case Some(sinks) =>
        sinks.view
      case None =>
        connects.collect {
          case (src, sinks) if sinks.contains(port) => src
        }.view
    }
  }

  /**
   * Get a single connector (source or sink) for a given port, throwing an error if not exactly one is found
   *
   * @param port     The port to get the connector for
   * @param prop     An optional property function to filter connectors. Formatted as BdPinPort => Boolean
   * @param errorMsg An optional custom error message if the number of connectors that satisfy the property is not exactly one
   * @return The single connector for the given port
   */
  def getConnector(port: BdPinPort, prop: BdPinPort => Boolean = _ => true, errorMsg: Option[String] = None): BdPinPort = {
    val errorMsgFinal = errorMsg.getOrElse(s"Expected exactly one connector for port $port that satisfies the given property, but found a different number.")
    val connectors = getConnectors(port).toSeq
    connectors filter prop match {
      case Seq(single) => single
      case _ => throw XilinxDesignException(errorMsgFinal)
    }
  }

  /**
   * Get all sink ports connected to a given source port
   *
   * @param source The source port
   * @return A sequence of sink ports connected to the source port
   */
  def getSinks(source: BdPinPort): Seq[BdPinPort] = {
    connects.getOrElse(source, Seq.empty)
  }

  /**
   * Get the source port connected to a given sink port
   *
   * @param sink The sink port
   * @return An optional source port connected to the sink port
   */
  def getSource(sink: BdPinPort): Option[BdPinPort] = {
    // throw warning if multiple sources found - should not happen in well-formed designs
    val sources = connects.collect {
      case (src, sinks) if sinks.contains(sink) => src
    }.toSeq
    if (sources.size > 1) {
      soct.log.warn(s"Multiple sources found for sink $sink: ${sources.mkString(", ")}")
    }
    sources.headOption
  }


  /**
   * Add a component to the block design
   *
   * @param c The component to add
   * @tparam T The type of the component
   */
  def addComponent[T <: BdBaseComp](c: T): Unit = {
    if (locked) {
      throw XilinxDesignException("Cannot add components after finalization")
    }

    if (!components.contains(c)) {
      components += c
    }
  }
}


class SOCTBdBuilder extends SOCTBd {

  /**
   * TCL arguments for the block design
   */
  val args = new SOCTBdVars

  /**
   * Get the top-level instance representing the design in the block design
   */
  var topInstance: () => ChiselModuleTop = () => {
    throw XilinxDesignException("Please call init before accessing topInstance")
  }

  /**
   * Get the target FPGA
   */
  var fpgaInstance: () => FPGA = () => {
    throw XilinxDesignException("Please call init before accessing fpgaInstance")
  }

  /**
   * Add Vivado port mappings to the given lines
   *
   * @param portLines    Lines of the Verilog file containing the port declarations
   * @param portMappings Map of port names to Vivado attribute strings
   * @return Modified lines with Vivado annotations added
   */
  def addPortMappings(portLines: Seq[String], portMappings: Map[String, Seq[String]]): Seq[String] = {
    require(locked, "Please call finalizeDesign() before adding port mappings")
    val lines = mutable.Buffer.from(portLines)
    portMappings.foreach { case (portName, attrStrings) =>
      val lineIdxOpt = lines.zipWithIndex.find { case (line, _) =>
        line.toLowerCase.contains(portName.toLowerCase) // FIXME: This will fail for ports like "reset" which match other ports like "reset_n"
      }.map { case (_, idx) => idx }
      if (lineIdxOpt.isEmpty) {
        soct.log.warn(s"Could not find port line for port $portName to add Vivado annotation")
      } else {
        val lineIdx = lineIdxOpt.get
        // Insert the annotations before the line - see https://docs.amd.com/r/en-US/ug994-vivado-ip-subsystems/General-Usage
        attrStrings.reverse.foreach { attrString =>
          lines.insert(lineIdx, "  " + attrString)
        }
      }
    }
    lines.toSeq
  }

  /**
   * Initialize the BDBuilder with parameters and top-level instance.
   *
   * @param p       Parameters
   * @param topInst Top-level instantiable block design component
   * @param fpga    Target FPGA
   */
  def init(p: Parameters, topInst: ChiselModuleTop, fpga: FPGA): Unit = {
    val paths = p(HasSOCTPaths).asInstanceOf[VivadoSOCTPaths]
    val config = p(HasSOCTConfig)
    val aggressive = config.args.overrideVivadoProject
    topInstance = () => topInst
    fpgaInstance = () => fpga

    args.vars ++= Map(
      k.aggressive -> TclVar("Whether to aggressively overwrite existing Vivado projects and sources", if (aggressive) "1" else "0"),
      k.sources -> TclVar("The name of the fileset containing the design sources", "sources_1"),
      k.projectName -> TclVar("The name of the Vivado project to create or open", s"${config.topModuleName}"),
      k.bdName -> TclVar("The name of the block design to create", s"${config.topModuleName}_bd"),
      k.xilinxPart -> TclVar("The Xilinx part number of the target FPGA", fpga.xilinxPart),
      k.partName -> TclVar("The Xilinx part name of the target FPGA", fpga.partName),
      k.sourcesDir -> TclVar("The directory containing the design sources", paths.systemDir.toString),
      k.vivadoProjectDir -> TclVar("The directory to use for the Vivado project", paths.vivadoProjectDir.toString),
      k.bdLoadFile -> TclVar("The TCL file to load the block design", paths.bdLoadFile.toString)
    )
  }

  private def checkInit(): Unit = {
    // check if vars is initialized
    if (args.vars.isEmpty) {
      throw XilinxDesignException("BDBuilder not initialized - call init(p: Parameters) before generating scripts")
    }
  }

  def portModifications(): Map[String, Seq[String]] = {
    components.collect { case xIntf: MapsToPorts => xIntf.portMapping }
      .flatten
      .groupBy(_._1)
      .view.mapValues(_.toSeq.flatMap(_._2))
      .toMap
  }

  def emitCollaterals(outDir: Path): Unit = {
    components.collect {
      case c: HasCollaterals => c.dumpCollaterals(outDir)
    }
  }

  /**
   * Finalize the block design by calling finalizeBd on all components and locking the design
   */
  def finalizeDesign(): Unit = {
    if (!inFinalization && !locked) {
      inFinalization = true
      // First finalize the Chisel modules, as they have not had the chance to evaluate their IO yet (only possible after elaboration)
      components.collect { case m: ChiselModuleTop => m.finalizeBd() }
      // Then finalize all other components
      components.collect { case f: Finalizable => f.finalizeBd() }
      locked = true
      inFinalization = false
    }
  }

  def generateBoardTcl(): String = {
    checkInit()
    require(locked, "Please call finalizeDesign() before generating the board TCL script")

    // Instantiations:
    lazy val instantiateCommands = components.collect {
      case inst: BdComp => inst.instTcl
    }.flatten.toSeq


    // Property settings:
    lazy val propertyCommands = components.collect {
      case c: BdComp if c.defaultProperties.nonEmpty =>
        def tclLiteral(v: String): String =
          if (v.startsWith("$") || v.startsWith("[")) v
          else s"{${v.replace("}", "\\}")}}"

        s"""
           |# Set default properties for component ${c.friendlyName}
           |set_property -dict [list \\
           |${c.defaultProperties.map { case (k, v) => s"  $k ${tclLiteral(v)}" }.mkString(" \\\n") + " \\"}
           |] $$${c.instanceName}
           |""".stripMargin
    }.toSeq

    val connectTCL = connects.flatMap {
      case (from, tos) => BdPinPort.connect(from, tos)
    }.toSeq

    // Keys for TCL variables used in the script
    val bdKeys = Seq(k.bdName, k.projectName, k.sources)

    // generate header with variable descriptions, using a subset of vals in vars
    // Add the BD-specific variables which are defined statically in SOCTBdBuilder
    val bdVars: Map[String, TclVar] =
      (args.vars.filter { case (v, _) => bdKeys.contains(v) } ++ args.bdVars).toMap

    val xips, xinlines = mutable.ListBuffer.empty[IsXilinx]
    val modules = mutable.ListBuffer.empty[IsModule]
    components.collect {
      case x: Xip => xips += x
      case x: XInlineHDL => xinlines += x
      case m: IsModule => modules += m
    }

    s"""${args.genTCLHeader(bdVars)}
       |
       |######## Helper procedures ########
       |proc error_exit {id msg} {
       |  common::send_gid_msg -ssname BD::TCL -id $$id -severity "ERROR" $$msg
       |  error $$msg
       |}
       |
       |proc warn_msg {id msg} {
       |  common::send_gid_msg -ssname BD::TCL -id $$id -severity "WARNING" $$msg
       |}
       |
       |proc info_msg {id msg} {
       |  common::send_gid_msg -ssname BD::TCL -id $$id -severity "INFO" $$msg
       |}
       |
       |proc check_required {items check_cmd label err_code ok_code err_hint} {
       |  set missing {}
       |  foreach item $$items {
       |    if {[$$check_cmd $$item]} {
       |      lappend missing $$item
       |    }
       |  }
       |  if {[llength $$missing]} {
       |    error_exit $$err_code \\
       |      "The following required $$label are missing:\n  [join $$missing "\n  "]\n$$err_hint"
       |  }
       |  info_msg $$ok_code "All required $$label are available."
       |}
       |
       |proc has_module {m} { expr {[llength [can_resolve_reference $$m]] == 0} }
       |proc has_ip     {v} { expr {[llength [get_ipdefs -all $$v]] == 0} }
       |
       |######## Project & Board design (bd) validation ########
       |
       |# Check if the current project matches the expected project name
       |if {[llength [get_projects -quiet]] == 0} {
       |  error_exit 2000 "No Vivado project is opened. Please open a project before sourcing this script."
       |} else {
       |  set current_proj [get_property NAME [current_project]]
       |  if {$$current_proj ne ${k.projectName}} {
       |    warn_msg 2001 "This script is intended for project ${k.projectName}, but current project is $$current_proj. If you don't know what you are doing, please create a new project with https://github.com/soct-org/SoCeteer. Continuing may lead to unexpected results."
       |  }
       |}
       |
       |# Save current BD context (best effort)
       |set cur_design [current_bd_design -quiet]
       |set cur_inst   [current_bd_instance -quiet]
       |
       |# Construct BD file path (Vivado-standard layout)
       |set bd_file [file join \\
       |  [get_property DIRECTORY [current_project]] \\
       |  ${k.projectName}.srcs \\
       |  ${k.sources} \\
       |  bd \\
       |  ${k.bdName} \\
       |  ${k.bdName}.bd]
       |
       |# Open existing BD or create a new one
       |if {[file exists $$bd_file]} {
       |  # Ensure the BD is part of the current project before opening
       |  if {[llength [get_files -quiet $$bd_file]] == 0} {
       |    add_files -norecurse $$bd_file
       |  }
       |  open_bd_design $$bd_file
       |} else {
       |  create_bd_design ${k.bdName}
       |}
       |
       |current_bd_design ${k.bdName}
       |update_compile_order -fileset ${k.sources}
       |
       |# Check whether the BD is empty
       |set n_cells  [llength [get_bd_cells -quiet *]]
       |set n_ports  [llength [get_bd_ports -quiet *]]
       |set n_iports [llength [get_bd_intf_ports -quiet *]]
       |set n_nets   [llength [get_bd_nets -quiet *]]
       |
       |if {($$n_cells + $$n_ports + $$n_iports + $$n_nets) != 0} {
       |  # Restore previous context (best effort)
       |  if {[llength $$cur_design] != 0 && $$cur_design ne ${k.bdName}} {
       |    open_bd_design $$cur_design
       |    if {[llength $$cur_inst] != 0} {
       |      catch { current_bd_instance $$cur_inst }
       |    }
       |  }
       |  error_exit 2004 "Block design ${k.bdName} already exists and is not empty. Aborting to avoid overwriting existing design."
       |}
       |
       |info_msg 2005 "Created / opened block design ${k.bdName} successfully."
       |
       |
       |######## Check required interfaces, IPs and Modules ########
       |
       |set list_check_ips [list \\
       |${xips.map(_.partName).map(ip => s"  \"$ip\"").mkString(" \\\n")}
       |]
       |check_required $$list_check_ips has_ip "Xilinx IPs" 2006 2007 \\
       |    "Please install them via the Vivado IP Catalog before sourcing this script."
       |
       |set list_check_modules [list \\
       |${modules.map(_.reference).map(m => s"  \"$m\"").mkString(" \\\n")}
       |]
       |check_required $$list_check_modules has_module "Modules" 2008 2009 \\
       |    "Please ensure their collateral files are available in the sources directory before sourcing this script."
       |
       |
       |######## Instantiate components and connect them ########
       |
       |# Instantiate ports and components
       |${instantiateCommands.sorted.mkString("\n")}
       |
       |# Set default properties
       |${propertyCommands.sorted.mkString("\n")}
       |
       |# Connect components
       |${connectTCL.sorted.mkString("\n")}
       |
       |""".stripMargin
  }


  /**
   * Generate Vivado init script
   *
   * @return TCl script as string
   */
  def generateInitScript(): String = {
    checkInit()

    s"""${args.genTCLHeader(args.vars.toMap)}
       |
       |# If there is no project opened, create a project for the design
       |if {[llength [get_projects -quiet]] == 0} {
       |  # https://docs.amd.com/r/en-US/ug835-vivado-tcl-commands/create_project
       |  if {${k.aggressive} == 1} {
       |    create_project -force -part ${k.xilinxPart} ${k.projectName} ${k.vivadoProjectDir}
       |  } else {
       |    create_project -part ${k.xilinxPart} ${k.projectName} ${k.vivadoProjectDir}
       |  }
       |  set_property board_part ${k.partName} [current_project]
       |}
       |
       |# Create fileset if not found.
       |if {[llength [get_filesets -quiet ${k.sources}]] == 0} {
       |  create_fileset -srcset ${k.sources}
       |}
       |
       |set source_fileset [get_filesets ${k.sources}]
       |# https://docs.amd.com/r/en-US/ug835-vivado-tcl-commands/add_files
       |add_files -fileset $$source_fileset ${k.sourcesDir}
       |
       |# TODO add other filesets (constraints, etc.)
       |
       |source ${k.bdLoadFile}
       |
       |""".stripMargin
  }
}