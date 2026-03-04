// Scala
import ch.qos.logback.classic.{Level, LoggerContext, Logger => LBLogger}
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import chisel3.RawModule
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import org.slf4j.LoggerFactory
import soct.build.BuildInfo

package object soct {

  /**
   * Type alias for the Chisel top module, which can be either a Module or a LazyModule
   */
  type ChiselTop = Either[Class[_ <: RawModule], Class[_ <: LazyModule]]

  /**
   * Load a resource file from the classpath
   * @param fullPath The full path to the resource
   * @return Some content of the resource file, or None if not found
   */
  def getResource(fullPath: String): Option[String] = {
    val stream = getClass.getResourceAsStream(fullPath)
    if (stream != null) {
      Some(scala.io.Source.fromInputStream(stream).mkString)
    } else {
      None
    }
  }

  // Default parameters for the launcher
  val logLevels = Seq("debug", "info", "warn", "error")

  // Logger instance
  val log = com.typesafe.scalalogging.Logger("SOCT")

  // The version of the SOCT tool - prefer BuildInfo (available during sbt run/compile),
  // fall back to the JAR manifest `Implementation-Version`, otherwise "unknown"
  val version: String = {
    val bi = scala.util.Try(BuildInfo.version).getOrElse("")
    if (bi != null && bi.nonEmpty && bi != "unknown") bi
    else {
      val impl = getClass.getPackage.getImplementationVersion
      if (impl != null) impl else "unknown"
    }
  }

  def configureLogging(level: String = "DEBUG",
                       pattern: String = "[%-5level] %logger{36} - %msg%n"
                      ): Unit = {

    val factory = LoggerFactory.getILoggerFactory
    factory match {
      case context: LoggerContext =>
        context.reset() // Clears previous config (including logback.xml)

        val encoder = new PatternLayoutEncoder()
        encoder.setContext(context)
        encoder.setPattern(pattern)
        encoder.start()

        val consoleAppender = new ConsoleAppender[ILoggingEvent]()
        consoleAppender.setContext(context)
        consoleAppender.setEncoder(encoder)
        consoleAppender.start()

        val rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        rootLogger.setLevel(Level.toLevel(level, Level.INFO))
        rootLogger.addAppender(consoleAppender)

      case other =>
        // No Logback available. Avoid throwing; log a warning via println to avoid SLF4J usage.
        System.err.println(s"[WARN] Logback not found on classpath (found ${other.getClass.getName}). Skipping configuration.")
    }
  }
}
