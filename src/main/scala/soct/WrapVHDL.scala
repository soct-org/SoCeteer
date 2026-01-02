package soct

import org.antlr.v4.runtime.{CharStreams, CommonTokenStream, Token}
import soct.antlr.verilog.{Verilog2001BaseListener, Verilog2001Lexer, Verilog2001Parser}
import soct.antlr.verilog.Verilog2001Parser._
import java.io.{PrintWriter, Writer}
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.Using

object WrapVHDL {

  // ---------------------- Public API ----------------------

  /** Transform Verilog -> VHDL wrapper, writing to a file path. */
  def transform(input: Path, output: Path, moduleName: String = "rocket"): Unit =
    Using.resource(Files.newBufferedWriter(output)) { bw =>
      transform(input, new PrintWriter(bw), moduleName)
    }

  /** Transform Verilog -> VHDL wrapper, writing to any Writer. */
  def transform(input: Path, out: Writer, moduleName: String): Unit = {
    val ctx = new Context(moduleName)
    val rocketModule = parseAndCollect(input, ctx)
      .getOrElse(sys.error("Cannot find RocketSystem module in the provided Verilog"))
    generateVhdl(ctx)(rocketModule, out)
  }

  // ---------------------- Internal model -------------------

  private sealed trait IfDefState
  private object IfDefState { case object SkipFalse extends IfDefState; case object SkipTrue extends IfDefState; case object SkipAll extends IfDefState }

  private final case class BusSignal(
                                      riscvName: String,
                                      xilinxName: String,
                                      signalName: String,
                                      busName: String,
                                      range: Verilog2001Parser.Range_Context,
                                      isOutput: Boolean,
                                      addrOffsetName: Option[String] = None
                                    )
  private final case class Bus(
                                signals: mutable.ListBuffer[BusSignal] = mutable.ListBuffer.empty,
                                var addrRange: Option[Verilog2001Parser.Range_Context] = None,
                                var dataRange: Option[Verilog2001Parser.Range_Context] = None
                              )
  private final class Context(val moduleName: String) {
    val macros   = mutable.HashMap("SYNTHESIS" -> "1")
    val axiBuses = mutable.HashMap.empty[String, Bus]
    val axiSignals = mutable.ListBuffer.empty[BusSignal]
    val dmiBus, jtagBus, bscanBus = Bus()
    val modules = mutable.HashSet.empty[String]
    var interruptBits  = 0
    var memAddrOffset  = false
    var rocketSystem: Option[Verilog2001Parser.Module_declarationContext] = None
    def maxNameLen(xs: Iterable[BusSignal]): Int = xs.view.map(_.signalName.length).maxOption.getOrElse(0)
  }
  private final case class PortDecl(id: Port_identifierContext, range: Range_Context, isOutput: Boolean)

  // ---------------------- Parsing phase --------------------

