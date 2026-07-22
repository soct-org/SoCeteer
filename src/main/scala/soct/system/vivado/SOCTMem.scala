package soct.system.vivado

import org.chipsalliance.cde.config.Config
import soct.SOCTBytes.{ByteUnitOpsInt, Bytes}
import soct.SOCTUtils.MAX_MEM_SIZE_32_BIT
import soct.system.vivado.fpga.{DDR4PortParams, PartRegistry}
import soct.{SOCTArgs, WithMultiMemLayout, WithSingleMemLayout, log}

import java.nio.file.{Files, Path}



/**
 * Launcher-side resolution of the external (DRAM) memory layout for the Vivado flow - the
 * config-time counterpart of [[SOCTMemGen]] below, which handles the on-chip SRAMs at
 * Verilog-emission time.
 *
 * [[genMemConfig]] runs before elaboration (see `SOCTLauncher.generateVivadoDesign`) and turns
 * the board's DDR4 ports plus the `--ext-mem-part` arguments into the memory-layout config
 * fragment the design is elaborated with:
 *
 *   - every external port is resolved to a concrete memory part - the board preset, or the
 *     requested DIMM, which (because Vivado's board flow locks the controller to the preset)
 *     switches the port to the custom, non-board-flow DDR4 interface fed from the board
 *     definition's pin map and clock timing
 *   - capacities are derived from the part names via [[soct.system.vivado.fpga.PartRegistry]]
 *     and drive `ExtMem`, the device tree and the address decode; unknown parts fail loudly
 *   - 32-bit designs get the summed capacity capped to the 32-bit address space
 *     ([[limitToMaxCap]], largest ports first)
 *   - the single- or multi-channel layout fragment is chosen depending on whether the top
 *     module supports multiple memory channels (see [[SupportsMultiMem]])
 *
 * Everything here concerns memory *outside* the chip; nothing is emitted - the returned
 * fragment only parameterizes elaboration.
 */
object SOCTMem {

  /**
   * Cap the summed capacity of the given memory ports to `maxSize`: ports are kept
   * largest-first, the last fitting port is truncated, and the rest are dropped.
   *
   * @param mems    the memory ports (capacities must be set)
   * @param maxSize the maximum total capacity; 0 drops all ports, negative values are invalid
   * @return the capped ports
   * @throws soct.system.vivado.VivadoDesignException if `maxSize` is negative or a port has no capacity
   */
  def limitToMaxCap(mems: Seq[DDR4PortParams], maxSize: Bytes): Seq[DDR4PortParams] = {
    if (maxSize < 0.B) {
      throw VivadoDesignException(s"maxSize must be non-negative, got $maxSize")
    }
    if (maxSize == 0.B) {
      return Seq.empty
    }

    var remaining = maxSize

    mems
      .sortBy(_.getCap.value)(Ordering.Long.reverse)
      .iterator
      .flatMap { mem =>
        if (remaining <= 0.B) {
          None
        } else {
          val capped = if (mem.getCap <= remaining) mem.getCap else remaining
          val updated = mem.withCap(capped)
          remaining = remaining - capped
          Some(updated)
        }
      }
      .toSeq
  }


