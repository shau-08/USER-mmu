package explorerTL.regNode

import chisel3._
import explorerTL.tilelinkSwitchboard.{SwitchboardTLAdapter, TLClientPortParams}
import freechips.rocketchip.diplomacy.{AddressSet, IdRange}
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}
class ExampleDevice(val base: BigInt, val beatBytes: Int)(implicit p: Parameters) extends LazyModule {

  val device = new SimpleDevice("My Device", Seq("Toy Device"))

  val regNode = TLRegisterNode(
    address = Seq(AddressSet(base, 0xfff)),
    device = device,
    beatBytes = beatBytes,
    concurrency = 2,
  )

  lazy val module = new ExampleDeviceImp(this)
}

class ExampleDeviceImp(outer: ExampleDevice) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val big    = Output(UInt(64.W))
    val medium = Output(UInt(32.W))
    val small  = Output(UInt(16.W))
    val tiny0  = Output(UInt(4.W))
    val tiny1  = Output(UInt(4.W))
  })

  val bigReg    = RegInit(0.U(64.W))
  val mediumReg = RegInit(0.U(32.W))
  val smallReg  = RegInit(0.U(16.W))

  val tinyReg0 = RegInit(0.U(4.W))
  val tinyReg1 = RegInit(0.U(4.W))

  outer.regNode.regmap(
    0x00 -> Seq(RegField(64, readBig(_), writeBig(_, _, _))),
    0x08 -> Seq(RegField(32, readMedium(_, _), writeMedium(_, _, _))),
    0x0c -> Seq(RegField(16, smallReg)),
    0x0e -> Seq(RegField(4, tinyReg0), RegField(4, tinyReg1)),
  )

  def readBig(@annotation.unused oready: Bool): (Bool, UInt) = (true.B, bigReg)

  def writeBig(ivalid: Bool, oready: Bool, ibits: UInt): (Bool, Bool) = {
    when(ivalid) {
      printf(cf"0x00: Write 64-bit Big out-ready:${oready}\n")
      bigReg := ibits
    }
    (true.B, true.B)
  }

  def readMedium(ivalid: Bool, oready: Bool): (Bool, Bool, UInt) = {
    when(ivalid) {
      printf(cf"0x08: Read 32-bit Medium out-ready:${oready}\n")
    }
    (true.B, true.B, mediumReg)
  }

  def writeMedium(ivalid: Bool, oready: Bool, ibits: UInt): (Bool, Bool) = {
    when(ivalid) {
      printf(cf"0x08: Write 32-bit Medium out-ready:${oready}\n")
      mediumReg := ibits
    }
    (true.B, true.B)
  }

  io.big    := bigReg
  io.medium := mediumReg
  io.small  := smallReg
  io.tiny0  := tinyReg0
  io.tiny1  := tinyReg1
}

class SmoketestRegNode(implicit p: Parameters) extends LazyModule with SwitchboardTLAdapter {

  override val nClientParams  = Seq(TLClientPortParams(idBits = 4))
  override val nManagerParams = Seq.empty
  val device                  = LazyModule(new ExampleDevice(base = 0, beatBytes = 32))
  device.regNode := clients(0)
}

class DUT()(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "regNode interface",
            sourceId = IdRange(0, 4),
          ),
        ),
      ),
    ),
  )
  val in = InModuleBody(node.makeIOs())

  val device = LazyModule(new ExampleDevice(base = 0, beatBytes = 16))

  device.regNode := node

  override lazy val module = new DUTImp(this)
}

class DUTImp(outer: DUT) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val big    = Output(UInt(64.W))
    val medium = Output(UInt(32.W))
    val small  = Output(UInt(16.W))
    val tiny0  = Output(UInt(4.W))
    val tiny1  = Output(UInt(4.W))
  })

  io := outer.device.module.io
}
