package redefine.rrm.mmu

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import redefine.header.abi.{MMUKernelInfoEntry, MMUSegmentInfoEntry}
import redefine.header.kernelSections._
import redefine.header.{FabricIOKey, FabricKey, FabricSize}
import redefine.rrm.MMUExceptionUnit
import redefine.util.DynLockingArbiter

class LUTReqIfc(addrWidth: Int, dataWidth: Int) extends Bundle {
  val write = Bool()
  val addr  = UInt(addrWidth.W)
  val data  = UInt(dataWidth.W)
}

class KernelInfoLUT(nEntries: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(nEntries)
  assert(
    nEntries <= 32,
    "Built using 1W3R RF. The number of entries are more than 32.",
  )

  val io = IO(new Bundle {
    val rdReqForAddrTranslation  = Flipped(Decoupled(UInt(addrWidth.W)))
    val rdRespForAddrTranslation = Decoupled(UInt(dataWidth.W))
    val configReq                = Flipped(Decoupled(new LUTReqIfc(addrWidth, dataWidth)))
    val configRdResp             = Decoupled(UInt(dataWidth.W))
    // Additional ports needed to cater kernelInfo read requests from EWMU
    val rdReqFromEwmu = Flipped(Decoupled(UInt(addrWidth.W)))
    val rdResp2Ewmu   = Decoupled(UInt(dataWidth.W))
  })

  val rdRespForAddrTranslationBuf = Module(new Queue(UInt(dataWidth.W), 1, pipe = true))
  val configRdRespBuf             = Module(new Queue(UInt(dataWidth.W), 1, pipe = true))
  val ewmuRdRespBuf               = Module(new Queue(UInt(dataWidth.W), 1, pipe = true))

  io.configRdResp <> configRdRespBuf.io.deq
  io.rdRespForAddrTranslation <> rdRespForAddrTranslationBuf.io.deq
  io.rdResp2Ewmu <> ewmuRdRespBuf.io.deq

  val kernelInfoMem = Mem(nEntries, UInt(dataWidth.W))
  when(io.configReq.valid && io.configReq.bits.write) {
    kernelInfoMem.write(io.configReq.bits.addr, io.configReq.bits.data)
  }
  configRdRespBuf.io.enq.valid := (io.configReq.valid && !io.configReq.bits.write)
  configRdRespBuf.io.enq.bits  := kernelInfoMem.read(io.configReq.bits.addr)
  io.configReq.ready           := configRdRespBuf.io.enq.ready || io.configReq.bits.write

  rdRespForAddrTranslationBuf.io.enq.valid := io.rdReqForAddrTranslation.valid
  rdRespForAddrTranslationBuf.io.enq.bits  := kernelInfoMem.read(io.rdReqForAddrTranslation.bits)
  io.rdReqForAddrTranslation.ready         := rdRespForAddrTranslationBuf.io.enq.ready

  ewmuRdRespBuf.io.enq.valid := io.rdReqFromEwmu.valid
  ewmuRdRespBuf.io.enq.bits  := kernelInfoMem.read(io.rdReqFromEwmu.bits)
  io.rdReqFromEwmu.ready     := ewmuRdRespBuf.io.enq.ready
}

class SegmentInfoLUT(nEntries: Int, dataWidth: Int) extends Module {
  val addrWidth = log2Ceil(nEntries)

  val io = IO(new Bundle {
    val rdReqForAddrTranslation  = Flipped(Decoupled(UInt(addrWidth.W)))
    val rdRespForAddrTranslation = Decoupled(UInt(dataWidth.W))
    val configReq                = Flipped(Decoupled(new LUTReqIfc(addrWidth, dataWidth)))
    val configRdResp             = Decoupled(UInt(dataWidth.W))
  })

  val rdRespForAddrTranslationBuf = Module(new Queue(UInt(dataWidth.W), 1, flow = true))
  val configRdRespBuf             = Module(new Queue(UInt(dataWidth.W), 1, flow = true))