  /**
   * Build the memory-layout config fragment for the selected board: resolves the memory part of
   * every external DDR4 port (board preset or `--ext-mem-part`, switching to the custom
   * interface when they differ), derives capacities, applies the 32-bit address-space cap, and
   * chooses the single- or multi-channel layout.
   *
   * @param args               the launcher arguments (board, requested memory parts, xlen)
   * @param topSupportMultiMem whether the top module supports multiple memory channels
   * @return the layout fragment, or None if the board has no memory ports
   * @throws soct.system.vivado.VivadoDesignException if the requested parts do not match the
   *                                                  board's ports, a part's capacity cannot be
   *                                                  derived, or the board lacks the data for a
   *                                                  required custom interface
   */
  def genMemConfig(args: SOCTArgs, topSupportMultiMem: Boolean): Option[Config] = {
    val board = args.board.get
    val _memCaps: Seq[DDR4PortParams] = {
      // Requires memory inserted by the user - we don't know its capacity
      // Note that we don't support internal and external memory at the same time
      val extMem = if (board.extDDR4Ports.nonEmpty) {
        val ports = board.extDDR4Ports
        val nPorts = ports.length

        if (args.extMemParts.nonEmpty && args.extMemParts.length != nPorts) {
          throw VivadoDesignException(s"Number of memory parts provided via --ext-mem-part (${args.extMemParts.length}) does not match the number of external DDR4 ports on the board ${board.friendlyName}: ($nPorts). Please provide a part for each external DDR4 port.")
        }

        // The DDR4 board flow locks the controller to the board-interface preset DIMM
        // (C0.DDR4_MemoryPart is a disabled parameter; set_property on it is ignored). The
        // capacity (ExtMem, DTB, address aperture) is derived from the selected part. Selecting
        // a DIMM that differs from the preset switches the port to the custom (non board-flow)
        // interface, which requires the board definition to carry the pin map and clock timing.
        ports.zipWithIndex.map { case (port, i) =>
          val requestedOpt = args.extMemParts.lift(i)
          val presetOpt = port.defaultMemoryPart

          val part = (requestedOpt, presetOpt) match {
            case (Some(requested), Some(preset)) =>
              if (PartRegistry.vivadoPartName(requested) != PartRegistry.vivadoPartName(preset)) {
                // Vivado's board flow locks C0.DDR4_MemoryPart to the board preset, so a
                // different DIMM needs the custom (non board-flow) interface: full IP config
                // plus pin LOCs from the board definition's ddr4PinMap.
                if (!port.supportsCustomInterface) {
                  throw VivadoDesignException(s"Port ${port.portName}: the board interface enforces memory part '$preset' and the board definition of ${board.friendlyName} does not provide custom-interface data (ddr4PinMap/ddr4TimePeriodPs/ddr4InputClockPeriodPs), so '$requested' cannot be used. Add the custom-interface data to the board definition (see DDR4PortParams).")
                }
                soct.log.info(s"Port ${port.portName}: memory part '$requested' differs from the board preset '$preset' - using a custom (non board-flow) DDR4 interface with pin constraints from the board definition.")
                port.withCustomInterface()
              }
              requestedOpt.get
            case (Some(requested), None) =>
              throw VivadoDesignException(s"Port ${port.portName}: --ext-mem-part '$requested' was provided, but the board definition of ${board.friendlyName} does not declare the part enforced by its DDR4 board interface (defaultMemoryPart), so preset and request cannot be compared. Declare defaultMemoryPart for the port.")
            case (None, Some(preset)) =>
              soct.log.info(s"Port ${port.portName}: using the board-preset memory part '$preset'.")
              preset
            case (None, None) =>
              throw VivadoDesignException(s"Port ${port.portName}: the board definition of ${board.friendlyName} does not declare the memory part enforced by its DDR4 board interface. Declare defaultMemoryPart for the port (see DDR4PortParams).")
          }

          val cap = PartRegistry.capacityOf(part).getOrElse(
            throw VivadoDesignException(s"The capacity of memory part '$part' could not be derived from its name. Add it to PartRegistry (known parts: ${PartRegistry.knownParts.mkString(", ")}).")
          )
          soct.log.info(s"Port ${port.portName}: capacity $cap derived from memory part '$part'.")
          port.withCap(cap).withMemoryPart(part)
        }
      } else {
        if (args.extMemParts.nonEmpty) {
          throw VivadoDesignException(s"--ext-mem-part was provided, but the board ${board.friendlyName} has no external DDR4 ports. Memory parts can only be selected for user-insertable (external) DIMMs.")
        }
        Seq.empty
      }

      extMem ++ board.intDDR4Ports
    }


    val memCaps = if (args.xlen == 32) {
      val oldCaps = _memCaps.map(_.getCap).mkString(", ")
      val limited = limitToMaxCap(_memCaps, MAX_MEM_SIZE_32_BIT)
      val newCaps = limited.map(_.getCap).mkString(", ")
      soct.log.info(s"Limiting memory capacities to a maximum of $MAX_MEM_SIZE_32_BIT for 32-bit address space. Original capacities: ${oldCaps}, limited capacities: $newCaps")
      limited
    } else {
      _memCaps
    }

    if (memCaps.nonEmpty) {
      if (!topSupportMultiMem) {
        val chosen = memCaps.head
        if (memCaps.length > 1) {
          soct.log.warn(s"Top module does not support multiple memory channels, but ${memCaps.length} memory channels were provided. Using ${chosen.portName} with capacity ${chosen.getCap}")
        }
        Some(new WithSingleMemLayout(chosen))
      } else {
        Some(new WithMultiMemLayout(memCaps))
      }
    } else {
      None
    }
  }
}