  /**
   * Parse Verilog with a minimal preprocessor:
   * honors `ifdef/ifndef/else/endif/define/undef` by skipping tokens in disabled regions.
   * When `RocketSystem` is seen, collect buses/ports.
   */
  private def parseAndCollect(input: Path, ctx: Context): Option[Verilog2001Parser.Module_declarationContext] = {
    val lexer = new Verilog2001Lexer(CharStreams.fromPath(input)) {
      private var skipping = false
      private val stack = new mutable.Stack[IfDefState]

      override def nextToken(): Token = {
        var t = super.nextToken()
        while (true) {
          if (t.getType == Token.EOF) return t
          val txt = t.getText.trim
          txt match {
            case s if s.startsWith("`ifdef")  => handleIfDef(argOf(s));   t = super.nextToken(); continue
            case s if s.startsWith("`ifndef") => handleIfNDef(argOf(s));  t = super.nextToken(); continue
            case s if s.startsWith("`else")   => handleElse();            t = super.nextToken(); continue
            case s if s.startsWith("`endif")  => handleEndIf();           t = super.nextToken(); continue
            case s if s.startsWith("`define") => handleDefine(argOf(s));  t = super.nextToken(); continue
            case s if s.startsWith("`undef")  => handleUnDef(argOf(s));   t = super.nextToken(); continue
            case _ if skipping                => t = super.nextToken(); continue
            case _                            => return t
          }
        }
        t
      }
      private def continue: Unit = ()
      private def argOf(s: String): String = s.dropWhile(!_.isWhitespace).trim
      private def handleIfDef(name: String): Unit =
        if (skipping) stack.push(IfDefState.SkipAll)
        else if (!ctx.macros.contains(name)) { stack.push(IfDefState.SkipTrue); skipping = true }
        else stack.push(IfDefState.SkipFalse)
      private def handleIfNDef(name: String): Unit =
        if (skipping) stack.push(IfDefState.SkipAll)
        else if (ctx.macros.contains(name)) { stack.push(IfDefState.SkipTrue); skipping = true }
        else stack.push(IfDefState.SkipFalse)
      private def handleElse(): Unit = if (stack.nonEmpty) {
        stack.pop() match {
          case IfDefState.SkipFalse => stack.push(IfDefState.SkipTrue);  skipping = true
          case IfDefState.SkipTrue  => stack.push(IfDefState.SkipFalse); skipping = false
          case IfDefState.SkipAll   => stack.push(IfDefState.SkipAll);   skipping = true
        }
      }
      private def handleEndIf(): Unit = { if (stack.nonEmpty) stack.pop(); skipping = stack.headOption.exists(_ != IfDefState.SkipFalse) }
      private def handleDefine(body: String): Unit =
        if (body.nonEmpty) { val parts = body.split("\\s+", 2); ctx.macros.update(parts.head, parts.lift(1).getOrElse("1")) }
      private def handleUnDef(body: String): Unit =
        if (body.nonEmpty) ctx.macros.remove(body.split("\\s+").head)
    }

    val parser = new Verilog2001Parser(new CommonTokenStream(lexer))
    parser.addParseListener(new Verilog2001BaseListener {
      override def exitModule_declaration(m: Module_declarationContext): Unit =
        Option(m.module_identifier()).foreach { idCtx =>
          if (idCtx.getText == "RocketSystem") { ctx.rocketSystem = Some(m); collectBusSignals(ctx) }
        }
      override def exitModule_instantiation(inst: Module_instantiationContext): Unit =
        ctx.modules += inst.module_identifier().getText
    })
    parser.source_text()
    ctx.rocketSystem
  }

  private def iteratePorts(portDecls: List_of_port_declarationsContext): Seq[PortDecl] = {
    val buf = mutable.ListBuffer.empty[PortDecl]
    portDecls.port_declaration().forEach { decl =>
      Option(decl.input_declaration()).foreach { inp =>
        val r = inp.range_()
        inp.list_of_port_identifiers().port_identifier().forEach { id => buf += PortDecl(id, r, isOutput = false) }
      }
      Option(decl.output_declaration()).foreach { out =>
        val r = out.range_()
        out.list_of_port_identifiers().port_identifier().forEach { id => buf += PortDecl(id, r, isOutput = true) }
      }
    }
    buf.toSeq
  }