  val segMemRdArbiter = Module(new Arbiter(UInt(addrWidth.W), 2))
  segMemRdArbiter.io.in(0) <> io.rdReqForAddrTranslation

  segMemRdArbiter.io.in(1).valid := io.configReq.valid
  segMemRdArbiter.io.in(1).bits  := io.configReq.bits.addr
  io.configReq.ready             := segMemRdArbiter.io.in(1).ready

  val segMemRdClientSel = Module(new Queue(segMemRdArbiter.io.chosen.cloneType, entries = 1, pipe = true))
  segMemRdClientSel.io.enq.valid := segMemRdArbiter.io.out.fire
  segMemRdClientSel.io.enq.bits  := segMemRdArbiter.io.chosen
  segMemRdClientSel.io.deq.ready := true.B

  when(segMemRdArbiter.io.chosen === 1.U) {
    segMemRdArbiter.io.out.ready := (configRdRespBuf.io.enq.ready || configRdRespBuf.io.deq.ready)
  }.otherwise {
    segMemRdArbiter.io.out.ready := (rdRespForAddrTranslationBuf.io.enq.ready || rdRespForAddrTranslationBuf.io.deq.ready)
  }

  // Design intent: segMemRdClientSel must always have space when arbiter output is valid,
  // since deq.ready is tied high (pipe=true queue always drains immediately).
  // Assert is trivially true by construction but documents the invariant explicitly.
  when(segMemRdArbiter.io.out.valid) {
    assert(segMemRdClientSel.io.enq.ready)
  }

  val configRdRespEn = RegNext(!io.configReq.bits.write)

  when(RegNext(segMemRdClientSel.io.enq.fire)) {
    when(segMemRdClientSel.io.deq.bits === 1.U) {
      when(configRdRespEn) {
        assert(configRdRespBuf.io.enq.fire)
      }
    }.otherwise {
      assert(rdRespForAddrTranslationBuf.io.enq.fire)
    }
  }

  /* Single-Ported SRAM (1RW - SRAM) */
  val segmentInfoMem = SyncReadMem(nEntries, UInt(dataWidth.W))
  val rdRespData     = Wire(UInt(dataWidth.W))
  rdRespData := DontCare

  /* Single-ported SRAMs can be inferred when the read and write conditions
   * are mutually exclusive.
   */
  rdRespData := segmentInfoMem.readWrite(
    segMemRdArbiter.io.out.bits,
    io.configReq.bits.data,
    segMemRdArbiter.io.out.valid,
    io.configReq.bits.write && segMemRdArbiter.io.chosen === 1.U,
  )

  io.rdRespForAddrTranslation <> rdRespForAddrTranslationBuf.io.deq
  rdRespForAddrTranslationBuf.io.enq.valid := segMemRdClientSel.io.deq.valid && (segMemRdClientSel.io.deq.bits === 0.U)
  rdRespForAddrTranslationBuf.io.enq.bits  := rdRespData

  io.configRdResp <> configRdRespBuf.io.deq
  configRdRespBuf.io.enq.valid := segMemRdClientSel.io.deq.valid && (segMemRdClientSel.io.deq.bits === 1.U) && configRdRespEn
  configRdRespBuf.io.enq.bits  := rdRespData
}

class MMUConfig(implicit p: Parameters) extends LazyModule with MMUCoreFunc {
  val params = p(MMUKey)
  // Slave node for the config port
  /* TODO: MMUConfig accesses are all assumed to be 64-bits in this implementation.
   * 1 -> config manager is characterized with Transfer-sizes (1, 8), as required by TLFragmenter.
   * 2 -> PutPartial is supported due to the requirements of TSI Client limitation
   */
  val configSlaveNode = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        Seq(
          TLSlaveParameters.v1(
            address = AddressSet.misaligned(params.baseAddr, params.lutAddrSize),
            regionType = RegionType.UNCACHED,
            supportsGet = TransferSizes(1, MMUConst.mmuEntryByteSize),
            supportsPutFull = TransferSizes(1, MMUConst.mmuEntryByteSize),
            supportsPutPartial = TransferSizes(1, MMUConst.mmuEntryByteSize), // FIXME: PutPartial must be dropped.
            fifoId = Some(0),
          ),
        ),
        beatBytes = MMUConst.mmuEntryByteSize,
        minLatency = 1,
      ),
    ),
  )

  lazy val module = new MMUConfigImp(this)
}

