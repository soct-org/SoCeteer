package soct.xilinx.components

import org.chipsalliance.cde.config.Parameters


trait Component {
  def add()(implicit p: Parameters): Unit
}

trait OutputComponent extends Component{

}

trait RequiresInputComponents {
  val inputComponents: Seq[OutputComponent]
}

trait RequiresBoardOutputPins {
  val boardOutputPinNames: Seq[String]
}

abstract class XilinxIPComponent extends Component {
  val partName: String
}