  /**
   * Bus collection:
   * - Map Rocket signal suffixes to Xilinx AXI/JTAG/DMI names.
   * - Detect interrupt width, address/data ranges, and multi-instance bus naming.
   * - Synthesize BSCAN when DMI exists and module name ends with 'e'.
   */
  private def collectBusSignals(ctx: Context): Unit = {
    val portDecls = ctx.rocketSystem.flatMap(rs => Option(rs.list_of_port_declarations())).getOrElse(return)
    val portList  = iteratePorts(portDecls)

    val suffixToXilinx = Seq(
      "aw_ready" -> "AWREADY", "aw_valid" -> "AWVALID", "aw_bits_id" -> "AWID",
      "aw_bits_addr" -> "AWADDR", "aw_bits_len" -> "AWLEN", "aw_bits_size" -> "AWSIZE",
      "aw_bits_burst" -> "AWBURST", "aw_bits_lock" -> "AWLOCK", "aw_bits_cache" -> "AWCACHE",
      "aw_bits_prot" -> "AWPROT", "aw_bits_qos" -> "AWQOS",
      "w_ready" -> "WREADY", "w_valid" -> "WVALID", "w_bits_data" -> "WDATA",
      "w_bits_strb" -> "WSTRB", "w_bits_last" -> "WLAST",
      "b_ready" -> "BREADY", "b_valid" -> "BVALID", "b_bits_id" -> "BID", "b_bits_resp" -> "BRESP",
      "ar_ready" -> "ARREADY", "ar_valid" -> "ARVALID", "ar_bits_id" -> "ARID",
      "ar_bits_addr" -> "ARADDR", "ar_bits_len" -> "ARLEN", "ar_bits_size" -> "ARSIZE",
      "ar_bits_burst" -> "ARBURST", "ar_bits_lock" -> "ARLOCK", "ar_bits_cache" -> "ARCACHE",
      "ar_bits_prot" -> "ARPROT", "ar_bits_qos" -> "ARQOS",
      "r_ready" -> "RREADY", "r_valid" -> "RVALID", "r_bits_id" -> "RID",
      "r_bits_data" -> "RDATA", "r_bits_resp" -> "RRESP", "r_bits_last" -> "RLAST",
      "req_ready" -> "o_ready", "req_valid" -> "o_valid", "req_bits_addr" -> "o_addr",
      "req_bits_data" -> "o_data", "req_bits_op" -> "o_op",
      "resp_ready" -> "i_ready", "resp_valid" -> "i_valid",
      "resp_bits_data" -> "i_data", "resp_bits_resp" -> "i_resp",
      "jtag_TCK" -> "tck", "jtag_TMS" -> "tms", "jtag_TDI" -> "tdi",
      "jtag_TDO_data" -> "tdo", "jtag_TDO_driven" -> "tdt"
    )

    // Detect multi-instance buses
    val (multiMem, multiIo, multiDma) = {
      var mm = false; var mi = false; var md = false
      portList.foreach { p =>
        val n = p.id.getText
        if (n.startsWith("mem_axi4_") && !n.startsWith("mem_axi4_0_")) mm = true
        if (n.startsWith("mmio_axi4_") && !n.startsWith("mmio_axi4_0_")) mi = true
        if (n.startsWith("l2_frontend_bus_axi4_") && !n.startsWith("l2_frontend_bus_axi4_0_")) md = true
      }
      (mm, mi, md)
    }

    portList.foreach { p =>
      val sigName = p.id.getText
      if (sigName == "interrupts") ctx.interruptBits = Option(p.range).map(rangeLen).getOrElse(1)

      val busNameOpt: Option[String] = sigName match {
        case s if s.startsWith("mem_axi4_") =>
          val idx = parseBusIndex(s.stripPrefix("mem_axi4_"))
          Some(if (!multiMem && idx == 0) "mem_axi4" else s"mem_axi4_$idx")
        case s if s.startsWith("mmio_axi4_") =>
          val idx = parseBusIndex(s.stripPrefix("mmio_axi4_"))
          Some(if (!multiIo && idx == 0) "io_axi4" else s"io_axi4_$idx")
        case s if s.startsWith("l2_frontend_bus_axi4_") =>
          val idx = parseBusIndex(s.stripPrefix("l2_frontend_bus_axi4_"))
          Some(if (!multiDma && idx == 0) "dma_axi4" else s"dma_axi4_$idx")
        case s if s.startsWith("debug_clockeddmi_dmi_") || s.startsWith("debug_debug_clockeddmi_dmi_") =>
          Some("dmi")
        case s if s.startsWith("debug_systemjtag_jtag_") || s.startsWith("debug_debug_systemjtag_jtag_") =>
          Some("jtag")
        case _ => None
      }

      busNameOpt.foreach { busName =>
        suffixToXilinx.find { case (suf, _) => sigName.endsWith("_" + suf) }.foreach { case (_, xilinx) =>
          val addrOffsetName =
            if (busName.startsWith("mem_axi4") && (xilinx == "AWADDR" || xilinx == "ARADDR")) {
              ctx.memAddrOffset = true; Some(s"${busName}_${xilinx.toLowerCase}_sys")
            } else None

          val sig = BusSignal(
            riscvName = sigName,
            xilinxName = xilinx,
            signalName = busName + "_" + xilinx.toLowerCase,
            busName = busName,
            range = p.range,
            isOutput = p.isOutput,
            addrOffsetName = addrOffsetName
          )

          busName match {
            case "jtag" => ctx.jtagBus.signals += sig
            case "dmi"  => ctx.dmiBus.signals  += sig
            case _ =>
              ctx.axiSignals += sig
              val bus = ctx.axiBuses.getOrElseUpdate(busName, Bus())
              bus.signals += sig
              if (bus.addrRange.isEmpty && xilinx.endsWith("ADDR")) bus.addrRange = Option(p.range)
              if (bus.dataRange.isEmpty && xilinx.endsWith("DATA")) bus.dataRange = Option(p.range)
          }
        }
      }
    }

    // Synthesize BSCAN if needed (Series-7 DMI path)
    if (ctx.dmiBus.signals.nonEmpty && ctx.moduleName.endsWith("e")) {
      Seq("capture","drck","reset","runtest","sel","shift","tck","tdi","tdo","tms","update").foreach { nm =>
        ctx.bscanBus.signals += BusSignal(
          riscvName = s"S_BSCAN_$nm",
          xilinxName = nm.toUpperCase,
          signalName = s"S_BSCAN_$nm",
          busName = "S_BSCAN",
          range = null,
          isOutput = nm == "tdo"
        )
      }
    }
  }