class MMUConfigImp(outer: MMUConfig) extends LazyModuleImp(outer) with MMUCoreFunc {
  import redefine.header.kernelSections.KernelSectionConst._
  val params             = outer.p(MMUKey)
  val fabricConfig       = outer.p(FabricKey)
  val deviceMemBusParams = outer.p(DeviceMemBusKey).get

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle {
      val phyCrLoc  = new redefine.header.CrLoc(yWidth = fabricConfig.yWidth, xWidth = fabricConfig.xWidth)
      val kernelId  = UInt(log2Ceil(fabricConfig.maxNumKernels).W)
      val segmentId = UInt(log2Ceil(numOfSegmentsPerKernel).W)
    }))

    val resp = Decoupled(new Bundle {
      val kernelInfoValid       = Bool()
      val logicalCrIdIsLegal    = Bool()
      val linearizedLogicalCrId = UInt((fabricConfig.xWidth + fabricConfig.yWidth).W)
      val segmentInfoEntry      = new MMUSegmentInfoEntry(deviceMemBusParams)
    })

    val ewmu = new Bundle {
      val kernelInfoReq  = Flipped(Decoupled(UInt(log2Ceil(fabricConfig.maxNumKernels).W)))
      val kernelInfoResp = Decoupled(new MMUKernelInfoEntry(fabricConfig))
    }

  })
  val (in, edge)        = outer.configSlaveNode.in(0)
  val configReqHndlrBuf = Module(new Queue(in.a.bits.cloneType, entries = 1, pipe = true))
  configReqHndlrBuf.io.enq.valid := in.a.fire
  configReqHndlrBuf.io.enq.bits  := in.a.bits

  when(in.a.fire) {
    // Either access entire MMUEntry or Nothing
    assert(
      in.a.bits.mask.andR || in.a.bits.mask === 0.U,
      s"MMUConfig do not support memory accesses of size other than ${MMUConst.mmuEntryByteSize}",
    )
  }

  val segmentInfoLUT = Module(
    new SegmentInfoLUT(
      fabricConfig.maxNumKernels * numOfSegmentsPerKernel,
      (new MMUSegmentInfoEntry(deviceMemBusParams)).getWidth,
    ),
  )

  segmentInfoLUT.io.rdReqForAddrTranslation.valid  := io.req.valid
  segmentInfoLUT.io.rdReqForAddrTranslation.bits   := getSegmentInfoSRAMAddr(io.req.bits.kernelId, io.req.bits.segmentId)
  segmentInfoLUT.io.rdRespForAddrTranslation.ready := io.resp.ready

  val kernelInfoLUT = Module(
    new KernelInfoLUT(fabricConfig.maxNumKernels, (new MMUKernelInfoEntry(fabricConfig)).getWidth),
  )
  kernelInfoLUT.io.rdReqForAddrTranslation.valid  := io.req.valid
  kernelInfoLUT.io.rdReqForAddrTranslation.bits   := io.req.bits.kernelId
  kernelInfoLUT.io.rdRespForAddrTranslation.ready := io.resp.ready

  kernelInfoLUT.io.rdReqFromEwmu.valid := io.ewmu.kernelInfoReq.valid
  kernelInfoLUT.io.rdReqFromEwmu.bits  := io.ewmu.kernelInfoReq.bits
  kernelInfoLUT.io.rdResp2Ewmu.ready   := io.ewmu.kernelInfoResp.ready

  io.ewmu.kernelInfoResp.valid := kernelInfoLUT.io.rdResp2Ewmu.valid
  io.ewmu.kernelInfoResp.bits := kernelInfoLUT.io.rdResp2Ewmu.bits
    .asTypeOf(new MMUKernelInfoEntry(fabricConfig))
  io.ewmu.kernelInfoReq.ready := kernelInfoLUT.io.rdReqFromEwmu.ready

  val kernelInfoRdResp = kernelInfoLUT.io.rdRespForAddrTranslation.bits.asTypeOf(new MMUKernelInfoEntry(fabricConfig))
  val kernelSize       = kernelInfoRdResp.size
  val baseCrLoc        = Wire(new redefine.header.CrLoc(xWidth = fabricConfig.xWidth, yWidth = fabricConfig.yWidth))
  baseCrLoc.y := kernelInfoRdResp.kernelBaseCrIdY
  baseCrLoc.x := kernelInfoRdResp.kernelBaseCrIdX
  val reqPhyCrLocBuf = Module(
    new Queue(
      new redefine.header.CrLoc(yWidth = fabricConfig.yWidth, xWidth = fabricConfig.xWidth),
      entries = 1,
      pipe = true,
    ),
  )
  reqPhyCrLocBuf.io.enq.valid := io.req.valid
  reqPhyCrLocBuf.io.enq.bits  := io.req.bits.phyCrLoc
  reqPhyCrLocBuf.io.deq.ready := io.resp.ready
  io.req.ready                := segmentInfoLUT.io.rdReqForAddrTranslation.ready && kernelInfoLUT.io.rdReqForAddrTranslation.ready && reqPhyCrLocBuf.io.enq.ready
  when(io.req.valid) {
    // All three ready signals move in lock-step:
    // - SegmentInfoLUT: rdReqForAddrTranslation is always granted (arbiter in(0) has priority over config in(1)),
    //   so its ready only goes low when its response buffer is full.
    // - KernelInfoLUT: ready only goes low when its response buffer is full.
    // - reqPhyCrLocBuf: same — goes low only when full.
    // All three buffers are filled together on io.req.fire and drained together via io.resp.ready,
    // so their ready signals are always in agreement.
    // The arbiter priority (addr translation > config) ensures the common-case path is never
    // stalled by config accesses, optimizing for MMU address translation throughput.
    val r = Cat(
      segmentInfoLUT.io.rdReqForAddrTranslation.ready,
      kernelInfoLUT.io.rdReqForAddrTranslation.ready,
      reqPhyCrLocBuf.io.enq.ready,
    )
    assert((r === 0.U) || r.andR)
  }

  val logicalCrLoc = reqPhyCrLocBuf.io.deq.bits.getLogicalLoc(baseCrLoc, new FabricSize(fabricConfig))
  io.resp.valid                      := segmentInfoLUT.io.rdRespForAddrTranslation.valid && kernelInfoLUT.io.rdRespForAddrTranslation.valid && reqPhyCrLocBuf.io.deq.valid
  io.resp.bits.linearizedLogicalCrId := logicalCrLoc.linearize(nY = kernelSize.nY, nX = kernelSize.nX)
  io.resp.bits.logicalCrIdIsLegal    := logicalCrLoc.isLogicalCrLocValid(kernelSize)
  io.resp.bits.kernelInfoValid       := kernelInfoRdResp.valid
  io.resp.bits.segmentInfoEntry := segmentInfoLUT.io.rdRespForAddrTranslation.bits
    .asTypeOf(new MMUSegmentInfoEntry(deviceMemBusParams))

  when(io.resp.ready) {
    // Address translation responses are processed in lock-step for SegmentInfoLUT and KernelInfoLUT.
    val r = Cat(
      segmentInfoLUT.io.rdRespForAddrTranslation.valid,
      kernelInfoLUT.io.rdRespForAddrTranslation.valid,
      reqPhyCrLocBuf.io.deq.valid,
    )
    assert((r === 0.U) || r.andR)
  }

  // Requests from configuration interface
  val isWrite      = (in.a.bits.opcode === TLMessages.PutFullData) || (in.a.bits.opcode === TLMessages.PutPartialData)
  val partialWrite = in.a.bits.opcode === TLMessages.PutPartialData && !in.a.bits.mask.andR

  // Partial writes to LUT addresses are denied; skip LUT ready — LUT won't be accessed.
  when(isSegmentInfoMemAddr(in.a.bits.address) && !partialWrite) {
    in.a.ready := segmentInfoLUT.io.configReq.ready && configReqHndlrBuf.io.enq.ready
  }.elsewhen(isKernelInfoMemAddr(in.a.bits.address) && !partialWrite) {
    in.a.ready := kernelInfoLUT.io.configReq.ready && configReqHndlrBuf.io.enq.ready
  }.otherwise {
    in.a.ready := configReqHndlrBuf.io.enq.ready
  }
  val segmentInfoData = Wire(new MMUSegmentInfoEntry(deviceMemBusParams))
  segmentInfoData := segmentInfoData.unpackUInt64(in.a.bits.data)
  segmentInfoLUT.io.configReq.valid := in.a.valid && isSegmentInfoMemAddr(
    in.a.bits.address,
  ) && configReqHndlrBuf.io.enq.ready && !partialWrite
  segmentInfoLUT.io.configReq.bits.write := isWrite
  segmentInfoLUT.io.configReq.bits.addr  := getSegmentInfoSRAMAddr(in.a.bits.address)
  segmentInfoLUT.io.configReq.bits.data  := segmentInfoData.asUInt

  val kernelInfoData = Wire(new MMUKernelInfoEntry(fabricConfig))
  kernelInfoData := kernelInfoData.unpackUInt64(in.a.bits.data)
  kernelInfoLUT.io.configReq.valid := in.a.valid && isKernelInfoMemAddr(
    in.a.bits.address,
  ) && configReqHndlrBuf.io.enq.ready && !partialWrite
  kernelInfoLUT.io.configReq.bits.write := isWrite
  kernelInfoLUT.io.configReq.bits.addr  := getKernelInfoSRAMAddr(in.a.bits.address)
  kernelInfoLUT.io.configReq.bits.data  := kernelInfoData.asUInt

  // Response for Configuration interface
  when(in.d.valid) {
    assert(configReqHndlrBuf.io.deq.valid)
  }
  segmentInfoLUT.io.configRdResp.ready := false.B
  kernelInfoLUT.io.configRdResp.ready  := false.B

  val hasRespData = configReqHndlrBuf.io.deq.bits.opcode === TLMessages.Get
  // Partial writes not forwarded to LUT; deny explicitly rather than silently ACK-ing without writing.
  val storedIsPartialWrite = configReqHndlrBuf.io.deq.bits.opcode === TLMessages.PutPartialData &&
    !configReqHndlrBuf.io.deq.bits.mask.andR
  val configIfcRspData   = Wire(UInt((MMUConst.mmuEntryByteSize * 8).W))
  val configIfcRspDenied = Wire(Bool())
  when(isSegmentInfoMemAddr(configReqHndlrBuf.io.deq.bits.address) && !storedIsPartialWrite) {
    in.d.valid := Mux(hasRespData, segmentInfoLUT.io.configRdResp.valid, configReqHndlrBuf.io.deq.valid)
    configIfcRspData := segmentInfoLUT.io.configRdResp.bits
      .asTypeOf(new MMUSegmentInfoEntry(deviceMemBusParams))
      .packUInt64
    configIfcRspDenied                   := false.B
    configReqHndlrBuf.io.deq.ready       := in.d.ready && (segmentInfoLUT.io.configRdResp.valid || !hasRespData)
    segmentInfoLUT.io.configRdResp.ready := in.d.ready
  }.elsewhen(isKernelInfoMemAddr(configReqHndlrBuf.io.deq.bits.address) && !storedIsPartialWrite) {
    in.d.valid                          := Mux(hasRespData, kernelInfoLUT.io.configRdResp.valid, configReqHndlrBuf.io.deq.valid)
    configIfcRspData                    := kernelInfoLUT.io.configRdResp.bits.asTypeOf(new MMUKernelInfoEntry(fabricConfig)).packUInt64
    configIfcRspDenied                  := false.B
    configReqHndlrBuf.io.deq.ready      := in.d.ready && (kernelInfoLUT.io.configRdResp.valid || !hasRespData)
    kernelInfoLUT.io.configRdResp.ready := in.d.ready
  }.otherwise {
    // Covers: out-of-range addresses AND partial writes to LUT addresses.
    in.d.valid                     := configReqHndlrBuf.io.deq.valid
    configIfcRspData               := 0.U
    configIfcRspDenied             := configReqHndlrBuf.io.deq.bits.mask.orR // requests with no masks are ignored.
    configReqHndlrBuf.io.deq.ready := in.d.ready
  }

  when(configReqHndlrBuf.io.deq.bits.opcode === TLMessages.Get) {
    in.d.bits := edge.AccessAck(
      configReqHndlrBuf.io.deq.bits,
      data = configIfcRspData,
      denied = configIfcRspDenied,
      corrupt = configIfcRspDenied, // As per TL spec, corrupt should also be high when denied is high for AccessAckData
    )
  }.otherwise {
    in.d.bits := edge.AccessAck(
      configReqHndlrBuf.io.deq.bits,
      denied = configIfcRspDenied,
    )
  }
}

