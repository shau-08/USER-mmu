package explorerTL.serdes

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import testchipip.serdes.TLSerdesser

class SerdesLoopBack(implicit p: Parameters) extends LazyModule {

  val clientPortParams =
    TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name = "MyClient-1", sourceId = IdRange(0, 256))))

  val managerPortParams = TLSlavePortParameters.v1(
    Seq(
      TLSlaveParameters.v1(
        address = Seq(AddressSet(0L, BigInt("ffffffffffffffff", 16))),
        regionType = RegionType.IDEMPOTENT,
        supportsGet = TransferSizes(1, 8),
        supportsPutFull = TransferSizes(1, 8),
        supportsPutPartial = TransferSizes(1, 8),
        supportsArithmetic = TransferSizes(4, 4),
      ),
    ),
    beatBytes = 8,
    minLatency = 1,
  )
  val serdes = LazyModule(
    new TLSerdesser(
      flitWidth = 256,
      clientPortParams = Some(clientPortParams),
      managerPortParams = Some(managerPortParams),
    ),
  )

  serdes.managerNode.get := serdes.clientNode.get
  override lazy val module = new SerdesLoopBackImp(this)
}

class SerdesLoopBackImp(outer: SerdesLoopBack) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val out = new Bundle {
      val chanA = Decoupled(UInt(256.W))
      val chanD = Flipped(Decoupled(UInt(256.W)))
    }

    val in = new Bundle {
      val chanA = Flipped(Decoupled(UInt(256.W)))
      val chanD = Decoupled(UInt(256.W))
    }
  })

  // TL Channel E
  outer.serdes.module.io.ser(0).in.valid  := false.B
  outer.serdes.module.io.ser(0).in.bits   := DontCare
  outer.serdes.module.io.ser(0).out.ready := true.B

  // TL Channel D
  outer.serdes.module.io.ser(1).in.valid     := io.out.chanD.valid
  outer.serdes.module.io.ser(1).in.bits.flit := io.out.chanD.bits
  io.out.chanD.ready                         := outer.serdes.module.io.ser(1).in.ready

  io.in.chanD.valid                       := outer.serdes.module.io.ser(1).out.valid
  io.in.chanD.bits                        := outer.serdes.module.io.ser(1).out.bits.flit
  outer.serdes.module.io.ser(1).out.ready := io.in.chanD.ready

  // TL Channel C
  outer.serdes.module.io.ser(2).in.valid  := false.B
  outer.serdes.module.io.ser(2).in.bits   := DontCare
  outer.serdes.module.io.ser(2).out.ready := true.B

  // TL Channel B
  outer.serdes.module.io.ser(3).in.valid  := false.B
  outer.serdes.module.io.ser(3).in.bits   := DontCare
  outer.serdes.module.io.ser(3).out.ready := true.B

  // TL Channel A
  outer.serdes.module.io.ser(4).in.valid     := io.in.chanA.valid
  outer.serdes.module.io.ser(4).in.bits.flit := io.in.chanA.bits
  io.in.chanA.ready                          := outer.serdes.module.io.ser(4).in.ready

  io.out.chanA.valid                      := outer.serdes.module.io.ser(4).out.valid
  io.out.chanA.bits                       := outer.serdes.module.io.ser(4).out.bits.flit
  outer.serdes.module.io.ser(4).out.ready := io.out.chanA.ready
}
