package explorerTL.axi4

/*
(xbar.node
    := TLFIFOFixer()
    := TLDelayer(0.1)
    := TLBuffer(BufferParams.flow)
    := TLDelayer(0.1)
    := AXI4ToTL()
    := AXI4UserYanker(Some(4))
    := AXI4Fragmenter()
    := AXI4IdIndexer(2)
    := node)
 */

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy.{AddressSet, BufferParams}
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

class MyAXI4RAM(implicit p: Parameters) extends LazyModule {
  val client1 = LazyModule(new MyClient1)
  val sram    = LazyModule(new AXI4RAM(address = AddressSet(0x20000, 0xffff), beatBytes = 4))

  sram.node := AXI4Buffer(
    aw = BufferParams(0),
    ar = BufferParams(0),
    w = BufferParams(0),
    r = BufferParams(4),
    b = BufferParams(4),
  ) := client1.node
  override lazy val module = new MyAXI4RAMImp(this)
}

class MyAXI4RAMImp(outer: MyAXI4RAM) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val in_aw = Flipped(Decoupled(new AXI4BundleAW(outer.client1.node.out(0)._1.params)))
    val in_ar = Flipped(Decoupled(new AXI4BundleAR(outer.client1.node.out(0)._1.params)))
    val in_w  = Flipped(Decoupled(new AXI4BundleW(outer.client1.node.out(0)._1.params)))
    val in_r  = Decoupled(new AXI4BundleR(outer.client1.node.out(0)._1.params))
    val in_b  = Decoupled(new AXI4BundleB(outer.client1.node.out(0)._1.params))

  })

  outer.client1.src(0).aw <> io.in_aw
  outer.client1.src(0).ar <> io.in_ar
  outer.client1.src(0).w <> io.in_w
  outer.client1.src(0).r <> io.in_r
  outer.client1.src(0).b <> io.in_b

}
