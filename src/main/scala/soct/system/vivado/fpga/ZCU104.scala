package soct.system.vivado.fpga

import org.chipsalliance.cde.config.Parameters
import soct.SOCTFreq._
import soct.system.vivado.{SOCTBdBuilder, VivadoDesignException}
import soct.system.vivado.misc.{FPGAPMODPin, RawPMODPin}


object ZCU104 extends FPGA {
  override val friendlyName: String = "ZCU104"

  override val xilinxPart: String = "xczu7ev-ffvc1156-2-e"

  override val partName: String = "xilinx.com:zcu104:part0:1.1"

  override val tpe: String = "zcu104"

  override val getPMODPorts: Seq[Int] = Seq(0, 1) // PMOD ports 0 and 1; port 2 is I2C

  override def extDDR4Ports: Seq[DDR4PortParams] = Seq(
    new DDR4PortParams {
      override val portName: String = "ddr4_sdram"

      // The ZCU104 board files preset the DDR4 SODIMM interface to this 4 GiB part and the
      // board flow locks the controller to it. A different DIMM (e.g. a 16 GiB module) is
      // supported through the custom (non board-flow) interface below.
      override def defaultMemoryPart: Option[String] = Some("MTA8ATF51264HZ-2G1")

      // Custom-interface data, taken from the ZCU104 board files (preset.xml timing and the
      // full part0_pins.xml pin map - including the second-rank pins cs1_n/cke1/odt1/ck1,
      // which the SODIMM socket wires but the single-rank preset never uses).
      override def ddr4TimePeriodPs: Option[Int] = Some(938)

      override def ddr4InputClockPeriodPs: Option[Int] = Some(3335)

      override def ddr4PinMap: Option[Seq[DDR4Pin]] = Some(ZCU104.ddr4SdramPins)
    },
  )

