package soct.system.vivado

import soct.system.vivado.abstracts.{BdBaseComp, BdPinPort}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.View
import scala.reflect.ClassTag

/**
 * Mutable directed multigraph:
 *   - Nodes: BdBaseComp
 *   - Vertices/ports: BdPinPort (edges connect ports)
 *
 * Edges are directed: from -> to
 * Multiple edges between same (from,to) are allowed (Vivado permits multiple sinks).
 *
 * Internals:
 * outAdj: fromPort -> ordered sinks (ListBuffer)
 * inAdj : toPort   -> ordered sources (ListBuffer)
 *
 * NOTE: Views are LIVE (reflect future mutations). Use snapshot methods when you need stability.
 */
class SOCTBd {

  // ----------------------------
  // Lifecycle state
  // ----------------------------

  var locked: Boolean = false // Prevent modifications after finalization
  var inFinalization: Boolean = false // Prevent recursive finalization

  // ----------------------------
  // Nodes / components (insertion-ordered)
  // ----------------------------

  protected val nodes: mutable.LinkedHashSet[BdBaseComp] =
    mutable.LinkedHashSet.empty[BdBaseComp]

  // ----------------------------
  // Edges / connections (ports graph)
  // ----------------------------

  // from -> sinks
  protected val outAdj: mutable.LinkedHashMap[BdPinPort, ListBuffer[BdPinPort]] =
    mutable.LinkedHashMap.empty

  // to -> sources
  protected val inAdj: mutable.LinkedHashMap[BdPinPort, ListBuffer[BdPinPort]] =
    mutable.LinkedHashMap.empty

  // ----------------------------
  // Guards
  // ----------------------------

  @inline protected final def requireUnlocked(msg: String): Unit =
    if (locked) throw XilinxDesignException(msg)

  // ----------------------------
  // Node API
  // ----------------------------

  /** All nodes/components (LIVE view). */
  def nodesView: View[BdBaseComp] = nodes.view

  /** All nodes/components (snapshot). */
  def nodesSnapshot: Seq[BdBaseComp] = nodes.toSeq

  /** Add a node/component (idempotent). */
  def addNode(n: BdBaseComp): Unit = {
    requireUnlocked("Cannot add components after finalization")
    nodes += n
  }

  /**
   * Count instances of a given BdBaseComp runtime class, excluding 'inst'.
   * Used for stable indexing.
   */
  def countInstancesOf[T <: BdBaseComp](inst: T): Int = {
    val cls = inst.getClass
    nodes.count(n => cls.isInstance(n) && (n ne inst))
  }

  // ----------------------------
  // Edge primitives
  // ----------------------------

  /** Connect directed edge from -> to (multigraph: does not dedupe). */
  def addEdge(from: BdPinPort, to: BdPinPort): Unit = {
    requireUnlocked("Cannot add connections after finalization")

    // out adjacency
    val outs = outAdj.getOrElseUpdate(from, ListBuffer.empty[BdPinPort])
    outs += to

    // in adjacency (reverse index)
    val ins = inAdj.getOrElseUpdate(to, ListBuffer.empty[BdPinPort])
    ins += from
  }

  /** True if at least one edge from -> to exists. */
  def hasEdge(from: BdPinPort, to: BdPinPort): Boolean =
    outAdj.get(from).exists(_.contains(to))

  /** Number of outgoing edges from a port (out-degree). */
  def outDegree(from: BdPinPort): Int =
    outAdj.get(from).fold(0)(_.size)

  /** Number of incoming edges to a port (in-degree). */
  def inDegree(to: BdPinPort): Int =
    inAdj.get(to).fold(0)(_.size)

  // ----------------------------
  // Edge removal
  // ----------------------------

  /**
   * Remove edges.
   *  - If toOpt is None: remove all outgoing edges from 'from'
   *  - If toOpt is Some(to): remove all edges from 'from' to 'to' (all duplicates)
   */
  def disconnect(from: BdPinPort, toOpt: Option[BdPinPort] = None): Unit = {
    requireUnlocked("Cannot remove connections after finalization")

    toOpt match {
      case None =>
        // remove all outgoing edges from 'from'
        outAdj.remove(from).foreach { sinks =>
          // for each sink, remove 'from' from inAdj(sink) as many times as it occurred
          sinks.foreach { to =>
            val insOpt = inAdj.get(to)
            insOpt.foreach { ins =>
              // remove ONE occurrence each time we saw an edge
              val idx = ins.indexOf(from)
              if (idx >= 0) ins.remove(idx)
              if (ins.isEmpty) inAdj.remove(to)
            }
          }
        }

      case Some(to) =>
        // remove all edges from -> to (including duplicates)
        outAdj.get(from) match {
          case None => ()
          case Some(outs) =>
            // count occurrences first
            val k = outs.count(_ == to)
            if (k == 0) return

            // remove from out adjacency
            outs.filterInPlace(_ != to)
            if (outs.isEmpty) outAdj.remove(from)

            // remove k occurrences from reverse adjacency
            inAdj.get(to).foreach { ins =>
              var remaining = k
              while (remaining > 0) {
                val idx = ins.indexOf(from)
                if (idx < 0) remaining = 0
                else {
                  ins.remove(idx)
                  remaining -= 1
                }
              }
              if (ins.isEmpty) inAdj.remove(to)
            }
        }
    }
  }

  /** Backward compatible name (your old API). */
  final def removeConnection(from: BdPinPort, toOpt: Option[BdPinPort] = None): Unit =
    disconnect(from, toOpt)

  // ----------------------------
  // Adjacency access (successors/predecessors)
  // ----------------------------