  private def parseBusIndex(rest: String): Int =
    rest.takeWhile(_.isDigit).toIntOption.getOrElse(0)

  // ---------------------- Small helpers -------------------

  private def expr(e: Verilog2001Parser.Constant_expressionContext): String = expr(e.expression())
  private def expr(e: Verilog2001Parser.ExpressionContext): String = {
    val terms = e.term().toArray(new Array[TermContext](0))
    val ops   = e.binary_operator().toArray(new Array[Binary_operatorContext](0))
    terms.indices.map { i =>
      val t = Option(terms(i).String()).map(_.toString).orElse(Option(terms(i).unary_operator()).map(_.getText)).getOrElse(terms(i).primary().getText)
      if (i == 0) t else s"${ops(i - 1).getText} $t"
    }.mkString(" ")
  }
  private def rangeStr(r: Verilog2001Parser.Range_Context): String = {
    val msb = expr(r.msb_constant_expression().constant_expression())
    val lsb = expr(r.lsb_constant_expression().constant_expression())
    s"$msb downto $lsb"
  }
  private def rangeLen(r: Verilog2001Parser.Range_Context): Int = {
    val msb = expr(r.msb_constant_expression().constant_expression()).toInt
    val lsb = expr(r.lsb_constant_expression().constant_expression()).toInt
    msb - lsb + 1
  }
  private def rangeHigh(r: Verilog2001Parser.Range_Context): Int =
    expr(r.msb_constant_expression().constant_expression()).toInt

  // ---------------------- Rendering phase -----------------