  /** DDR4 SODIMM pin map from the ZCU104 board files (part0_pins.xml, board rev 1.1) */
  private val ddr4SdramPins: Seq[DDR4Pin] = Seq(
    DDR4Pin("act_n", None, "AC17"),
    DDR4Pin("adr", Some(0), "AH16"),
    DDR4Pin("adr", Some(1), "AG14"),
    DDR4Pin("adr", Some(2), "AG15"),
    DDR4Pin("adr", Some(3), "AF15"),
    DDR4Pin("adr", Some(4), "AF16"),
    DDR4Pin("adr", Some(5), "AJ14"),
    DDR4Pin("adr", Some(6), "AH14"),
    DDR4Pin("adr", Some(7), "AF17"),
    DDR4Pin("adr", Some(8), "AK17"),
    DDR4Pin("adr", Some(9), "AJ17"),
    DDR4Pin("adr", Some(10), "AK14"),
    DDR4Pin("adr", Some(11), "AK15"),
    DDR4Pin("adr", Some(12), "AL18"),
    DDR4Pin("adr", Some(13), "AK18"),
    DDR4Pin("adr", Some(14), "AA16"),
    DDR4Pin("adr", Some(15), "AA14"),
    DDR4Pin("adr", Some(16), "AD15"),
    DDR4Pin("ba", Some(0), "AL15"),
    DDR4Pin("ba", Some(1), "AL16"),
    DDR4Pin("bg", Some(0), "AC16"),
    DDR4Pin("bg", Some(1), "AB16"),
    DDR4Pin("ck_c", Some(0), "AG18"),
    DDR4Pin("ck_c", Some(1), "AJ15"),
    DDR4Pin("ck_t", Some(0), "AF18"),
    DDR4Pin("ck_t", Some(1), "AJ16"),
    DDR4Pin("cke", Some(0), "AD17"),
    DDR4Pin("cke", Some(1), "AM15"),
    DDR4Pin("cs_n", Some(0), "AA15"),
    DDR4Pin("cs_n", Some(1), "AL17"),
    DDR4Pin("dm_n", Some(0), "AH22"),
    DDR4Pin("dm_n", Some(1), "AE18"),
    DDR4Pin("dm_n", Some(2), "AL20"),
    DDR4Pin("dm_n", Some(3), "AP19"),
    DDR4Pin("dm_n", Some(4), "AF11"),
    DDR4Pin("dm_n", Some(5), "AH12"),
    DDR4Pin("dm_n", Some(6), "AK13"),
    DDR4Pin("dm_n", Some(7), "AN12"),
    DDR4Pin("dq", Some(0), "AE24"),
    DDR4Pin("dq", Some(1), "AE23"),
    DDR4Pin("dq", Some(2), "AF22"),
    DDR4Pin("dq", Some(3), "AF21"),
    DDR4Pin("dq", Some(4), "AG20"),
    DDR4Pin("dq", Some(5), "AG19"),
    DDR4Pin("dq", Some(6), "AH21"),
    DDR4Pin("dq", Some(7), "AG21"),
    DDR4Pin("dq", Some(8), "AA20"),
    DDR4Pin("dq", Some(9), "AA19"),
    DDR4Pin("dq", Some(10), "AD19"),
    DDR4Pin("dq", Some(11), "AC18"),
    DDR4Pin("dq", Some(12), "AE20"),
    DDR4Pin("dq", Some(13), "AD20"),
    DDR4Pin("dq", Some(14), "AC19"),
    DDR4Pin("dq", Some(15), "AB19"),
    DDR4Pin("dq", Some(16), "AJ22"),
    DDR4Pin("dq", Some(17), "AJ21"),
    DDR4Pin("dq", Some(18), "AK20"),
    DDR4Pin("dq", Some(19), "AJ20"),
    DDR4Pin("dq", Some(20), "AK19"),
    DDR4Pin("dq", Some(21), "AJ19"),
    DDR4Pin("dq", Some(22), "AL23"),
    DDR4Pin("dq", Some(23), "AL22"),
    DDR4Pin("dq", Some(24), "AN23"),
    DDR4Pin("dq", Some(25), "AM23"),
    DDR4Pin("dq", Some(26), "AP23"),
    DDR4Pin("dq", Some(27), "AN22"),
    DDR4Pin("dq", Some(28), "AP22"),
    DDR4Pin("dq", Some(29), "AP21"),
    DDR4Pin("dq", Some(30), "AN19"),
    DDR4Pin("dq", Some(31), "AM19"),
    DDR4Pin("dq", Some(32), "AC13"),
    DDR4Pin("dq", Some(33), "AB13"),
    DDR4Pin("dq", Some(34), "AF12"),
    DDR4Pin("dq", Some(35), "AE12"),
    DDR4Pin("dq", Some(36), "AF13"),
    DDR4Pin("dq", Some(37), "AE13"),
    DDR4Pin("dq", Some(38), "AE14"),
    DDR4Pin("dq", Some(39), "AD14"),
    DDR4Pin("dq", Some(40), "AG8"),
    DDR4Pin("dq", Some(41), "AF8"),
    DDR4Pin("dq", Some(42), "AG10"),
    DDR4Pin("dq", Some(43), "AG11"),
    DDR4Pin("dq", Some(44), "AH13"),
    DDR4Pin("dq", Some(45), "AG13"),
    DDR4Pin("dq", Some(46), "AJ11"),
    DDR4Pin("dq", Some(47), "AH11"),
    DDR4Pin("dq", Some(48), "AK9"),
    DDR4Pin("dq", Some(49), "AJ9"),
    DDR4Pin("dq", Some(50), "AK10"),
    DDR4Pin("dq", Some(51), "AJ10"),
    DDR4Pin("dq", Some(52), "AL12"),
    DDR4Pin("dq", Some(53), "AK12"),
    DDR4Pin("dq", Some(54), "AL10"),
    DDR4Pin("dq", Some(55), "AL11"),
    DDR4Pin("dq", Some(56), "AM8"),
    DDR4Pin("dq", Some(57), "AM9"),
    DDR4Pin("dq", Some(58), "AM10"),
    DDR4Pin("dq", Some(59), "AM11"),
    DDR4Pin("dq", Some(60), "AP11"),
    DDR4Pin("dq", Some(61), "AN11"),
    DDR4Pin("dq", Some(62), "AP9"),
    DDR4Pin("dq", Some(63), "AP10"),
    DDR4Pin("dqs_c", Some(0), "AG23"),
    DDR4Pin("dqs_c", Some(1), "AB18"),
    DDR4Pin("dqs_c", Some(2), "AK23"),
    DDR4Pin("dqs_c", Some(3), "AN21"),
    DDR4Pin("dqs_c", Some(4), "AD12"),
    DDR4Pin("dqs_c", Some(5), "AH9"),
    DDR4Pin("dqs_c", Some(6), "AL8"),
    DDR4Pin("dqs_c", Some(7), "AN8"),
    DDR4Pin("dqs_t", Some(0), "AF23"),
    DDR4Pin("dqs_t", Some(1), "AA18"),
    DDR4Pin("dqs_t", Some(2), "AK22"),
    DDR4Pin("dqs_t", Some(3), "AM21"),
    DDR4Pin("dqs_t", Some(4), "AC12"),
    DDR4Pin("dqs_t", Some(5), "AG9"),
    DDR4Pin("dqs_t", Some(6), "AK8"),
    DDR4Pin("dqs_t", Some(7), "AN9"),
    DDR4Pin("odt", Some(0), "AE15"),
    DDR4Pin("odt", Some(1), "AM16"),
    DDR4Pin("reset_n", None, "AB14", ioStandard = Some("LVCMOS12"), drive = Some(8))
  )

