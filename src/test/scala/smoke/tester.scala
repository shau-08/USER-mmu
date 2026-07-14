/*
package redefine.rrm.mmu.smoke

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.stage.PrintFullStackTraceAnnotation
import chisel3.util._
import chiseltest._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink.TLMessages
import org.chipsalliance.cde.config._
import org.scalatest.freespec.AnyFreeSpec
import redefine.header.kernelSections._
import redefine.rrm.mmu._
class MMUTest extends AnyFreeSpec with ChiselScalatestTester {

  implicit val p: Parameters = new Config(new sim.SmokeTestMMUParams)
  val mmuParams    = p(MMUKey)
  val memBusParams = p(DeviceMemBusKey).get

  "Test for MMU" in {
    test(
      LazyModule(
        new sim.SimMMU()(p),
      ).module,
    ).withAnnotations(
      Seq(
        WriteVcdAnnotation,
        VerilatorBackendAnnotation, // Uncomment to use the Verilator backend
      ),
    ).withChiselAnnotations(
      Seq(PrintFullStackTraceAnnotation),
    ) { dut =>
      dut.io.config_a.initSource()
      dut.io.config_d.initSink()

      dut.io.fabric_a.initSource()
      dut.io.fabric_d.initSink()

      dut.io.configSrc.enq.initSource()

      dut.io.fabricSrc.enq.initSource()

      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)
      dut.clock.step(1)

      dut.io.configSrc.enq.enqueueSeq(Seq.range(0, 4, 1).map(x => x.U))
      dut.io.fabricSrc.enq.enqueueSeq(Seq.range(0, 32, 1).map(x => x.U))

      // We fix size to 8 KB, and use only global data sections (0-31)
      def mkSegmentInfoEntry(segmentId: Int, wr: Boolean) = {
        val size     = 0x2000 >> log2Ceil(KernelSectionConst.segmentGranularitySize)
        val baseAddr = (segmentId * 0x2000) >> log2Ceil(KernelSectionConst.segmentGranularitySize)
        val configAddr =
          mmuParams.baseAddr + mmuParams.offsetAddrSegmentInfoLUT + (segmentId * MMUConst.mmuEntryByteSize)
        // val tlEdge = dut.config_out._2
        val data = if (wr) (BigInt(1L) << 63) | ((size & 0xff_ffffL) << 32) | (baseAddr & 0xffff_ffff) else BigInt(0)
        // val (_, d) = tlEdge.Put(0.U, 0x0230_0000.U, 8.U, data.U(64.W))
        val opcode = if (wr) TLMessages.PutFullData else TLMessages.Get
        chiselTypeOf(dut.io.config_a.bits).Lit(
          _.data    -> data.U,
          _.corrupt -> false.B,
          _.mask    -> 0xff.U,
          _.address -> configAddr.U,
          _.source  -> dut.io.configSrc.deqBits.peek(),
          _.size    -> 0x3.U,
          _.param   -> 0.U,
          _.opcode  -> opcode,
        )
      }

      dut.io.config_d.ready.poke(true.B)

      for (i <- Range(0, 32)) {
        val req = mkSegmentInfoEntry(i, true)
        println(s"${dut.io.cyc.peek()}@$req")
        dut.io.config_a.enqueueNow(req)
        while (!dut.io.config_d.valid.peekBoolean()) {
          step(1)
        }
        println(s"${dut.io.cyc.peek()}@ ${dut.io.config_d.bits.peek()}")
      }
      println("--Read SegmentInfo---")
      for (i <- Range(0, 64)) {
        val req = mkSegmentInfoEntry(i, false)
        println(s"${dut.io.cyc.peek()}@ $req")
        dut.io.config_a.enqueueNow(req)
        while (!dut.io.config_d.valid.peekBoolean()) {
          step(1)
        }
        println(s"${dut.io.cyc.peek()}@ ${dut.io.config_d.bits.peek()}")
      }

      def mkKernelInfoEntry(kernelId: Int, wr: Boolean) = {
        val configAddr = mmuParams.baseAddr + mmuParams.offsetAddrKernelInfoLUT + (kernelId * MMUConst.mmuEntryByteSize)
        val kXSize     = kernelId + 1
        val kYSize     = 2
        val baseX      = 0
        val baseY      = 0
        val data       = (BigInt(1L) << 63) | ((baseY << 24) | (baseX << 16) | (kYSize << 8) | kXSize)
        val opcode     = if (wr) TLMessages.PutFullData else TLMessages.Get
        chiselTypeOf(dut.io.config_a.bits).Lit(
          _.data    -> data.U,
          _.corrupt -> false.B,
          _.mask    -> 0xff.U,
          _.address -> configAddr.U,
          _.source  -> dut.io.configSrc.deqBits.peek(),
          _.size    -> 0x3.U,
          _.param   -> 0.U,
          _.opcode  -> opcode,
        )

      }

      println("--Write KerneInfo---")
      for (i <- Range(0, 32)) {
        val req = mkKernelInfoEntry(i, true)
        // println(s"$req")
        dut.io.config_a.enqueueNow(req)
        while (!dut.io.config_d.valid.peekBoolean()) {
          step(1)
        }
        // println(dut.io.config_d.bits.peek())
      }

      println("--Read KerneInfo---")
      for (i <- Range(0, 32)) {
        val req = mkKernelInfoEntry(i, false)
        // println(s"$req")
        dut.io.config_a.enqueueNow(req)
        while (!dut.io.config_d.valid.peekBoolean()) {
          step(1)
        }
        // println(dut.io.config_d.bits.peek())
      }

      // MMU accesses
      def mkFabricReq(segmentId: Int, beatId: Int, wr: Boolean) = {
        require(memBusParams.beatBytes == 8)
        def dataWd(i: Int) = (segmentId & 0x3f) << 16 | i & 0xffff
        val data = if (wr) (BigInt(dataWd(beatId + 1)) << 32) | BigInt(dataWd(beatId)) else BigInt(0)

        val address = (1L << 31) | ((segmentId & 0x1f) << 26) // Kernel-Id: 0

        val opcode = if (wr) TLMessages.PutFullData else TLMessages.Get
        chiselTypeOf(dut.io.fabric_a.bits).Lit(
          _.data    -> data.U,
          _.corrupt -> false.B,
          _.mask    -> 0xff.U,
          _.address -> address.U,
          _.source  -> dut.io.fabricSrc.deqBits.peek(),
          _.size    -> log2Ceil(memBusParams.beatBytes).U,
          _.param   -> 0.U,
          _.opcode  -> opcode,
        )

      }

      def mkIllegalFabricReq(segmentId: Int, beatId: Int, wr: Boolean) = {
        val req     = mkFabricReq(segmentId, beatId, wr)
        val address = (segmentId & 0x1f) << 26
        chiselTypeOf(dut.io.fabric_a.bits).Lit(
          _.data    -> req.data,
          _.corrupt -> req.corrupt,
          _.mask    -> req.mask,
          _.address -> address.U,
          _.source  -> req.source,
          _.size    -> req.size,
          _.param   -> req.param,
          _.opcode  -> req.opcode,
        )
      }

      dut.io.fabric_d.ready.poke(true.B)
      println("-- Fabric Write Memory request---")
      fork {
        for (segId <- Range(0, 8)) {
          for (i <- Range(0, memBusParams.beatBytes / memBusParams.beatBytes)) {
            val req = mkFabricReq(segId, i, true)
            println(s"${dut.io.cyc.peek()}@ ${req}")
            dut.io.fabric_a.enqueueNow(req)
          }
        }
      }.fork {
        for (_ <- Range(0, 8)) {
          while (!dut.io.fabric_d.valid.peekBoolean()) {
            step(1)
          }
          println(s"${dut.io.cyc.peek()}@ ${dut.io.fabric_d.bits.peek()}")
          step(1)
        }
      }.join()
      println("-- Fabric Read Memory request---")

      fork {
        // for (segId <- Range(0, 8)) {
        val req = mkFabricReq(0, 0, false)
        println(s"${dut.io.cyc.peek()}@ ${req}")
        dut.io.fabric_a.enqueueNow(req)
        // }
      }.fork {
        // for (_ <- Range(0, 8)) {
        for (_ <- Range(0, memBusParams.beatBytes / memBusParams.beatBytes)) {
          while (!dut.io.fabric_d.valid.peekBoolean()) {
            step(1)
          }
          println(s"${dut.io.cyc.peek()}@ ${dut.io.fabric_d.bits.peek()}")
          step(1)
        }
        // }
      }.join()

      println("-- Fabric Illegal Write Memory request---")
      fork {
        for (segId <- Range(0, 8)) {
          for (i <- Range(0, memBusParams.beatBytes / memBusParams.beatBytes)) {
            val req = mkIllegalFabricReq(segId, i, true)
            println(s"${dut.io.cyc.peek()}@ ${req}")
            dut.io.fabric_a.enqueueNow(req)
          }
        }
      }.fork {
        for (_ <- Range(0, 8)) {
          while (!dut.io.fabric_d.valid.peekBoolean()) {
            step(1)
          }
          println(s"${dut.io.cyc.peek()}@ ${dut.io.fabric_d.bits.peek()}")
          step(1)
        }
      }.join()

      println("-- Fabric Illegal Read Memory request---")

      fork {
        // for (segId <- Range(0, 8)) {
        val req = mkIllegalFabricReq(0, 0, false)
        println(s"${dut.io.cyc.peek()}@ ${req}")
        dut.io.fabric_a.enqueueNow(req)
        // }
      }.fork {
        // for (_ <- Range(0, 8)) {
        for (_ <- Range(0, memBusParams.beatBytes / memBusParams.beatBytes)) {
          while (!dut.io.fabric_d.valid.peekBoolean()) {
            step(1)
          }
          println(s"${dut.io.cyc.peek()}@ ${dut.io.fabric_d.bits.peek()}")
          step(1)
        }
        // }
      }.join()

      println("--Failing test case------------")
      fork {
        val req0 = mkIllegalFabricReq(0, 0, true)
        val req1 = mkFabricReq(0, 0, false)
        val req2 = mkFabricReq(0, 0, false)
        dut.io.fabric_a.enqueueSeq(Seq(req0, req1, req2))
      }.fork {
        while (!dut.io.fabric_d.valid.peekBoolean()) {
          step(1)
        }
        println(s"${dut.io.cyc.peek()}@ ${dut.io.fabric_d.bits.peek()}")
        step(1)
        while (!dut.io.fabric_d.valid.peekBoolean()) {
          step(1)
        }
        println(s"${dut.io.cyc.peek()}@ ${dut.io.fabric_d.bits.peek()}")
        step(1)
        while (!dut.io.fabric_d.valid.peekBoolean()) {
          step(1)
        }
        println(s"${dut.io.cyc.peek()}@ ${dut.io.fabric_d.bits.peek()}")
        step(1)
      }.join()

    }
  }
}
 */