class MMU(implicit p: Parameters) extends LazyModule with MMUCoreFunc {
  val params = p(MMUKey)
  require(!kernelInfoLUTAddressSet.overlaps(segmentInfoLUTAddressSet))
  // Adapter node provides both master and slave interfaces
  val node = TLAdapterNode(
    clientFn = { case cp =>
      cp.v1copy(clients = cp.clients.map { c =>
        c.v1copy()
      })
    },
    managerFn = { case mp =>
      mp.v1copy(managers = Seq(mp.managers.head.v1copy(address = fabric2mmu_addressSet)))
    },
  )

  val ctrlNode = TLNameNode(ValName("config_ctrl"))
  val xbar     = TLXbar()
  xbar := ctrlNode
  val configCntrl   = LazyModule(new MMUConfig)
  val exceptionUnit = LazyModule(new MMUExceptionUnit(params.baseAddr + params.lutAddrSize))
  configCntrl.configSlaveNode := xbar
  exceptionUnit.regNode       := xbar
  val configSlaveNode = configCntrl.configSlaveNode

  lazy val module = new MMUImp(this)
}
class MMUImp(outer: MMU) extends LazyModuleImp(outer) {
  val fabricConfig = outer.p(FabricKey)
  val io = IO(new Bundle {
    val exception = Output(Bool())
    val ewmu = new Bundle {
      val kernelInfoReq  = Flipped(Decoupled(UInt(log2Ceil(fabricConfig.maxNumKernels).W)))
      val kernelInfoResp = Decoupled(new MMUKernelInfoEntry(fabricConfig))
    }
  })