  override def uartPorts: Seq[UARTPortParams] = Seq(
    new UARTPortParams {
      override val portName: String = "uart2_pl"
    }
  )


  override def initNClockPorts(n: Int)(implicit bd: SOCTBdBuilder, p: Parameters): Seq[FPGAClockDomain] = {
    // CPU_RESET pushbutton (part0_pins.xml); placed via XDC by the DDR4 custom interface.
    lazy val reset = FPGAResetPort("reset", pinLoc = Some(BoardPin("M11", "LVCMOS33")))
    lazy val dom: FPGAClockDomain = FPGAClockDomain(clk, reset, 300.MHz)
    // Pin locations from the board files (part0_pins.xml: CLK_300_P/N); used by the DDR4
    // custom interface, which has to place the clock pair itself.
    lazy val clk: FPGADiffClockPort = FPGADiffClockPort("clk_300mhz", () => dom,
      pinLocs = Some(DiffClockPins(clkP = "AH18", clkN = "AH17", ioStandard = "DIFF_SSTL12")))
    n match {
      case 1 => Seq(dom)
      case _ => throw VivadoDesignException(s"ZCU104 only provides a single differential clock input. Requested $n clock domains.")
    }
  }

  override def pmod(pmodPort: Int, pmodPin: RawPMODPin): FPGAPMODPin = {
    val pin = pmodPin.pin
    val port = pmodPort match {
      case 0 => IndexedSeq(
        ("G8", "LVCMOS33"), ("H8", "LVCMOS33"), ("G7", "LVCMOS33"), ("H7", "LVCMOS33"),
        ("G6", "LVCMOS33"), ("H6", "LVCMOS33"), ("J6", "LVCMOS33"), ("J7", "LVCMOS33")
      )
      case 1 => IndexedSeq(
        ("J9", "LVCMOS33"), ("K9", "LVCMOS33"), ("K8", "LVCMOS33"), ("L8", "LVCMOS33"),
        ("L10", "LVCMOS33"), ("M10", "LVCMOS33"), ("M8", "LVCMOS33"), ("M9", "LVCMOS33")
      )
      case _ =>
        throw VivadoDesignException(s"Invalid PMOD port $pmodPort. ZCU104 only has PMOD ports 0 and 1.")
    }
    val (packagePin, ioStandard) = port(pin)
    FPGAPMODPin(packagePin, ioStandard, pin)
  }
}