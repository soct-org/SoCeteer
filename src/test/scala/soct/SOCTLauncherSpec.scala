package soct

import org.scalatest.flatspec.AnyFlatSpec

class SOCTLauncherSpec extends AnyFlatSpec {

  "SOCTLauncher" should "have a version" in {
    assert(soct.version.nonEmpty)
    val args = Seq("--version")
    // Run SOCTLauncher.main(args.toArray) and capture output
    val output = new java.io.ByteArrayOutputStream()
    Console.withOut(output) {
      scala.util.Try {
        SOCTLauncher.main(args.toArray)
      }
    }
    val outputStr = output.toString
    assert(outputStr.contains(soct.version))
  }
}