  outer.configCntrl.module.io.ewmu.kernelInfoReq <> io.ewmu.kernelInfoReq
  io.ewmu.kernelInfoResp <> outer.configCntrl.module.io.ewmu.kernelInfoResp

  val params                           = outer.params
  val deviceMemAccessIntraCrSrcIdWidth = outer.p(FabricIOKey).deviceMemAccessIntraCrSrcIdWidth
  // Declaration of Slave and Master nodes of the MMU
  val (in, edgeIn)   = outer.node.in(0)
  val (out, edgeOut) = outer.node.out(0)

  require(edgeIn.bundle.addressBits == (log2Ceil(fabricConfig.maxNumKernels) + KernelSectionConst.kernelAddrWidth))
  require(edgeIn.bundle.sourceBits >= (fabricConfig.xWidth + fabricConfig.yWidth + deviceMemAccessIntraCrSrcIdWidth))
  require(edgeOut.bundle.addressBits == params.physicalMemoryAddrWidth)

  require(edgeIn.slave.anySupportAcquireB == false)
  require(edgeIn.slave.anySupportAcquireT == false)
  require(edgeIn.slave.anySupportHint == false)
  require(edgeIn.slave.anySupportArithmetic == false)
  require(edgeIn.slave.anySupportLogical == false)

