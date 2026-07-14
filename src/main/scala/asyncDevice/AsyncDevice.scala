package explorerTL.asyncDevice

import chisel3._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange}
import freechips.rocketchip.prci.{AsynchronousCrossing, ClockBundle}
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.subsystem.CrossingWrapper
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._

class ExampleTLDevice(val base: BigInt)(implicit p: Parameters) extends LazyModule {
  val device = new SimpleDevice("Simple Device", Seq("Example TL Device"))

  val regNode = TLRegisterNode(
    address = Seq(AddressSet(base, 0xff)),
    device = device,
    beatBytes = 4,
    concurrency = 1,
  )

  lazy val module = new ExampleTLDeviceImp(this)
}

class ExampleTLDeviceImp(outer: ExampleTLDevice) extends LazyModuleImp(outer) {

  val reg1 = Reg(UInt(32.W))
  val reg2 = Reg(UInt(32.W))
  val reg3 = Reg(UInt(32.W))

  reg3 := reg2 + reg1

  outer.regNode.regmap(
    0x00 -> Seq(RegField(32, reg1)),
    0x04 -> Seq(RegField(32, reg2)),
    0x08 -> Seq(RegField(32, reg3)),
  )
}

class DUT(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(
    Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name = "MyClient-1", sourceId = IdRange(0, 3))))),
  )

  val params = AsynchronousCrossing(safe = false, narrow = true)

  val island = LazyModule(new CrossingWrapper(params))
  val tlDev  = island(LazyModule(new ExampleTLDevice(0x4000)))

  island.crossTLIn(tlDev.regNode) := node

  val tlSrc                = InModuleBody(node.makeIOs())
  override lazy val module = new DUTImp(this)
}

class DUTImp(outer: DUT) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val deviceDomain = Input(new ClockBundle)
  })
  outer.island.module.clock := io.deviceDomain.clock
  outer.island.module.reset := io.deviceDomain.reset
}