  /** Outgoing neighbors as LIVE view. */
  def successorsView(from: BdPinPort): View[BdPinPort] =
    outAdj.get(from).map(_.view).getOrElse(View.empty)

  /** Outgoing neighbors snapshot. */
  def successors(from: BdPinPort): Seq[BdPinPort] =
    outAdj.get(from).map(_.toSeq).getOrElse(Seq.empty)

  /** Incoming neighbors as LIVE view. */
  def predecessorsView(to: BdPinPort): View[BdPinPort] =
    inAdj.get(to).map(_.view).getOrElse(View.empty)

  /** Incoming neighbors snapshot. */
  def predecessors(to: BdPinPort): Seq[BdPinPort] =
    inAdj.get(to).map(_.toSeq).getOrElse(Seq.empty)

  final def getSinks(source: BdPinPort): Seq[BdPinPort] = successors(source)

  // ----------------------------
  // Connector queries (union of in+out)
  // ----------------------------

  /** All connectors (in + out) as LIVE view (duplicates preserved). */
  def connectorsView(p: BdPinPort): View[BdPinPort] =
    successorsView(p) ++ predecessorsView(p)

  /** All connectors (in + out) snapshot (duplicates preserved). */
  def connectors(p: BdPinPort): Seq[BdPinPort] =
    connectorsView(p).toSeq

  /**
   * If you expect exactly one connector (optionally matching a predicate).
   * Uses snapshot for safety.
   */
  def singleConnector(port: BdPinPort, pred: BdPinPort => Boolean = _ => true, errorMsg: Option[String] = None): BdPinPort = {
    val msg = errorMsg.getOrElse(
      s"Expected exactly one connector for port $port satisfying predicate, but found a different number."
    )
    val xs = connectors(port).filter(pred)
    xs match {
      case Seq(one) => one
      case _ => throw XilinxDesignException(msg)
    }
  }

  /**
   * Source lookup:
   * If multiple sources exist, warns and returns the first in insertion order.
   * This is now O(in-degree) instead of O(E).
   */
  def sourceOf(sink: BdPinPort): Option[BdPinPort] = {
    val srcs = inAdj.get(sink).map(_.toSeq).getOrElse(Seq.empty)
    if (srcs.size > 1) {
      soct.log.warn(s"Multiple sources found for sink $sink: ${srcs.mkString(", ")}")
    }
    srcs.headOption
  }

  // ----------------------------
  // Edge iteration (good for emitters)
  // ----------------------------

  /** LIVE view of all edges (from, to) preserving insertion order and duplicates. */
  def edgesView: View[(BdPinPort, BdPinPort)] =
    outAdj.view.flatMap { case (from, tos) => tos.view.map(to => (from, to)) }

  /** Snapshot of all edges. */
  def edges: Seq[(BdPinPort, BdPinPort)] = edgesView.toSeq

  // ----------------------------
  // Filtering / query helpers
  // ----------------------------

  /**
   * Filter edges by a predicate; returns snapshot Map(from -> sinks) (stable, safe).
   * Predicate sees the LIVE sinks buffer via Iterable but result snapshots it.
   */
  def edgesWhere(prop: (BdPinPort, Iterable[BdPinPort]) => Boolean): Map[BdPinPort, Seq[BdPinPort]] =
    outAdj.iterator
      .filter { case (from, tos) => prop(from, tos) }
      .map { case (from, tos) => from -> tos.toSeq }
      .toMap

  /**
   * Filter edges by a predicate; returns LIVE view (fast, but live).
   */
  def edgesWhereView(prop: (BdPinPort, Iterable[BdPinPort]) => Boolean): View[(BdPinPort, View[BdPinPort])] =
    outAdj.view
      .filter { case (from, tos) => prop(from, tos) }
      .map { case (from, tos) => from -> tos.view }


  /**
   * All BdPinPort (from + to) as snapshot (duplicates removed).
   *
   * @return
   */
  def pinPortsSnapshot: Seq[BdPinPort] =
    (outAdj.keysIterator ++ outAdj.valuesIterator.flatten ++
      inAdj.keysIterator ++ inAdj.valuesIterator.flatten).toSeq


  /**
   * All ports of type T as snapshot.
   *
   * @param pred Optional predicate to filter ports.
   * @tparam T Type of port
   * @return A snapshot sequence of ports of type T.
   */
  def pinPortsOfTWhere[T <: BdPinPort : ClassTag](pred: T => Boolean): Seq[T] =
    pinPortsSnapshot.collect { case p: T if pred(p) => p }


  /**
   * Convenience: all outgoing edges from a given port as snapshot pairs.
   */
  def outEdges(from: BdPinPort): Seq[(BdPinPort, BdPinPort)] =
    successors(from).map(to => (from, to))

  /**
   * Convenience: all incoming edges to a given port as snapshot pairs.
   */
  def inEdges(to: BdPinPort): Seq[(BdPinPort, BdPinPort)] =
    predecessors(to).map(from => (from, to))

  // ----------------------------
  // Maintenance / diagnostics
  // ----------------------------

  /** Total number of edges (including duplicates). */
  def edgeCount: Int = outAdj.valuesIterator.map(_.size).sum

  /** Clear all connections (keeps nodes). */
  def clearEdges(): Unit = {
    requireUnlocked("Cannot clear connections after finalization")
    outAdj.clear()
    inAdj.clear()
  }

  /** Clear everything. */
  def clearAll(): Unit = {
    requireUnlocked("Cannot clear after finalization")
    nodes.clear()
    clearEdges()
  }
}
