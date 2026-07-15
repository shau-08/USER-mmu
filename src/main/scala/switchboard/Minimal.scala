package explorerTL.switchboard

import chisel3._
import chisel3.util._
//import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
//import freechips.rocketchip.tilelink._.
//import org.chipsalliance.cde.config._
//import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}

class SwitchboardIfc extends Bundle {
  val data = Output(UInt(256.W))
  val dest = Output(UInt(32.W))
  val last = Output(Bool())
}

class SBIO extends Bundle {
  val data  = Output(UInt(256.W))
  val dest  = Output(UInt(32.W))
  val last  = Output(Bool())
  val valid = Output(Bool())
  val ready = Input(Bool())
}

class Minimal extends Module {
  val io = IO(new Bundle {
    val out = new SBIO
    val in  = Flipped(new SBIO)
  })

  val buf = Module(new Queue(new SwitchboardIfc, 32))

  val x = VecInit(io.in.data.asTypeOf(Vec(32, UInt(8.W))).map(i => i + 1.U))
  buf.io.enq.valid     := io.in.valid
  buf.io.enq.bits.data := x.asUInt
  buf.io.enq.bits.dest := io.in.dest
  buf.io.enq.bits.last := io.in.last
  io.in.ready          := buf.io.enq.ready

  io.out.valid     := buf.io.deq.valid
  io.out.data      := buf.io.deq.bits.data
  io.out.dest      := buf.io.deq.bits.dest
  io.out.last      := buf.io.deq.bits.last
  buf.io.deq.ready := io.out.ready

}
