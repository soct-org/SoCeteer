// Scala
import ch.qos.logback.classic.{Level, Logger => LBLogger, LoggerContext}
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import org.slf4j.LoggerFactory

package object soct {

  val log = com.typesafe.scalalogging.Logger("SOCT")

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