/**
 * Generates Vivado-BRAM-inferrable Verilog implementations for the SRAMs that firtool extracts
 * with `--repl-seq-mem` (Vivado targets only - see the chisel Transpiler's `emitVerilog`).
 *
 * Why this exists: firtool's inline memory lowering emits a byte-masked memory as ONE wide
 * array written by `width / mask_gran` conditional part-select statements. Vivado's RAM
 * extraction merges those write lanes only up to a small lane count: the 4-lane I-cache data
 * array is extracted to BRAM, while the structurally identical 32-lane D-cache data array is
 * silently flattened to registers - 131k FFs + 52k LUTs per Rocket core, the dominant cost of
 * the whole design (found 2026-07-22 when the two-core video design became unroutable).
 *
 * Instead of fighting the extractor, each memory is re-emitted in the shape the old Scala
 * FIRRTL compiler and Chipyard's behavioral `vlsi_mem_gen` output use, which Vivado provably
 * infers: ONE INDEPENDENT ARRAY PER MASK LANE, each with a single unmasked write and a
 * registered (synchronous) read. Read latency stays 1 cycle, so the swap is transparent to
 * the design. Where the original read data was undefined (reads with the port in write mode),
 * the generated memory holds the previous read value - a defined superset of the FIRRTL
 * behavior. Read-write collision behavior is undefined, as in FIRRTL.
 *
 * The conf format is the ReplSeqMem format shared by firtool and the SFC, one memory per line:
 * `name <module> depth <d> width <w> ports <kind>[,<kind>...] [mask_gran <g>]`
 * with port kinds `rw`, `mrw`, `read`, `write`, `mwrite`. Port names follow firtool's extern
 * memory convention (`RW0_*`, `R0_*`, `W0_*`), so the generated modules satisfy the extern
 * declarations the emitted design references.
 */
object SOCTMemGen {

  /** One parsed memory description from the ReplSeqMem conf file. */
  final case class MemDesc(name: String, depth: BigInt, width: Int, ports: Seq[String], maskGran: Option[Int]) {
    /** Bits per independently written lane: the mask granularity, or the full width when unmasked. */
    def gran: Int = maskGran.getOrElse(width)

    /** Number of independent per-lane arrays the implementation is split into. */
    def lanes: Int = width / gran
  }

  private val knownKinds = Set("rw", "mrw", "read", "write", "mwrite")
  private val writingKinds = Set("rw", "mrw", "write", "mwrite")

  /**
   * Generate one Verilog implementation per memory in the conf file into `outDir`.
   *
   * @param confFile the ReplSeqMem conf file written by firtool
   * @param outDir   the Verilog sources directory the Vivado project reads (each memory becomes
   *                 `<name>.sv` there)
   * @throws VivadoDesignException if the conf file is missing or a memory is malformed/unsupported
   */
  def generate(confFile: Path, outDir: Path): Unit = {
    if (!Files.exists(confFile)) {
      throw VivadoDesignException(
        s"ReplSeqMem conf file $confFile was not produced by firtool - cannot generate the memory implementations the design references.")
    }
    val mems = parse(Files.readString(confFile))
    if (mems.isEmpty) {
      log.info(s"ReplSeqMem conf $confFile lists no memories - nothing to generate")
      return
    }
    mems.foreach { m =>
      Files.writeString(outDir.resolve(s"${m.name}.sv"), emit(m))
    }
    log.info(s"Generated ${mems.size} BRAM-inferrable memory implementations from ${confFile.getFileName}")
  }

  /**
   * Parse a ReplSeqMem conf into memory descriptions and validate each against what [[emit]]
   * supports.
   *
   * @throws VivadoDesignException on malformed lines, unknown port kinds, indivisible mask
   *                               granularity, or more than one writing port (BRAM inference
   *                               would need a true-dual-port template no memory here uses)
   */
  def parse(conf: String): Seq[MemDesc] = {
    conf.linesIterator.map(_.trim).filter(_.nonEmpty).map { line =>
      val tokens = line.split("\\s+")
      if (tokens.length % 2 != 0) {
        throw VivadoDesignException(s"Malformed ReplSeqMem conf line (odd token count): '$line'")
      }
      val kv = tokens.grouped(2).map(p => p(0) -> p(1)).toMap
      def req(key: String): String = kv.getOrElse(key,
        throw VivadoDesignException(s"Malformed ReplSeqMem conf line (missing '$key'): '$line'"))

      val m = MemDesc(req("name"), BigInt(req("depth")), req("width").toInt,
        req("ports").split(",").toSeq, kv.get("mask_gran").map(_.toInt))

      val unknown = m.ports.filterNot(knownKinds)
      if (unknown.nonEmpty) {
        throw VivadoDesignException(s"Memory ${m.name}: unsupported port kind(s) ${unknown.mkString(", ")} (known: ${knownKinds.mkString(", ")})")
      }
      if (m.ports.count(writingKinds) > 1) {
        throw VivadoDesignException(s"Memory ${m.name}: ${m.ports.count(writingKinds)} writing ports - only one is supported (no true-dual-port template)")
      }
      if (m.width % m.gran != 0) {
        throw VivadoDesignException(s"Memory ${m.name}: width ${m.width} is not divisible by mask granularity ${m.gran}")
      }
      m
    }.toSeq
  }

  /** Address port width: enough bits to index `depth` entries, at least one. */
  private def addrBits(depth: BigInt): Int = ((depth - 1).bitLength).max(1)

