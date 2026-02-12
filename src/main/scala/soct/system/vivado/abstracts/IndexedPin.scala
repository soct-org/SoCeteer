package soct.system.vivado.abstracts

import soct.system.vivado.XilinxDesignException

import scala.collection.mutable
import scala.reflect.ClassTag

trait HasIndexedPins {
  self: BdComp =>

  sealed trait BaseIndexedFactory {
    /** Number of instantiated indexed pins in this factory. */
    def size: Int

    /** Iterable of all instantiated indices. */
    def indices: Iterable[Int]
  }

  /** Thrown when attempting to instantiate the same indexed pin more than once. */
  class IndexedPinReinstantiationException(message: String) extends XilinxDesignException(message)

  private def validateIndexRange(idx: Int, indexRange: (Int, Int), kind: String): Unit = {
    require(
      idx >= indexRange._1 && idx <= indexRange._2,
      s"$kind index must be between ${indexRange._1} and ${indexRange._2}, got $idx"
    )
  }

  private def reinstantiationError(kind: String, idx: Int): String = {
    s"$kind at index $idx has already been instantiated in component '${self.instanceName}'.\n" +
      s"Each index can only be created once. Store the result in a val and reuse it or use the get/contains methods to check for existing pins."
  }

  /**
   * Generic indexed factory that works for any BdPin/BdIntfPin type.
   *
   * @tparam T The pin or interface type being created
   * @tparam D Optional data type needed for construction
   */
  protected class IndexedPinFactory[T: ClassTag, D](
                                                     indexRange: (Int, Int),
                                                     pinConstructor: (Int, D) => T
                                                   ) extends BaseIndexedFactory {
    private val cache: mutable.Map[Int, T] = mutable.Map.empty
    private val kind: String = implicitly[ClassTag[T]].runtimeClass.getSimpleName

    /** Create or retrieve the indexed pin at the given index. */
    def apply(idx: Int, data: D): T = {
      validateIndexRange(idx, indexRange, kind)

      cache.get(idx) match {
        case Some(_) =>
          throw new IndexedPinReinstantiationException(reinstantiationError(kind, idx))
        case None =>
          val pin = pinConstructor(idx, data)
          cache += idx -> pin
          pin
      }
    }

    /** Return all instantiated pins keyed by index. */
    def all: Map[Int, T] = cache.toMap

    /** Number of instantiated pins. */
    def size: Int = cache.size

    /** Iterable of all instantiated indices. */
    def indices: Iterable[Int] = cache.keys

    /**
     * Get or initialize a pin at the given index.
     * If a pin at this index has already been instantiated, it is returned and the provided data is ignored.
     * Otherwise, a new pin is created using the provided data and returned.
     *
     * @param idx The index of the pin to get or initialize
     * @param data The data to use for pin construction if initialization is needed
     * @return If a pin at this index has already been instantiated, it is returned. Otherwise, a new pin is created and returned.
     */
    def getOrElseInit(idx: Int, data: => D): T = {
      validateIndexRange(idx, indexRange, kind)

      cache.getOrElse(idx, {
        val pin = pinConstructor(idx, data)
        cache += idx -> pin
        pin
      })
    }

    /** Get a pin if it has been instantiated. */
    def get(idx: Int): Option[T] = cache.get(idx)

    /** Check whether a pin at this index has been instantiated. */
    def contains(idx: Int): Boolean = cache.contains(idx)
  }

  /**
   * No-data version for simple pins.
   */
  protected class SimpleIndexedPinFactory[T: ClassTag](
                                                        indexRange: (Int, Int),
                                                        pinConstructor: Int => T
                                                      ) extends BaseIndexedFactory {
    private val cache: mutable.Map[Int, T] = mutable.Map.empty
    private val kind: String = implicitly[ClassTag[T]].runtimeClass.getSimpleName

    /** Create or retrieve the indexed pin at the given index. */
    def apply(idx: Int): T = {
      validateIndexRange(idx, indexRange, kind)

      cache.get(idx) match {
        case Some(_) =>
          throw new IndexedPinReinstantiationException(reinstantiationError(kind, idx))
        case None =>
          val pin = pinConstructor(idx)
          cache += idx -> pin
          pin
      }
    }

    /** Return all instantiated pins keyed by index. */
    def all: Map[Int, T] = cache.toMap

    /** Number of instantiated pins. */
    def size: Int = cache.size

    /** Iterable of all instantiated indices. */
    def indices: Iterable[Int] = cache.keys

    /**
     * Get or initialize a pin at the given index.
     * @param idx The index of the pin to get or initialize
     * @return If a pin at this index has already been instantiated, it is returned. Otherwise, a new pin is created and returned.
     */
    def getOrElseInit(idx: Int): T = {
      validateIndexRange(idx, indexRange, kind)

      cache.getOrElse(idx, {
        val pin = pinConstructor(idx)
        cache += idx -> pin
        pin
      })
    }

    /** Get a pin if it has been instantiated. */
    def get(idx: Int): Option[T] = cache.get(idx)

    /** Check whether a pin at this index has been instantiated. */
    def contains(idx: Int): Boolean = cache.contains(idx)
  }
}