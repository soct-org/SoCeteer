package soct

import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.{CharStreams, CommonTokenStream, ParserRuleContext, TokenStream}
import soct.SOCTVivado.VIVADO_WRAPPER_TOP
import soct.antlr.verilog.{sv2017Lexer, sv2017Parser, sv2017ParserBaseListener}
import soct.antlr.verilog.sv2017Parser.{List_of_port_declarationsContext, Module_declarationContext, Port_identifierContext, Range_expressionContext}

import java.nio.file.Path
import scala.collection.mutable


object SOCTVivadoHelper {


  /**
   * A simple case class to hold port declarations
   *
   * @param id       The port identifier
   * @param range    The range expression
   * @param isOutput Whether the port is an output
   */
  private final case class PortDecl(id: Port_identifierContext, range: Range_expressionContext, isOutput: Boolean)


  /**
   * A simple case class to hold bus signal information
   *
   * @param riscvName
   * @param xilinxName
   * @param signalName
   * @param busName
   * @param range
   * @param isOutput
   * @param addrOffsetName
   */
  private final case class BusSignal(
                                      riscvName: String,
                                      xilinxName: String,
                                      signalName: String,
                                      busName: String,
                                      range: Range_expressionContext,
                                      isOutput: Boolean,
                                      addrOffsetName: Option[String] = None
                                    )

  /**
   * A simple case class to hold bus information
   *
   * @param signals   The bus signals: address/data/control
   * @param addrRange The address range
   * @param dataRange The data range
   */
  private final case class Bus(
                                signals: mutable.ListBuffer[BusSignal] = mutable.ListBuffer.empty,
                                var addrRange: Option[Range_expressionContext] = None,
                                var dataRange: Option[Range_expressionContext] = None
                              )

  /**
   *
   * @param moduleName
   */
  private final class ModuleInfo(val moduleName: String) {
    val axiBuses = mutable.HashMap.empty[String, Bus]
    val axiSignals = mutable.ListBuffer.empty[BusSignal]
    val dmiBus, jtagBus, bscanBus = Bus()
    val modules = mutable.HashSet.empty[String]
    var interruptBits = 0
    var memAddrOffset = false
    var top: Option[Module_declarationContext] = None
  }


  def transform(inPath: Path, outPath: Path, top: String): Unit = {
    val lexer = new sv2017Lexer(CharStreams.fromPath(inPath))
    val parser = new sv2017Parser(new CommonTokenStream(lexer))

    parser.addParseListener(new sv2017ParserBaseListener {
      override def exitModule_declaration(m: Module_declarationContext): Unit = {
        val name = m.module_header_common().identifier().getText
        if (name == top) {
          val info = new ModuleInfo(VIVADO_WRAPPER_TOP)
          info.top = Some(m)
          collectBusSignals(m)
        }
      }
    })
    parser.source_text()
  }

  private def iteratePorts(portDecls: List_of_port_declarationsContext): Seq[PortDecl] = {
    // Keep track of last direction: port declarations can omit it and implicitly use the last one thet was specified
    var lastIsOutput: Option[Boolean] = None

    println(portDecls.nonansi_port())

    portDecls.list_of_port_declarations_ansi_item().forEach { decl =>
      val ansi = decl.ansi_port_declaration()
      val dir = ansi.port_direction() match {
        case null =>
          if (lastIsOutput.isEmpty) {
            throw new Exception(s"Port direction not specified and no previous direction to infer from for port: ${ansi.port_identifier().getText}")
          }
          lastIsOutput
        case pd => pd.getText match {
          case "input" =>
            lastIsOutput = Some(false)
            lastIsOutput
          case "output" =>
            lastIsOutput = Some(true)
            lastIsOutput
          case _ =>
            throw new Exception(s"Unknown port direction: ${pd.getText} for port: ${ansi.port_identifier().getText}")
        }
      }
      val id = ansi.port_identifier()


      // get the range expression if any
      ansi.net_or_var_data_type() match {
        case null =>
        case dt =>
          // Get the first child that is a range expression if available
          if (dt.getChildCount > 0) {
            val child = dt.getChild(0)
            // get lhs and rhs of child
            for (i <- 0 until child.getChildCount) {
              println(s"Child $i: ${child.getChild(i).getText}")
            }
          }
      }
      println(s"Port: ${id.getText}, isOutput: $dir")
    }
    Seq.empty
  }

  private def collectBusSignals(m: Module_declarationContext): Seq[BusSignal] = {
    val portDecls = m.list_of_port_declarations()
    val ports = iteratePorts(portDecls)
    Seq.empty
  }


}
