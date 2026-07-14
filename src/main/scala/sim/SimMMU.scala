package redefine.rrm.mmu.sim

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import redefine.header.{FabricIOKey, FabricKey}
import redefine.rrm.mmu.{DeviceMemBusKey, MMU, MMUCoreFunc, MMUKey}

//top-level class for connecting nodes
class SimMMU()(implicit p: Parameters) extends LazyModule with MMUCoreFunc {
  val params                           = p(MMUKey)
  val fabricConfig                     = p(FabricKey)
  val memBusParams                     = p(DeviceMemBusKey).get
  val deviceMemAccessIntraCrSrcIdWidth = p(FabricIOKey).deviceMemAccessIntraCrSrcIdWidth
  require(isPow2(memBusParams.base) || (memBusParams.base == 0))

  println(s"Begin Test-Harness $p")
  val ram = LazyModule(
    new TLRAM(
      AddressSet.misaligned(memBusParams.base, memBusParams.size).head,
      beatBytes = memBusParams.beatBytes,
      sramReg = false,
    ),
  )
  val memMasterNode = new TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "Fabric Interface",
            sourceId = IdRange(0, 1 << (fabricConfig.xWidth + fabricConfig.yWidth + deviceMemAccessIntraCrSrcIdWidth)),
          ),
        ),
      ),
    ),
  )

  val mmu = LazyModule(new MMU)
  val configMasterNode = new TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "MMU Config Interface",
            sourceId = IdRange(0, 4),
          ),
        ),
      ),
    ),
  )

  DisableMonitors { implicit p =>
    ram.node     := TLFragmenter(memBusParams.beatBytes, memBusParams.maxXferBytes) := mmu.node := memMasterNode
    mmu.ctrlNode := configMasterNode
  }
  lazy val module = new SimMMUImp(this)
}

class SimMMUImp(outer: SimMMU) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val config_a = Flipped(Decoupled(new TLBundleA(outer.configMasterNode.out(0)._2.bundle)))
    val config_d = Decoupled(new TLBundleD(outer.configMasterNode.out(0)._2.bundle))

    val fabric_a = Flipped(Decoupled(new TLBundleA(outer.memMasterNode.out(0)._2.bundle)))
    val fabric_d = Decoupled(new TLBundleD(outer.memMasterNode.out(0)._2.bundle))

    val configSrc = new Bundle {
      val enq     = Flipped(Decoupled(UInt(config_a.bits.source.getWidth.W)))
      val deqBits = UInt(config_a.bits.source.getWidth.W)
    }

    val fabricSrc = new Bundle {
      val enq     = Flipped(Decoupled(UInt(fabric_a.bits.source.getWidth.W)))
      val deqBits = UInt(fabric_a.bits.source.getWidth.W)
    }

    val cyc = Output(UInt(32.W))
  })

  val cycCnt = Counter(true.B, 1000000)

  io.cyc := cycCnt._1
  val configSrcId = Module(
    new Queue(UInt(io.config_a.bits.source.getWidth.W), entries = 1 << io.config_a.bits.source.getWidth),
  )

  val fabricEdge               = outer.memMasterNode.out(0)._2
  val (_, _, fabric_D_done, _) = fabricEdge.firstlastHelper(io.fabric_d.bits, io.fabric_d.fire)
  val (_, _, fabric_A_done, _) = fabricEdge.firstlastHelper(io.fabric_a.bits, io.fabric_a.fire)

  outer.mmu.module.io.ewmu.kernelInfoReq.valid  := false.B
  outer.mmu.module.io.ewmu.kernelInfoReq.bits   := DontCare
  outer.mmu.module.io.ewmu.kernelInfoResp.ready := true.B

  when(io.config_d.fire) {
    assert(configSrcId.io.enq.ready)
    configSrcId.io.enq.valid := true.B
    configSrcId.io.enq.bits  := io.config_d.bits.source
    io.configSrc.enq.ready   := false.B
  }.otherwise {
    configSrcId.io.enq.valid := io.configSrc.enq.valid
    configSrcId.io.enq.bits  := io.configSrc.enq.bits
    io.configSrc.enq.ready   := true.B
  }

  when(io.config_a.fire) {
    assert(configSrcId.io.deq.valid)
  }
  io.configSrc.deqBits     := configSrcId.io.deq.bits
  configSrcId.io.deq.ready := io.config_a.fire

  val fabricSrcId = Module(
    new Queue(UInt(io.fabric_a.bits.source.getWidth.W), entries = 1 << io.fabric_a.bits.source.getWidth),
  )
  when(fabric_D_done) {
    fabricSrcId.io.enq.valid := true.B
    fabricSrcId.io.enq.bits  := io.fabric_d.bits.source
    io.fabricSrc.enq.ready   := false.B
  }.otherwise {
    fabricSrcId.io.enq.valid := io.fabricSrc.enq.valid
    fabricSrcId.io.enq.bits  := io.fabricSrc.enq.bits
    io.fabricSrc.enq.ready   := true.B
  }

  when(fabric_A_done) {
    assert(fabricSrcId.io.deq.valid)
  }
  io.fabricSrc.deqBits     := fabricSrcId.io.deq.bits
  fabricSrcId.io.deq.ready := fabric_A_done

  val config_out = outer.configMasterNode.out(0)._1
  config_out.a.valid := io.config_a.valid
  config_out.a.bits  := io.config_a.bits
  io.config_a.ready  := config_out.a.ready

  io.config_d.valid  := config_out.d.valid
  io.config_d.bits   := config_out.d.bits
  config_out.d.ready := io.config_d.ready

  val fabric_out = outer.memMasterNode.out(0)._1
  fabric_out.a.valid := io.fabric_a.valid
  fabric_out.a.bits  := io.fabric_a.bits
  io.fabric_a.ready  := fabric_out.a.ready

  io.fabric_d.valid  := fabric_out.d.valid
  io.fabric_d.bits   := fabric_out.d.bits
  fabric_out.d.ready := io.fabric_d.ready
}
