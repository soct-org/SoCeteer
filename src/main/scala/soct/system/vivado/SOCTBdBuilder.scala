package soct.system.vivado

import org.chipsalliance.cde.config.Parameters
import soct.system.vivado.SOCTBdVars.k
import soct.system.vivado.abstracts._
import soct.system.vivado.fpga.FPGA
import soct.system.vivado.misc.WantsPMODPins
import soct.{HasSOCTConfig, HasSOCTPaths, VivadoSOCTPaths}

import java.nio.file.Path
import scala.collection.mutable
import scala.util.matching.Regex


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


  private val portMappingsGens = mutable.ListBuffer.empty[() => Map[String, Seq[String]]]


  private val timingConstraintsGens = mutable.ListBuffer.empty[() => TCLCommands]

  /**
   * Add Vivado port mappings to the given lines
   *
   * @param portLines Lines of the Verilog file containing the port declarations
   * @return Modified lines with Vivado annotations added
   */
  def addPortMappings(portLines: Seq[String]): Seq[String] = {
    checkFinalized()

    def isAttributeLine(line: String): Boolean =
      line.trim.startsWith("(*")

    def portDeclRegex(portName: String): Regex = {
      // matches "... <portName>," or "... <portName>)" or "... <portName> ;"
      // allowing whitespace between tokens
      ("""(?i)\b""" + Regex.quote(portName) + """\b\s*(,|\)|;)\s*$""").r
    }

    val portMappings = portMappingsGens.flatMap(gen => gen())
    val lines = mutable.ListBuffer(portLines: _*)
    val targets = portMappings.flatMap { case (portName, attrStrings) =>
      val idxOpt = lines.zipWithIndex.collectFirst {
        case (line, idx)
          if !isAttributeLine(line) && portDeclRegex(portName).findFirstIn(line).nonEmpty =>
          idx
      }
      idxOpt match {
        case Some(idx) => Some((idx, portName, attrStrings))
        case None =>
          soct.log.warn(s"Could not find port line for port $portName to add Vivado annotation")
          None
      }
    }

    // apply bottom-to-top so indices don't shift under our feet
    targets.sortBy(-_._1).foreach { case (lineIdx, portName, attrStrings) =>
      attrStrings.reverse.foreach { a => lines.insert(lineIdx, "  " + a) }
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

    def rel(path: Path, base: Path = paths.vivadoSourceDir, baseVar: String = k.thisDir): String = {
      val relative = base.relativize(path).toString.replace("\\", "/")
      // Concat with $thisDir to get the full path in the TCL script
      s"$baseVar/$relative"
    }

    args.vars ++= Map(
      k.aggressive -> TclVar("Whether to aggressively overwrite existing Vivado projects and sources", if (aggressive) "1" else "0"),
      k.sources -> TclVar("The name of the fileset containing the design sources", "sources_1"),
      k.constraints -> TclVar("The name of the fileset containing the constraints files", "constrs_1"),
      k.synth -> TclVar("The name of the synthesis run to use for synthesis scripts", "synth_1"),
      k.impl -> TclVar("The name of the implementation run to use for implementation scripts", "impl_1"),
      k.projectName -> TclVar("The name of the Vivado project to create or open", s"${config.topModuleName}"),
      k.bdName -> TclVar("The name of the block design to create", s"${config.topModuleName}_bd"),
      k.xilinxPart -> TclVar("The Xilinx part number of the target FPGA", fpga.xilinxPart),
      k.partName -> TclVar("The Xilinx part name of the target FPGA", fpga.partName),
      k.nThreads -> TclVar("The number of threads to use for synthesis and implementation", (Runtime.getRuntime.availableProcessors().doubleValue * .5).toInt.toString),
      k.thisDir -> TclVar(
        "The directory this file is located in - all paths are relative to this directory",
        "[file dirname [file normalize [info script]]]",
        0
      ),
      // Paths - all relative to thisDir
      k.sourcesDir -> TclVar("The directory containing the design sources", rel(paths.verilogSrcDir)),
      k.vivadoProjectDir -> TclVar("The directory to use for the Vivado project", rel(paths.vivadoProjectDir)),
      k.defaultBdGenerator -> TclVar("The default TCL file to load the block design", rel(paths.defaultBdGenerator)),
      k.defaultTCGenerator -> TclVar("The default TCL file to add timing constraints - Must be added after the bd", rel(paths.defaultTCGenerator)),
      k.defaultSynthGenerator -> TclVar("The default TCL file to launch synthesis", rel(paths.defaultSynthGenerator)),
      k.defaultImplGenerator -> TclVar("The default TCL file to launch implementation", rel(paths.defaultImplGenerator)),
      k.xdcDir -> TclVar("The directory containing the generated XDC constraint files", rel(paths.xdcDir))
    )
  }

  private def checkInit(): Unit = {
    // check if vars is initialized
    if (args.vars.isEmpty) {
      throw XilinxDesignException("BDBuilder not initialized - call init(p: Parameters) before generating scripts")
    }
  }

  private def checkFinalized(): Unit = {
    if (!locked) {
      soct.log.warn("BDBuilder not finalized - calling finalizeDesign() automatically before generating scripts")
      finalizeDesign()
    }
  }


  private def openProject: String = {
    s"""# Check if the current project matches the expected project name
       |if {[llength [get_projects -quiet]] == 0} {
       |  error_exit 2000 "No Vivado project is opened. Please open a project before sourcing this script."
       |} else {
       |  set current_proj [get_property NAME [current_project]]
       |  if {$$current_proj ne ${k.projectName}} {
       |    warn_msg 2001 "This script is intended for project ${k.projectName}, but current project is $$current_proj. If you don't know what you are doing, please create a new project with https://github.com/soct-org/SoCeteer. Continuing may lead to unexpected results."
       |  }
       |}""".stripMargin
  }

  def addTimingConstraints(constraints: () => TCLCommands): Unit = {
    timingConstraintsGens += constraints
  }

  def addPortMapping(portMapping: () => Map[String, Seq[String]]): Unit = {
    portMappingsGens += portMapping
  }

  def emitCollaterals(outDir: Path): Unit = {
    checkFinalized()
    nodes.collect {
      case c: HasCollaterals => c.dumpCollaterals(outDir)
    }
  }

  /**
   * Finalize the block design by calling finalizeBd on all components and locking the design
   */
  def finalizeDesign(): Unit = {
    if (inFinalization || locked)
      throw XilinxDesignException("Cannot finalize design recursively or after it has already been finalized")

    inFinalization = true
    try {
      val comps = nodes.toSeq // Snapshot of components to avoid modification during iteration (e.g., new components added during finalization)
      // Tops first
      comps.collect { case m: ChiselModuleTop => m }.foreach(_.finalizeBd())
      // Others
      comps.collect { case f: Finalizable if !f.isInstanceOf[ChiselModuleTop] => f }.foreach(_.finalizeBd())
      locked = true
    } finally {
      inFinalization = false
    }
  }


  def generateBoardTcl(): String = {
    checkInit()
    checkFinalized()

    // Instantiations:
    lazy val instantiateCommands = nodes.collect {
      case inst: BdComp => inst.instTcl
    }.flatten.toSeq


    // Property settings:
    lazy val propertyCommands = nodes.collect {
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

    val connectTCL = this.outAdj.iterator
      .flatMap { case (from, tos) => BdPinPort.connect(from, tos) }
      .toSeq

    val addrConnects = nodes.collect {
      case c: HasBdAddr => c.assignAddrTcl
    }.flatten.toSeq

    val xips, xinlines = mutable.ListBuffer.empty[IsXilinx]
    val modules = mutable.ListBuffer.empty[IsModule]
    nodes.collect {
      case x: Xip => xips += x
      case x: XInlineHDL => xinlines += x
      case m: IsModule => modules += m
    }

    s"""${args.genTCLHeader(args.vars.toMap)}
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
       |$openProject
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
       |# Assign addresses
       |${addrConnects.sorted.mkString("\n")}
       |
       |# Regenerate layout once - usually improves readability significantly
       |regenerate_bd_layout
       |
       |validate_bd_design
       |
       |save_bd_design
       |
       |# Ensure BD products (HDL, synthesis netlists for IP, etc.) exist and are part of the project
       |set bd_obj [get_files $$bd_file]
       |generate_target all $$bd_obj
       |export_ip_user_files -of_objects $$bd_obj -no_script -sync -force
       |create_ip_run $$bd_obj
       |
       |set_property synth_checkpoint_mode None [get_files $$bd_file]
       |
       |
       |# Generate wrapper and set it as top so XDC can see BD ports
       |set source_fileset [get_filesets ${k.sources}]
       |
       |# Wrapper module name is "<bd_name>_wrapper"
       |set wrapper_name "${k.bdName}"
       |append wrapper_name "_wrapper"
       |
       |# Create/import wrapper if it doesn't exist yet
       |if { [get_files -quiet -of_objects $$source_fileset [list "*/$${wrapper_name}.v"]] == "" } {
       |  make_wrapper -files [get_files $$bd_file] -top -import
       |}
       |
       |# Set wrapper as top
       |set_property top $$wrapper_name $$source_fileset
       |update_compile_order -fileset ${k.sources}
       |""".stripMargin
  }

  /**
   * Generate a TCL script to launch synthesis for the block design. Assumes the project is already set up and the block design is already generated.
   *
   * @return TCL script as string
   */
  def generateSynthesisTcl(): String = {
    checkInit()
    checkFinalized()

    s"""${args.genTCLHeader(args.vars.toMap)}
       |
       |# Set max threads
       |set_param general.maxThreads ${k.nThreads}
       |
       |# Open the project
       |$openProject
       |
       |update_compile_order -fileset ${k.sources}
       |
       |# Reset previous synthesis runs to ensure a clean synthesis.
       |reset_run ${k.synth}
       |
       |# Launch synthesis with verbose output and the specified number of threads
       |launch_runs -jobs ${k.nThreads} ${k.synth} -verbose
       |
       |# Wait for synthesis to complete before proceeding
       |wait_on_run ${k.synth}
       |""".stripMargin
  }


  /**
   * Generate XDC constraint files for all components that emit constraints, grouped by the XDC name they specify.
   *
   * @param basePath The path to the directory where the generated XDC files should be placed.
   * @return A map from XDC file paths to their contents as strings.
   */
  def generateConstraintsTcls(basePath: Path): Map[Path, String] = {
    checkInit()
    checkFinalized()

    val xdcByName = nodes.collect {
      case w: EmitsConstraint => w
    }.groupBy(_.xdcName()(this))


    xdcByName.iterator.map { case (xdcName, emitters) =>
      val cmds = emitters.flatMap(_.xdcCommands()(this))
      val path = basePath.resolve(s"$xdcName.xdc")
      val content =
        s"""# Generated by SOCT
           |
           |${cmds.mkString("\n")}
           |""".stripMargin
      path -> content
    }.toMap
  }


  /**
   * Generate a TCL script to add timing constraints for the block design. Assumes the project is already set up and the block design is already generated.
   * @return TCL script as string
   */
  def generateTimingConstraintsTcl(): String = {
    checkInit()
    checkFinalized()

    val cmds = timingConstraintsGens.flatMap(gen => gen()).toSeq
    s"""# Generated by SOCT
       |${cmds.mkString("\n")}
       |""".stripMargin
  }


  /**
   * Generate Vivado init script
   *
   * @return Tcl script as string
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
       |# Create constraints fileset if not found.
       |if {[llength [get_filesets -quiet ${k.constraints}]] == 0} {
       |  create_fileset -constrset ${k.constraints}
       |}
       |
       |set source_fileset [get_filesets ${k.sources}]
       |# https://docs.amd.com/r/en-US/ug835-vivado-tcl-commands/add_files
       |add_files -fileset $$source_fileset ${k.sourcesDir}
       |
       |
       |set constr_fileset [get_filesets ${k.constraints}]
       |set xdc_files [glob -nocomplain -directory ${k.xdcDir} *.xdc]
       |if {[llength $$xdc_files] != 0} {
       |  add_files -fileset $$constr_fileset $$xdc_files
       |}
       |
       |# Note: Timing constraints must be last in constraints fileset
       |add_files -norecurse -fileset $$constr_fileset ${k.defaultTCGenerator}
       |
       |# Set file properties:
       |set file_obj [get_files -of_objects $$constr_fileset [list "*/*.xdc"]]
       |set_property -name "file_type" -value "XDC" -objects $$file_obj
       |set_property -name "used_in" -value "implementation" -objects $$file_obj
       |set_property -name "used_in_synthesis" -value "0" -objects $$file_obj
       |
       |set file_obj [get_files -of_objects $$constr_fileset [list "*/*.tcl"]]
       |set_property -name "file_type" -value "TCL" -objects $$file_obj
       |set_property -name "used_in" -value "implementation" -objects $$file_obj
       |set_property -name "used_in_synthesis" -value "0" -objects $$file_obj
       |
       |# Generate the block design
       |source ${k.defaultBdGenerator}
       |
       |# WARNING, not yet tested - use with caution:
       |#source ${k.defaultSynthGenerator}
       |
       |""".stripMargin // TODO: Add impl and not always launch synth and impl here
  }
}