  val segmentDecoder = Module(new SegmentDecodeTable)
  val reqPhyCrLoc    = outer.getReqCrPhyLocFromSourceId(in.a.bits.source)

  val (first_in_a, _, _, _) = edgeIn.firstlastHelper(in.a.bits, in.a.fire)

  when(in.a.fire && first_in_a) {
    assert(outer.configCntrl.module.io.req.fire)
  }

  // Stage-0
  val extAddr     = new redefine.header.ExtendedKernelLogicalAddr()(outer.p)
  val kernelId    = extAddr.unpackUInt(in.a.bits.address).kernelId
  val logicalAddr = extAddr.unpackUInt(in.a.bits.address).logicalAddr

  segmentDecoder.logicalAddr := logicalAddr
  val isLegalReg           = RegEnable(segmentDecoder.isLegal, in.a.fire && first_in_a)
  val logicalAddrOffsetReg = RegEnable(segmentDecoder.logicalAddrOffset, in.a.fire && first_in_a)
  val sectionScopeReg      = RegEnable(segmentDecoder.sectionScope, in.a.fire && first_in_a)
  val pipe0                = Module(new Queue(in.a.bits.cloneType, entries = 1, pipe = true))
  pipe0.io.deq.ready                             := false.B
  pipe0.io.enq.valid                             := in.a.valid && (outer.configCntrl.module.io.req.ready || !first_in_a)
  pipe0.io.enq.bits                              := in.a.bits
  in.a.ready                                     := pipe0.io.enq.ready && (outer.configCntrl.module.io.req.ready || !first_in_a)
  outer.configCntrl.module.io.req.valid          := in.a.valid && first_in_a && pipe0.io.enq.ready
  outer.configCntrl.module.io.req.bits.phyCrLoc  := reqPhyCrLoc
  outer.configCntrl.module.io.req.bits.kernelId  := kernelId
  outer.configCntrl.module.io.req.bits.segmentId := segmentDecoder.segmentId

