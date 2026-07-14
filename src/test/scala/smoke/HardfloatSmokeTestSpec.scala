package smoke

import chisel3._
import circt.stage.ChiselStage
import testproj.HardfloatSmokeTest
import org.scalatest.flatspec.AnyFlatSpec

class HardfloatSmokeTestSpec extends AnyFlatSpec {
  behavior of "HardfloatSmokeTest"

  it should "elaborate without error" in {
    ChiselStage.elaborate(new HardfloatSmokeTest(8, 24))
  }
}
