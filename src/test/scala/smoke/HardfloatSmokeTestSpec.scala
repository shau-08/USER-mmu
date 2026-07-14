package smoke

import testproj.HardfloatSmokeTest
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class HardfloatSmokeTestSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "HardfloatSmokeTest"

  it should "elaborate and run without error" in {
    test(new HardfloatSmokeTest(8, 24)) { c =>
      c.io.a.poke(0.U)
      c.io.b.poke(0.U)
      c.io.roundingMode.poke(0.U)
      c.clock.step(1)
      // Just confirming it elaborates and runs a cycle without crashing --
      // not asserting a specific numeric result here.
    }
  }
}