  /**
   * Emit the Verilog implementation of one memory: per-lane arrays plus, per port, one
   * always-block per lane in the canonical single-write/registered-read form Vivado infers.
   */
  def emit(m: MemDesc): String = {
    val a = addrBits(m.depth) - 1
    val w = m.width - 1
    val lanes = 0 until m.lanes

    // Assign each port its firtool-convention prefix (R0.., W0.., RW0.. per kind family).
    var (r, wr, rw) = (0, 0, 0)
    val prefixed: Seq[(String, String)] = m.ports.map {
      case k @ ("read")            => val p = s"R$r";  r += 1;  (k, p)
      case k @ ("write" | "mwrite") => val p = s"W$wr"; wr += 1; (k, p)
      case k @ ("rw" | "mrw")       => val p = s"RW$rw"; rw += 1; (k, p)
    }

    val portDecls = prefixed.flatMap { case (kind, p) =>
      val common = Seq(s"input  [$a:0] ${p}_addr", s"input        ${p}_en", s"input        ${p}_clk")
      kind match {
        case "read"   => common :+ s"output [$w:0] ${p}_data"
        case "write"  => common :+ s"input  [$w:0] ${p}_data"
        case "mwrite" => common ++ Seq(s"input  [$w:0] ${p}_data", s"input  [${m.lanes - 1}:0] ${p}_mask")
        case "rw"     => common ++ Seq(s"input        ${p}_wmode", s"input  [$w:0] ${p}_wdata", s"output [$w:0] ${p}_rdata")
        case "mrw"    => common ++ Seq(s"input        ${p}_wmode", s"input  [$w:0] ${p}_wdata", s"output [$w:0] ${p}_rdata", s"input  [${m.lanes - 1}:0] ${p}_wmask")
      }
    }

    val arrays = lanes.map(l => s"  reg [${m.gran - 1}:0] mem_$l [0:${m.depth - 1}];").mkString("\n")

    def range(l: Int) = s"[${(l + 1) * m.gran - 1}:${l * m.gran}]"

    val portLogic = prefixed.map { case (kind, p) =>
      lanes.map { l =>
        kind match {
          case "read" =>
            s"""  reg [${m.gran - 1}:0] ${p}_data_$l;
               |  always @(posedge ${p}_clk) begin
               |    if (${p}_en)
               |      ${p}_data_$l <= mem_$l[${p}_addr];
               |  end
               |  assign ${p}_data${range(l)} = ${p}_data_$l;""".stripMargin
          case "write" =>
            s"""  always @(posedge ${p}_clk) begin
               |    if (${p}_en)
               |      mem_$l[${p}_addr] <= ${p}_data${range(l)};
               |  end""".stripMargin
          case "mwrite" =>
            s"""  always @(posedge ${p}_clk) begin
               |    if (${p}_en & ${p}_mask[$l])
               |      mem_$l[${p}_addr] <= ${p}_data${range(l)};
               |  end""".stripMargin
          case "rw" =>
            s"""  reg [${m.gran - 1}:0] ${p}_rdata_$l;
               |  always @(posedge ${p}_clk) begin
               |    if (${p}_en) begin
               |      if (${p}_wmode)
               |        mem_$l[${p}_addr] <= ${p}_wdata${range(l)};
               |      else
               |        ${p}_rdata_$l <= mem_$l[${p}_addr];
               |    end
               |  end
               |  assign ${p}_rdata${range(l)} = ${p}_rdata_$l;""".stripMargin
          case "mrw" =>
            s"""  reg [${m.gran - 1}:0] ${p}_rdata_$l;
               |  always @(posedge ${p}_clk) begin
               |    if (${p}_en) begin
               |      if (${p}_wmode) begin
               |        if (${p}_wmask[$l])
               |          mem_$l[${p}_addr] <= ${p}_wdata${range(l)};
               |      end else
               |        ${p}_rdata_$l <= mem_$l[${p}_addr];
               |    end
               |  end
               |  assign ${p}_rdata${range(l)} = ${p}_rdata_$l;""".stripMargin
        }
      }.mkString("\n")
    }.mkString("\n")

    s"""// Generated by SOCTMemGen (SoCeteer) from the firtool ReplSeqMem conf.
       |// ${m.name}: ${m.depth} x ${m.width}, ports ${m.ports.mkString(",")}${m.maskGran.map(g => s", mask_gran $g").getOrElse("")}
       |// One independent array per mask lane (${m.lanes} lane(s) of ${m.gran} bits), each with a
       |// single write and a registered read - the shape Vivado reliably infers as BRAM/LUTRAM.
       |// Latency 1 read, as the design expects; read-write collision behavior is undefined.
       |module ${m.name}(
       |${portDecls.mkString(",\n").linesIterator.map("  " + _).mkString("\n")}
       |);
       |
       |$arrays
       |
       |$portLogic
       |
       |endmodule
       |""".stripMargin
  }
}
