package explorerTL.serdes

import chisel3._
import chisel3.util.DecoupledIO
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
import freechips.rocketchip.tilelink.{
  TLMasterParameters,
  TLMasterPortParameters,
  TLSlaveParameters,
  TLSlavePortParameters,
}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import testchipip.serdes.{CreditedSourceSyncPhitIO, CreditedSourceSyncSerialPhyParams, Flit, TLSerdesser}

class TLSerial(
  val phitWidth:     Int,
  clientPortParams:  Option[TLMasterPortParameters],
  managerPortParams: Option[TLSlavePortParameters],
)(
  implicit p: Parameters,
) extends LazyModule {
  val flitWidth = managerPortParams.get.beatBytes * 8
  val serdes = LazyModule(
    new TLSerdesser(
      256,
      clientPortParams,
      managerPortParams,
    ),
  )
  lazy val module = new TLSerialImp(this)
}

class TLSerialImp(outer: TLSerial) extends LazyModuleImp(outer) {
  val phyParams =
    CreditedSourceSyncSerialPhyParams(phitWidth = outer.phitWidth, flitWidth = outer.flitWidth, freqMHz = 100)

  val io = IO(new CreditedSourceSyncPhitIO(phyParams.phitWidth))

  val serPhy = Module(new SerdesPhyImp(phyParams))

  def bluckConnectDecoupledFlit2UInt(flit: DecoupledIO[Flit], d: DecoupledIO[UInt]): Unit = {
    d.valid    := flit.valid
    d.bits     := flit.bits.flit
    flit.ready := d.ready
  }

  def bluckConnectDecoupledUInt2Flit(d: DecoupledIO[UInt], flit: DecoupledIO[Flit]): Unit = {
    flit.valid     := d.valid
    flit.bits.flit := d.bits
    d.ready        := flit.ready
  }

  // TL Channel E
  outer.serdes.module.io.ser(0).in.valid  := false.B
  outer.serdes.module.io.ser(0).in.bits   := DontCare
  outer.serdes.module.io.ser(0).out.ready := true.B

  // TL Channel C
  outer.serdes.module.io.ser(2).in.valid  := false.B
  outer.serdes.module.io.ser(2).in.bits   := DontCare
  outer.serdes.module.io.ser(2).out.ready := true.B

  // TL Channel B
  outer.serdes.module.io.ser(3).in.valid  := false.B
  outer.serdes.module.io.ser(3).in.bits   := DontCare
  outer.serdes.module.io.ser(3).out.ready := true.B

  serPhy.inner_ser(0).in <> outer.serdes.module.io.ser(4).in   // TL-Client-A
  serPhy.inner_ser(0).out <> outer.serdes.module.io.ser(1).out // TL-ClientD

  serPhy.inner_ser(1).in <> outer.serdes.module.io.ser(1).in   // TL-Manager-D
  serPhy.inner_ser(1).out <> outer.serdes.module.io.ser(4).out // TL-Manager-A

  io <> serPhy.io
}

class TLSerialLoopBack(implicit p: Parameters) extends LazyModule {

  val phitWidth = 32

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

  val tlSer = LazyModule(
    new TLSerial(
      phitWidth = phitWidth,
      clientPortParams = Some(clientPortParams),
      managerPortParams = Some(managerPortParams),
    ),
  )

  tlSer.serdes.managerNode.get := tlSer.serdes.clientNode.get

  lazy val module = new TLSerialLoopBackImp(this)
}

class TLSerialLoopBackImp(outer: TLSerialLoopBack) extends LazyModuleImp(outer) {
  val io = IO(new CreditedSourceSyncPhitIO(outer.phitWidth))

  io <> outer.tlSer.module.io
}