  when(outer.configCntrl.module.io.req.fire) {
    assert(pipe0.io.enq.fire)
  }

  // Stage-1
  val (_, last_pipe0, done_pipe0, _) = edgeIn.firstlastHelper(pipe0.io.deq.bits, pipe0.io.deq.fire)
  outer.configCntrl.module.io.resp.ready := done_pipe0
  val segmentInfoEntry   = outer.configCntrl.module.io.resp.bits.segmentInfoEntry
  val linearLogicalCrId  = outer.configCntrl.module.io.resp.bits.linearizedLogicalCrId
  val logicalCrIdIsLegal = outer.configCntrl.module.io.resp.bits.logicalCrIdIsLegal
  val kernelInfoValid    = outer.configCntrl.module.io.resp.bits.kernelInfoValid
  val ceId =
    KernelSectionConst.getCeIdForPrivateSegment(pipe0.io.deq.bits.address(KernelSectionConst.kernelAddrWidth - 1, 0))
  val (resTypeTmp, phyAddr) =
    outer.getPhyAddrFromLogicalAddr(logicalAddrOffsetReg, sectionScopeReg, segmentInfoEntry, linearLogicalCrId, ceId)
  val resType = Mux(
    kernelInfoValid && isLegalReg,
    Mux(logicalCrIdIsLegal, resTypeTmp, MMUResultType.ACCESS_DENIED),
    MMUResultType.ADDRESS_FAULT,
  )

  /* Legal request gets forwarded from pipe0 to pipeA1
   * Illegal requests are dropped at pipe0 and their response is forwarded to deniedTLD
   */
  val pipe1 = Module(new Queue(out.a.bits.cloneType, entries = 1, pipe = true))
  out.a <> pipe1.io.deq
  val deniedTLDWr = Wire(new QueueIO(in.d.bits.cloneType, entries = 1))
  deniedTLDWr.deq <> deniedTLDWr.enq
  deniedTLDWr.count                                 := DontCare
  outer.exceptionUnit.module.io.in.valid            := deniedTLDWr.enq.valid
  outer.exceptionUnit.module.io.in.bits.kernelId    := extAddr.unpackUInt(pipe0.io.deq.bits.address).kernelId
  outer.exceptionUnit.module.io.in.bits.logicalAddr := extAddr.unpackUInt(pipe0.io.deq.bits.address).logicalAddr
  outer.exceptionUnit.module.io.in.bits.phyCrId     := outer.getReqCrPhyLocFromSourceId(pipe0.io.deq.bits.source)
  io.exception                                      := outer.exceptionUnit.module.io.out

