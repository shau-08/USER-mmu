package explorerTL.serdes

import chisel3._
import freechips.rocketchip.util.ResetCatchAndSync
import testchipip.serdes.{CreditedSerialPhy, DecoupledFlitIO, SerialPhyParams, SourceSyncPhitIO}

class SerdesPhyImp(serPhyParams: SerialPhyParams) extends Module {
  val io = IO(new SourceSyncPhitIO(serPhyParams.phitWidth))

  val inner_ser = IO(Flipped(Vec(2, new DecoupledFlitIO(serPhyParams.flitWidth))))

  val phy = Module(new CreditedSerialPhy(2, serPhyParams))

  phy.io.inner_clock := clock
  phy.io.inner_reset := reset.asBool

  phy.io.outgoing_clock := clock
  phy.io.outgoing_reset := reset.asBool

  phy.io.incoming_clock := io.clock_in
  phy.io.incoming_reset := ResetCatchAndSync(io.clock_in, io.reset_in.asBool)

  io.clock_out := phy.io.outgoing_clock
  io.reset_out := phy.io.outgoing_reset.do_asAsyncReset

  phy.io.outer_ser.in <> io.in
  phy.io.outer_ser.out <> io.out

  phy.io.inner_ser(0) <> inner_ser(0)
  phy.io.inner_ser(1) <> inner_ser(1)

}
