package soct.system.vivado

import soct.system.vivado.abstracts.{BdBaseComp, BdPinPort}

import scala.collection.mutable.ListBuffer
import scala.collection.{View, mutable}

class SOCTBd {

  var locked = false // To prevent further modifications after finalization

  var inFinalization = false // To prevent recursive finalization

  // Set of all components in the block design
  protected val components: mutable.LinkedHashSet[BdBaseComp] = mutable.LinkedHashSet.empty[BdBaseComp]

  protected val connects: mutable.LinkedHashMap[BdPinPort, ListBuffer[BdPinPort]] = mutable.LinkedHashMap.empty

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
    if (locked) throw XilinxDesignException("Cannot remove connections after finalization")

    toOpt match {
      case None =>
        connects.remove(from)

      case Some(to) =>
        connects.get(from) match {
          case None => ()
          case Some(buf) =>
            buf.filterInPlace(_ != to)
            if (buf.isEmpty) connects.remove(from)
        }
    }
  }

  /**
   * Get all connections that satisfy a given property
   *
   * @param prop The property function to filter connections
   * @return A map of connections that satisfy the property
   */
  def connectsWithProperty(prop: (BdPinPort, Iterable[BdPinPort]) => Boolean): Map[BdPinPort, Seq[BdPinPort]] = {
    connects.iterator
      .filter { case (from, sinks) => prop(from, sinks) }
      .map { case (from, sinks) => from -> sinks.toSeq }
      .toMap
  }

  /**
   * Get all connections that satisfy a given property as a view
   *
   * @param prop The property function to filter connections
   * @return A view of connections that satisfy the property
   */
  def connectsWithPropertyView(prop: (BdPinPort, Iterable[BdPinPort]) => Boolean): View[(BdPinPort, View[BdPinPort])] = {
    connects.view
      .filter { case (from, sinks) => prop(from, sinks) }
      .map { case (from, sinks) => from -> sinks.view }
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
    if (locked) throw XilinxDesignException("Cannot add connections after finalization")
    val buf = connects.getOrElseUpdate(from, ListBuffer.empty[BdPinPort])
    buf += to
  }

  /**
   * Get all connectors (sources and sinks) for a given port
   *
   * @param port The port to get connectors for
   * @return A view of all connectors (sources and sinks) for the given port
   */
  def getConnectorsView(port: BdPinPort): View[BdPinPort] = {
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
   * Get all connectors (sources and sinks) for a given port as a snapshot
   *
   * @param port The port to get connectors for
   * @return A sequence of all connectors (sources and sinks) for the given port
   */
  def getConnectors(port: BdPinPort): Seq[BdPinPort] = getConnectorsView(port).toSeq

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
   * Get all sink ports connected to a given source port as a view
   *
   * @param source The source port
   * @return A view of all sink ports connected to the source port
   */
  def getSinksView(source: BdPinPort): View[BdPinPort] =
    connects.get(source).map(_.view).getOrElse(View.empty)


  /**
   * Get all sink ports connected to a given source port as a snapshot
   * @param source The source port
   * @return A sequence of all sink ports connected to the source port
   */
  def getSinks(source: BdPinPort): Seq[BdPinPort] =
    connects.get(source).map(_.toSeq).getOrElse(Seq.empty)

  /**
   * Get the source port connected to a given sink port
   *
   * @param sink The sink port
   * @return An optional source port connected to the sink port
   */
  def getSource(sink: BdPinPort): Option[BdPinPort] = {
    // throw warning if multiple sources found - should not happen in well-formed designs
    val sources = connects.iterator.collect {
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