  val (_, last_denied, done_denied, _) = edgeIn.firstlastHelper(deniedTLDWr.deq.bits, deniedTLDWr.deq.fire)
  when(outer.configCntrl.module.io.resp.valid) {
    when(resType =/= MMUResultType.LEGAL) {

      /** Drop the beats of illegal transaction. The response for the illegal request is generated only after receiving
        * the last beat of illegal request at pipe0. The last beat is used to generate the response beats, retained
        * until all the response beats are sent out. This is to avoid generating multiple responses for the same illegal
        * request.
        */
      when(last_pipe0) {
        pipe0.io.deq.ready := deniedTLDWr.enq.ready && done_denied
      }.otherwise {
        pipe0.io.deq.ready := true.B
      }
    }.otherwise {
      pipe0.io.deq.ready := pipe1.io.enq.ready
    }
  }
  pipe1.io.enq.valid := pipe0.io.deq.valid && (resType === MMUResultType.LEGAL) && outer.configCntrl.module.io.resp.valid
  // Forward the legal request downstream by replacing the logical address with physical address
  pipe1.io.enq.bits.exclude(_.address) :<= pipe0.io.deq.bits.exclude(_.address)
  pipe1.io.enq.bits.address := phyAddr
  // Forward response from outEdge-port to inEdge-port
  deniedTLDWr.enq.valid := pipe0.io.deq.valid && last_pipe0 && (resType =/= MMUResultType.LEGAL) && outer.configCntrl.module.io.resp.valid
  when(pipe0.io.deq.bits.opcode === TLMessages.Get) {
    deniedTLDWr.enq.bits := edgeIn.AccessAck(
      pipe0.io.deq.bits,
      pipe0.io.deq.bits.data,
      denied = true.B,
      corrupt = true.B, // As per TL spec, corrupt should also be high when denied is high for AccessAckData
    )
  }.otherwise {
    deniedTLDWr.enq.bits := edgeIn.AccessAck(pipe0.io.deq.bits, denied = true.B)
  }

  val inRspArb = Module(
    new DynLockingArbiter(
      in.d.bits.cloneType,
      2,
      maxIdleCycles = 2,
    ),
  )

  // Priority to handling response from outEdge-port (D-Channel) than handling illegal request (A-Channel)
  inRspArb.io.in(0).valid := out.d.valid
  inRspArb.io.in(0).bits  := out.d.bits
  val (first_inRsp_0, last_inRsp_0, _, _) = edgeIn.firstlastHelper(inRspArb.io.in(0).bits, inRspArb.io.in(0).fire)
  inRspArb.io.lockCond(0)   := first_inRsp_0 && !last_inRsp_0
  inRspArb.io.unlockCond(0) := !first_inRsp_0 && last_inRsp_0
  out.d.ready               := inRspArb.io.in(0).ready

  inRspArb.io.in(1).valid := deniedTLDWr.deq.valid
  inRspArb.io.in(1).bits  := deniedTLDWr.deq.bits
  val (first_inRsp_1, last_inRsp_1, _, _) = edgeIn.firstlastHelper(inRspArb.io.in(1).bits, inRspArb.io.in(1).fire)
  inRspArb.io.lockCond(1)   := first_inRsp_1 && !last_inRsp_1
  inRspArb.io.unlockCond(1) := !first_inRsp_1 && last_inRsp_1
  deniedTLDWr.deq.ready     := inRspArb.io.in(1).ready

  val inRspBuf = Module(new Queue(in.d.bits.cloneType, entries = 1, pipe = true))
  in.d <> inRspBuf.io.deq
  inRspBuf.io.enq <> inRspArb.io.out
}
