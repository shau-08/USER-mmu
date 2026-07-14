package explorerTL.point2point

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}

class MyClient1(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(
    Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name = "MyClient-1", sourceId = IdRange(0, 256))))),
  )
  val tlSrc                = InModuleBody(node.makeIOs())
  override lazy val module = new LazyModuleImp(this) {}
}

class MyManager1(implicit p: Parameters) extends LazyModule {
  val node = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        Seq(
          TLSlaveParameters.v1(
            address = Seq(AddressSet(0x0, BigInt("FFFFFFFFFFFFFFFF", 16))),
            regionType = RegionType.IDEMPOTENT,
            supportsGet = TransferSizes(1, 64),
            supportsPutFull = TransferSizes(1, 64),
            supportsPutPartial = TransferSizes(1, 64),
          ),
        ),
        beatBytes = 16,
        minLatency = 1,
      ),
    ),
  )

  val tlSink               = InModuleBody(node.makeIOs())
  override lazy val module = new LazyModuleImp(this) {}
}

class Point2Point(implicit p: Parameters) extends LazyModule {
  val client1  = LazyModule(new MyClient1)
  val manager1 = LazyModule(new MyManager1)

  manager1.node := client1.node
  override lazy val module = new Point2PointImp(this)
}

//TLA = {b.opcode, b.param, b.size,b.source,b.address, b.mask, b.data, b.corrupt}
//TLD = {b.opcode, b.param, b.size, b.source, b.sink, b.denied, b.data, b.corrupt}

class SwitchBoardTLBundleA extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(3.W)
  val size    = UInt(4.W)
  val source  = UInt(8.W)
  val mask    = UInt(8.W)
  val corrupt = Bool()
  val address = UInt(64.W)
  val data    = UInt(64.W)
}

class SwitchBoardTLBundleD extends Bundle {
  val opcode  = UInt(3.W)
  val param   = UInt(2.W)
  val size    = UInt(4.W)
  val source  = UInt(8.W)
  val sink    = UInt(8.W)
  val denied  = Bool()
  val corrupt = Bool()
  val data    = UInt(64.W)
}

class Point2PointImp(outer: Point2Point) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val in_a = Flipped(Decoupled(UInt((new SwitchBoardTLBundleA).getWidth.W)))
    val in_d = Decoupled(UInt((new SwitchBoardTLBundleD).getWidth.W))

    val out_a = Decoupled(UInt((new SwitchBoardTLBundleA).getWidth.W))
    val out_d = Flipped(Decoupled(UInt((new SwitchBoardTLBundleD).getWidth.W)))
  })

  val in_a_bits = io.in_a.bits.asTypeOf(new SwitchBoardTLBundleA)
  outer.client1.tlSrc(0).a.valid        := io.in_a.valid
  outer.client1.tlSrc(0).a.bits         := DontCare
  outer.client1.tlSrc(0).a.bits.opcode  := in_a_bits.opcode
  outer.client1.tlSrc(0).a.bits.param   := in_a_bits.param
  outer.client1.tlSrc(0).a.bits.size    := in_a_bits.size
  outer.client1.tlSrc(0).a.bits.source  := in_a_bits.source
  outer.client1.tlSrc(0).a.bits.mask    := in_a_bits.mask
  outer.client1.tlSrc(0).a.bits.address := in_a_bits.address
  outer.client1.tlSrc(0).a.bits.data    := in_a_bits.data
  outer.client1.tlSrc(0).a.bits.corrupt := in_a_bits.corrupt
  io.in_a.ready                         := outer.client1.tlSrc(0).a.ready

  val in_d_bits = Wire(new SwitchBoardTLBundleD)
  io.in_d.valid                  := outer.client1.tlSrc(0).d.valid
  in_d_bits.opcode               := outer.client1.tlSrc(0).d.bits.opcode
  in_d_bits.param                := outer.client1.tlSrc(0).d.bits.param
  in_d_bits.size                 := outer.client1.tlSrc(0).d.bits.size
  in_d_bits.source               := outer.client1.tlSrc(0).d.bits.source
  in_d_bits.sink                 := outer.client1.tlSrc(0).d.bits.sink
  in_d_bits.denied               := outer.client1.tlSrc(0).d.bits.denied
  in_d_bits.data                 := outer.client1.tlSrc(0).d.bits.data
  in_d_bits.corrupt              := outer.client1.tlSrc(0).d.bits.corrupt
  outer.client1.tlSrc(0).d.ready := io.in_d.ready
  io.in_d.bits                   := in_d_bits.asUInt

  val out_a_bits = Wire(new SwitchBoardTLBundleA)
  io.out_a.valid                   := outer.manager1.tlSink(0).a.valid
  out_a_bits.opcode                := outer.manager1.tlSink(0).a.bits.opcode
  out_a_bits.param                 := outer.manager1.tlSink(0).a.bits.param
  out_a_bits.size                  := outer.manager1.tlSink(0).a.bits.size
  out_a_bits.source                := outer.manager1.tlSink(0).a.bits.source
  out_a_bits.mask                  := outer.manager1.tlSink(0).a.bits.mask
  out_a_bits.address               := outer.manager1.tlSink(0).a.bits.address
  out_a_bits.data                  := outer.manager1.tlSink(0).a.bits.data
  out_a_bits.corrupt               := outer.manager1.tlSink(0).a.bits.corrupt
  outer.manager1.tlSink(0).a.ready := io.out_a.ready
  io.out_a.bits                    := out_a_bits.asUInt

  val out_d_bits = io.out_d.bits.asTypeOf(new SwitchBoardTLBundleD)
  outer.manager1.tlSink(0).d.valid        := io.out_d.valid
  outer.manager1.tlSink(0).d.bits.opcode  := out_d_bits.opcode
  outer.manager1.tlSink(0).d.bits.param   := out_d_bits.param
  outer.manager1.tlSink(0).d.bits.size    := out_d_bits.size
  outer.manager1.tlSink(0).d.bits.source  := out_d_bits.source
  outer.manager1.tlSink(0).d.bits.sink    := out_d_bits.sink
  outer.manager1.tlSink(0).d.bits.denied  := out_d_bits.denied
  outer.manager1.tlSink(0).d.bits.data    := out_d_bits.data
  outer.manager1.tlSink(0).d.bits.corrupt := out_d_bits.corrupt
  io.out_d.ready                          := outer.manager1.tlSink(0).d.ready
}