  private def generateVhdl(ctx: Context)(rs: Verilog2001Parser.Module_declarationContext, out: Writer): Unit = {
    val sb = new StringBuilder
    def add(lines: String*): Unit = lines.foreach { l => sb.append(l).append('\n') }
    def block(txt: String): Unit = add(txt.stripMargin.split("\n"): _*)

    def copyModule(name: String, targetLang: String = "vhdl"): Unit = {
      require(targetLang == "vhdl", s"Unsupported target language: $targetLang")
      val path = SOCTPaths.get(s"${targetLang}srcs").resolve(s"$name.$targetLang")
      require(Files.exists(path), s"Cannot find $targetLang module source: $path")
      val file = Files.readAllLines(path)
      file.forEach { line => add(line) }
    }

    // Entity ports
    def emitEntityPort(): Unit = {
      add("")
      add(s"entity ${ctx.moduleName} is")
      if (ctx.memAddrOffset) block(
        """generic (
          |    RAM_ADDR_OFFSET_MB : integer := 0);
          |""")
      add("port (")
      add(
        """    clock      : in std_logic;
          |    clock_ok   : in std_logic;
          |    mem_ok     : in std_logic;
          |    io_ok      : in std_logic;
          |    sys_reset  : in std_logic;
          |    aresetn    : out std_logic;
          |""".stripMargin)
      if (ctx.interruptBits > 0)
        add(s"    interrupts: in std_logic_vector(${ctx.interruptBits - 1} downto 0);\n")
      val pad = ctx.maxNameLen(ctx.axiSignals)
      def padName(n: String): String = n + " " * (pad - n.length)
      val grouped = ctx.axiSignals.groupBy(_.busName).toSeq.sortBy(_._1)
      val allSignals = grouped.flatMap(_._2)
      allSignals.zipWithIndex.foreach { case (sig, idx) =>
        val dir = if (sig.isOutput) "out" else "in "
        val typ = sig.addrOffsetName
          .map(_ => s"std_logic_vector(${rangeHigh(sig.range)} downto 0)")
          .orElse(Option(sig.range).map(r => s"std_logic_vector(${rangeStr(r)})"))
          .getOrElse("std_logic")
        val suffix = if (idx == allSignals.size - 1 && ctx.jtagBus.signals.isEmpty && ctx.bscanBus.signals.isEmpty) ");" else ";"
        add(s"    ${padName(sig.signalName)}: $dir $typ$suffix")
        if (idx < allSignals.size - 1 && allSignals(idx + 1).busName != sig.busName) add("")
      }
      def emitSimpleBus(bus: Bus, terminate: Boolean): Unit = if (bus.signals.nonEmpty) {
        add("")
        val padLocal = ctx.maxNameLen(bus.signals)
        bus.signals.zipWithIndex.foreach { case (sig, idx) =>
          val dir = if (sig.isOutput) "out" else "in "
          val typ = if (sig.range != null) s"std_logic_vector(${rangeStr(sig.range)})" else "std_logic"
          val suffix = if (terminate && idx == bus.signals.size - 1) ");" else ";"
          add(s"    ${sig.signalName.padTo(padLocal, ' ')}: $dir $typ$suffix")
        }
      }
      emitSimpleBus(ctx.jtagBus, terminate = ctx.bscanBus.signals.isEmpty)
      emitSimpleBus(ctx.bscanBus, terminate = true)
      if (ctx.jtagBus.signals.isEmpty && ctx.bscanBus.signals.isEmpty) add(");")
      add(s"end ${ctx.moduleName};")
    }

    // Bus attributes
    def emitBusAttributes(): Unit = {
      ctx.axiBuses.values.foreach { bus =>
        add("")
        val pad = ctx.maxNameLen(bus.signals)
        bus.signals.foreach { sig =>
          add(f"    ATTRIBUTE X_INTERFACE_INFO of ${sig.signalName.padTo(pad, ' ')}: SIGNAL is \"xilinx.com:interface:aximm:1.0 ${sig.busName.toUpperCase} ${sig.xilinxName}\";")
        }
        val aw = bus.addrRange.map(rangeLen).getOrElse(32)
        val dw = bus.dataRange.map(rangeLen).getOrElse(32)
        add(s"    ATTRIBUTE X_INTERFACE_PARAMETER of ${bus.signals.head.signalName}: SIGNAL is \"CLK_DOMAIN clock, PROTOCOL AXI4, ADDR_WIDTH $aw, DATA_WIDTH $dw\";")
      }
      if (ctx.jtagBus.signals.nonEmpty) block(
        """    ATTRIBUTE X_INTERFACE_INFO of jtag_tck : SIGNAL is "xilinx.com:interface:jtag:1.0 JTAG TCK";
          |    ATTRIBUTE X_INTERFACE_INFO of jtag_tms : SIGNAL is "xilinx.com:interface:jtag:1.0 JTAG TMS";
          |    ATTRIBUTE X_INTERFACE_INFO of jtag_tdi : SIGNAL is "xilinx.com:interface:jtag:1.0 JTAG TD_I";
          |    ATTRIBUTE X_INTERFACE_INFO of jtag_tdo : SIGNAL is "xilinx.com:interface:jtag:1.0 JTAG TD_O";
          |    ATTRIBUTE X_INTERFACE_INFO of jtag_tdt : SIGNAL is "xilinx.com:interface:jtag:1.0 JTAG TD_T";
          |""")
      if (ctx.bscanBus.signals.nonEmpty) block(
        """    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_update  : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN UPDATE";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_tms     : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN TMS";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_tdo     : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN TDO";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_tdi     : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN TDI";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_tck     : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN TCK";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_shift   : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN SHIFT";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_sel     : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN SEL";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_runtest : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN RUNTEST";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_reset   : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN RESET";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_drck    : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN DRCK";
          |    ATTRIBUTE X_INTERFACE_INFO of S_BSCAN_capture : SIGNAL is "xilinx.com:interface:bscan:1.0 S_BSCAN CAPTURE";
          |""")
    }

    // RocketSystem component declaration
    def emitRocketComponentDeclaration(): Unit = {
      val portDecls = Option(rs.list_of_port_declarations()).getOrElse(sys.error("RocketSystem missing port declarations"))
      add("")
      add("    component RocketSystem is")
      add("    port (")
      val decls = iteratePorts(portDecls)
      val pad = decls.map(_.id.getText.length).foldLeft(0)(math.max)
      decls.zipWithIndex.foreach { case (p, idx) =>
        val dir = if (p.isOutput) "out" else "in "
        val typ = Option(p.range).map(r => s"std_logic_vector(${rangeStr(r)})").getOrElse("std_logic")
        val suffix = if (idx == decls.size - 1) ");" else ";"
        add(s"        ${p.id.getText.padTo(pad, ' ')}: $dir $typ$suffix")
      }
      add("    end component RocketSystem;")
    }

    // Internal signals
    def emitSignalDeclarations(): Unit = {
      block(
        """
          |    attribute ASYNC_REG : string;
          |
          |    signal reset       : std_logic := '1';
          |    signal debug_reset : std_logic;
          |    signal riscv_reset : std_logic;
          |""")
      if (ctx.jtagBus.signals.nonEmpty) add("    signal enable_tdo  : std_logic;")
      if (ctx.dmiBus.signals.nonEmpty) {
        add("")
        val pad = ctx.maxNameLen(ctx.dmiBus.signals)
        ctx.dmiBus.signals.foreach { sig =>
          val typ = Option(sig.range).map(r => s"std_logic_vector(${rangeStr(r)})").getOrElse("std_logic")
          add(s"    signal ${sig.signalName.padTo(pad, ' ')} : $typ;")
        }
      }
      block(
        """
          |
          |    signal debug_dmactive : std_logic;
          |
          |    signal reset_cnt : unsigned(4 downto 0) := "00000";
          |    signal reset_inp : std_logic;
          |    signal reset_sync: std_logic;
          |""")
      if (ctx.interruptBits > 0) add(
        s"""    signal interrupts_ss1 : std_logic_vector(${ctx.interruptBits - 1} downto 0);
           |    signal interrupts_ss2 : std_logic_vector(${ctx.interruptBits - 1} downto 0);
           |    signal interrupts_sync: std_logic_vector(${ctx.interruptBits - 1} downto 0);
           |    attribute ASYNC_REG of interrupts_ss1 : signal is "TRUE";
           |    attribute ASYNC_REG of interrupts_ss2 : signal is "TRUE";
           |    attribute ASYNC_REG of interrupts_sync: signal is "TRUE";
           |""".stripMargin)
      if (ctx.memAddrOffset) {
        add("")
        add("""    constant mem_start_addr : unsigned(31 downto 0) := X"80000000";""")
        ctx.axiSignals.foreach { sig =>
          sig.addrOffsetName.foreach { name =>
            add(s"    signal $name : std_logic_vector(${rangeStr(sig.range)});")
          }
        }
      }
    }

    // Reset logic
    def emitResetLogic(): Unit = add(
      """|
         |    reset_inp <= sys_reset or not clock_ok or not mem_ok or not io_ok;
         |
         |    syn_reset : entity work.synchronizer
         |    port map (
         |        clock => clock,
         |        dinp  => reset_inp,
         |        dout  => reset_sync);
         |
         |    process (clock)
         |    begin
         |        if clock'event and clock = '1' then
         |            if reset_sync = '1' then
         |                reset_cnt <= (others => '0');
         |                aresetn <= '0';
         |                reset <= '1';
         |            elsif reset_cnt < "01111" then
         |                reset_cnt <= reset_cnt + 1;
         |                aresetn <= '0';
         |                reset <= '1';
         |            elsif reset_cnt < "11111" then
         |                reset_cnt <= reset_cnt + 1;
         |                aresetn <= '1';
         |                reset <= '1';
         |            else
         |                aresetn <= '1';
         |                reset <= '0';
         |            end if;
         |        end if;
         |    end process;
         |
         |    riscv_reset <= reset or debug_reset;
         |""".stripMargin)

    // Interrupt sync
    def emitInterruptSync(): Unit = if (ctx.interruptBits > 0) add(
      """|
         |    process (clock)
         |    begin
         |        if clock'event and clock = '1' then
         |            interrupts_ss1 <= interrupts;
         |            interrupts_ss2 <= interrupts_ss1;
         |            interrupts_sync <= interrupts_ss2;
         |        end if;
         |    end process;
         |""".stripMargin)

    // RocketSystem instance mapping
    def emitRocketInstance(): Unit = {
      val portDecls = Option(rs.list_of_port_declarations()).getOrElse(sys.error("RocketSystem missing port declarations"))
      if (ctx.memAddrOffset) {
        add("")
        ctx.axiSignals.foreach { sig =>
          sig.addrOffsetName.foreach { offs =>
            val h = rangeHigh(sig.range)
            add(s"    ${sig.signalName} <= std_logic_vector(unsigned($offs) - mem_start_addr + shift_left(to_unsigned(RAM_ADDR_OFFSET_MB, ${h + 1}), 20));")
          }
        }
      }

      add("")
      add("    rocket_system : component RocketSystem")
      add("    port map (")

      val decls = iteratePorts(portDecls)
      val pad = decls.map(_.id.getText.length).foldLeft(0)(math.max)
      val signalMap: Map[String, BusSignal] =
        (ctx.axiSignals ++ ctx.dmiBus.signals ++ ctx.jtagBus.signals.filterNot(_.riscvName.endsWith("TDO_driven"))).map(s => s.riscvName -> s).toMap

      def defaultMapping(name: String): String = name match {
        case "reset" | "debug_reset"       => "riscv_reset"
        case "debug_clock"                 => "clock"
        case "debug_clockeddmi_dmiClock"   => "clock"
        case "debug_clockeddmi_dmiReset"   => "reset"
        case "debug_systemjtag_reset"      => "'0'"
        case "debug_systemjtag_mfr_id"     => "\"10010001001\""
        case "debug_systemjtag_part_number"=> "\"0000000000000000\""
        case "debug_systemjtag_version"    => "\"0000\""
        case "debug_systemjtag_jtag_TDO_driven" => "enable_tdo"
        case "debug_ndreset"               => "debug_reset"
        case "debug_dmactive" | "debug_dmactiveAck" => "debug_dmactive"
        case nm if nm.startsWith("resetctrl_hartIsInReset") => "'0'"
        case "interrupts"                  => "interrupts_sync"
        case nm if nm.endsWith("_clock")   => "clock"
        case nm if nm.endsWith("_reset")   => "reset"
        case other                         => other
      }

      decls.zipWithIndex.foreach { case (p, idx) =>
        val dst = signalMap.get(p.id.getText).map(s => s.addrOffsetName.getOrElse(s.signalName)).getOrElse(defaultMapping(p.id.getText))
        val suffix = if (idx == decls.size - 1) ");" else ","
        add(s"        ${p.id.getText.padTo(pad, ' ')} => $dst$suffix")
      }
    }

    // Debug components (JTAG/JTAG+BSCAN)
    def emitDebugComponents(): Unit = if (ctx.dmiBus.signals.nonEmpty) {
      add("")
      val (entityName, bscanMap) =
        if (ctx.bscanBus.signals.nonEmpty) "JtagExtBscan" -> ctx.bscanBus.signals else "JtagSeries7" -> Seq.empty
      add(s"    jtag : entity work.$entityName")
      add("    port map (")
      add("        clock => clock,")
      add("        reset => reset,")
      bscanMap.foreach { sig => add(s"        ${sig.signalName} => ${sig.signalName},") }
      val pad = ctx.maxNameLen(ctx.dmiBus.signals)
      ctx.dmiBus.signals.zipWithIndex.foreach { case (sig, idx) =>
        val suffix = if (idx == ctx.dmiBus.signals.size - 1) ");" else ","
        add(s"        ${sig.signalName.padTo(pad, ' ')} => ${sig.signalName}$suffix")
      }
    }

    // ----- Emit VHDL -----
    copyModule("sync")
    if (ctx.dmiBus.signals.nonEmpty) { add(""); copyModule(if (ctx.bscanBus.signals.nonEmpty) "jtag-ext-bscan" else "jtag") }

    add("")
    add(
      """library ieee;
        |use ieee.std_logic_1164.all;
        |use ieee.numeric_std.all;
        |""".stripMargin)

    emitEntityPort()

    add("")
    add(s"architecture Behavioral of ${ctx.moduleName} is")
    add(
      """    ATTRIBUTE X_INTERFACE_INFO : STRING;
        |    ATTRIBUTE X_INTERFACE_PARAMETER : STRING;
        |""".stripMargin)
    val axiBusNames = ctx.axiBuses.keys.toSeq.sorted
    val associatedBus = if (axiBusNames.nonEmpty) s", ASSOCIATED_BUSIF ${axiBusNames.map(_.toUpperCase).mkString(":")}" else ""
    add(
      s"""    ATTRIBUTE X_INTERFACE_INFO of sys_reset: SIGNAL is "xilinx.com:signal:reset:1.0 sys_reset RST";
         |    ATTRIBUTE X_INTERFACE_PARAMETER of sys_reset: SIGNAL is "POLARITY ACTIVE_HIGH";
         |    ATTRIBUTE X_INTERFACE_INFO of aresetn: SIGNAL is "xilinx.com:signal:reset:1.0 aresetn RST";
         |    ATTRIBUTE X_INTERFACE_PARAMETER of aresetn: SIGNAL is "POLARITY ACTIVE_LOW";
         |    ATTRIBUTE X_INTERFACE_INFO of clock: SIGNAL is "xilinx.com:signal:clock:1.0 clock CLK";
         |    ATTRIBUTE X_INTERFACE_PARAMETER of clock: SIGNAL is "ASSOCIATED_RESET aresetn$associatedBus";
         |""".stripMargin)

    emitBusAttributes()
    emitRocketComponentDeclaration()
    emitSignalDeclarations()

    add("")
    add("begin")

    emitResetLogic()
    emitInterruptSync()
    if (ctx.jtagBus.signals.nonEmpty) add("    jtag_tdt <= not enable_tdo;")
    emitRocketInstance()
    emitDebugComponents()

    add("")
    add("end Behavioral;")

    // Optional helper stubs
    if (ctx.modules.contains("plusarg_reader")) add(
      """|
         |library ieee;
         |use ieee.std_logic_1164.all;
         |use ieee.numeric_std.all;
         |
         |entity plusarg_reader is
         |    generic (FORMAT : string := ""; DEFAULT : integer := 0; WIDTH : integer := 32);
         |    port (\out\: out std_logic_vector(WIDTH-1 downto 0));
         |end plusarg_reader;
         |
         |architecture Behavioral of plusarg_reader is
         |begin
         |    \out\ <= std_logic_vector(to_unsigned(DEFAULT, WIDTH));
         |end Behavioral;
         |""".stripMargin)

    if (ctx.modules.contains("EICG_wrapper")) add(
      """|
         |library ieee;
         |use ieee.std_logic_1164.all;
         |library unisim;
         |use unisim.Vcomponents.all;
         |
         |entity EICG_wrapper is
         |    port (\in\: in std_logic; en: in std_logic; \out\: out std_logic);
         |end EICG_wrapper;
         |
         |architecture Behavioral of EICG_wrapper is
         |begin
         |    buf : BUFGCE
         |    port map (I => \in\, CE => en, O => \out\);
         |end Behavioral;
         |""".stripMargin)

    if (ctx.modules.contains("AsyncResetReg")) add(
      """|
         |library ieee;
         |use ieee.std_logic_1164.all;
         |
         |entity AsyncResetReg is
         |    generic (RESET_VALUE : integer := 0);
         |    port (d: in std_logic; q: out std_logic; en: in std_logic; clk: in std_logic; rst: in std_logic);
         |end AsyncResetReg;
         |
         |architecture Behavioral of AsyncResetReg is
         |begin
         |    process (clk,rst)
         |    begin
         |        if rst = '1' then
         |            if RESET_VALUE = 0 then q <= '0'; else q <= '1'; end if;
         |        elsif clk'event and clk = '1' and en = '1' then
         |            q <= d;
         |        end if;
         |    end process;
         |end Behavioral;
         |""".stripMargin)

    out.write(sb.result())
    out.flush()
  }
}