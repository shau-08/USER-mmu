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
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}

class MyClient1(implicit p: Parameters) extends LazyModule {
  val node = AXI4MasterNode(Seq(AXI4MasterPortParameters(masters = Seq(AXI4MasterParameters(name = "MyClient-1")))))

  val src                  = InModuleBody(node.makeIOs())
  override lazy val module = new LazyModuleImp(this) {}
}

class MyManager1(implicit p: Parameters) extends LazyModule {
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        slaves = Seq(
          AXI4SlaveParameters(
            address = Seq(AddressSet(0x20000, 0xffff)),
            regionType = RegionType.IDEMPOTENT,
            executable = true,
            supportsWrite = TransferSizes(1, 64),
            supportsRead = TransferSizes(1, 64),
          ),
        ),
        beatBytes = 4,
        minLatency = 1,
      ),
    ),
  )

  val sink                 = InModuleBody(node.makeIOs())
  override lazy val module = new LazyModuleImp(this) {}
}

class Point2Point(implicit p: Parameters) extends LazyModule {
  val client1  = LazyModule(new MyClient1)
  val manager1 = LazyModule(new MyManager1)

  manager1.node := client1.node
  override lazy val module = new Point2PointImp(this)
}

class Point2PointImp(outer: Point2Point) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val in_aw = Flipped(Decoupled(new AXI4BundleAW(outer.client1.node.out(0)._1.params)))
    val in_ar = Flipped(Decoupled(new AXI4BundleAR(outer.client1.node.out(0)._1.params)))
    val in_w  = Flipped(Decoupled(new AXI4BundleW(outer.client1.node.out(0)._1.params)))
    val in_r  = Decoupled(new AXI4BundleR(outer.client1.node.out(0)._1.params))
    val in_b  = Decoupled(new AXI4BundleB(outer.client1.node.out(0)._1.params))

    val out_aw = Decoupled(new AXI4BundleAW(outer.manager1.node.in(0)._1.params))
    val out_ar = Decoupled(new AXI4BundleAR(outer.manager1.node.in(0)._1.params))
    val out_w  = Decoupled(new AXI4BundleW(outer.manager1.node.in(0)._1.params))
    val out_r  = Flipped(Decoupled(new AXI4BundleR(outer.manager1.node.in(0)._1.params)))
    val out_b  = Flipped(Decoupled(new AXI4BundleB(outer.manager1.node.in(0)._1.params)))
  })

  outer.client1.src(0).aw <> io.in_aw
  outer.client1.src(0).ar <> io.in_ar
  outer.client1.src(0).w <> io.in_w
  outer.client1.src(0).r <> io.in_r
  outer.client1.src(0).b <> io.in_b

  io.out_aw <> outer.manager1.sink(0).aw
  io.out_ar <> outer.manager1.sink(0).ar
  io.out_w <> outer.manager1.sink(0).w
  io.out_r <> outer.manager1.sink(0).r
  io.out_b <> outer.manager1.sink(0).b
}
