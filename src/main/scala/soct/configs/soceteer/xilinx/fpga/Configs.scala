package soct.xilinx

import org.chipsalliance.cde.config.{Config, Field}


abstract class FPGA {

}


/**
 * Field to indicate whether the design runs on a Xilinx FPGA.
 */
case object RunsOnXilinxFPGA extends Field[Option[FPGA]](None)



class WithXilinxFPGA() extends Config((_, _, _) => {
  case HasDDR4ExtMem => true
}
)