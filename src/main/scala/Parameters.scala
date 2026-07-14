package redefine.rrm.mmu

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem.MasterPortParams
import org.chipsalliance.cde.config._
import redefine.header.FabricKey
import redefine.header.abi.MMUSegmentInfoEntry
import redefine.header.kernelSections.KernelSectionScopeType

case class MMUParams(
  baseAddr:                 BigInt = 0x0230_0000, // base address mapped to MMU
  size:                     Int = 0x0010_0000,    // memory size mapped to MMU
  offsetAddrSegmentInfoLUT: Int = 0x0,            // Offset address within the address size mapped to MMU
  offsetAddrKernelInfoLUT: Int =
    0x5000, // Offset address within the address size mapped to MMU //FIXME: Non-power-of-2 offset complicates kernelInfoOffsetAddrPattern and address decoding
  physicalMemoryAddrWidth:          Int = 40,
  largestLocalSectionSegmentSize:   Int = 0x0100_0000, // 16 MB
  largestPrivateSectionSegmentSize: Int = 0x0000_8000, // 32 KB
) {
  require(isPow2(size))
  require(log2Ceil(largestLocalSectionSegmentSize) < physicalMemoryAddrWidth)
  require(log2Ceil(largestPrivateSectionSegmentSize) < physicalMemoryAddrWidth)

  val lutAddrSize = size >> 1
}

case object MMUKey extends Field[MMUParams]

/* All device memory accesses goes through the DeviceMemBus */
case object DeviceMemBusKey extends Field[Option[MasterPortParams]](None)

object MMUConst {
  val mmuEntryByteSize = 8
}

trait MMUCoreFunc {
  import redefine.header.kernelSections.KernelSectionConst._
  import MMUConst._

  implicit val p: Parameters

  val params: MMUParams

  def segmentInfoLUTAddressSetMask = BigInt(
    (1 << log2Ceil(p(FabricKey).maxNumKernels * numOfSegmentsPerKernel * mmuEntryByteSize)) - 1,
  )
  def segmentInfoLUTAddressSet =
    AddressSet(base = params.baseAddr + params.offsetAddrSegmentInfoLUT, mask = segmentInfoLUTAddressSetMask)
  def kernelInfoLUTAddressSetMask = BigInt((1 << (log2Ceil(p(FabricKey).maxNumKernels * mmuEntryByteSize))) - 1)
  def kernelInfoLUTAddressSet =
    AddressSet(base = params.baseAddr + params.offsetAddrKernelInfoLUT, mask = kernelInfoLUTAddressSetMask)

  def fabric2mmu_addressSet = logicalMemoryKernelIdExtendedAddressSet(p(FabricKey))

  def getReqCrPhyLocFromSourceId(srcId: UInt): redefine.header.CrLoc = {
    val x = new redefine.header.FabricSrcIdForDeviceMemAccess()(p)
    x.unpackUInt(srcId).reqCrPhyLoc
  }

  def segmentInfoOffsetAddrPattern: BitPat = {
    require(isPow2(mmuEntryByteSize))
    require(isPow2(p(FabricKey).maxNumKernels))
    val mask = BigInt((1 << log2Ceil(params.size)) - 1) ^ segmentInfoLUTAddressSetMask
    new BitPat(value = params.offsetAddrSegmentInfoLUT, mask = mask, width = log2Ceil(params.size))
  }

  def kernelInfoOffsetAddrPattern: BitPat = {
    val mask = BigInt((1 << log2Ceil(params.size)) - 1) ^ kernelInfoLUTAddressSetMask
    new BitPat(value = params.offsetAddrKernelInfoLUT, mask = mask, width = log2Ceil(params.size))
  }

  def isSegmentInfoMemAddr(configAddr: UInt): Bool = {
    val segmentId = getSegmentInfoSRAMAddr(configAddr) >> log2Ceil(p(FabricKey).maxNumKernels)
    configAddr === segmentInfoOffsetAddrPattern && (segmentId < numOfSegmentsPerKernel.U)
  }

  def isKernelInfoMemAddr(configAddr: UInt): Bool = configAddr === kernelInfoOffsetAddrPattern

  def getSegmentInfoSRAMAddr(configAddr: UInt): UInt = {
    require(configAddr.getWidth >= log2Ceil(params.size))
    require(isPow2(p(FabricKey).maxNumKernels))
    val entryAddr =
      (configAddr & segmentInfoLUTAddressSetMask.asUInt(configAddr.getWidth.W)) >> log2Ceil(mmuEntryByteSize)
    val kernelIndex =
      if (p(FabricKey).maxNumKernels > 1) {
        entryAddr(log2Ceil(p(FabricKey).maxNumKernels * numOfSegmentsPerKernel) - 1, log2Ceil(numOfSegmentsPerKernel))
      } else {
        0.U(0.W)
      }
    val segmentIndex = entryAddr(log2Ceil(numOfSegmentsPerKernel) - 1, 0)
    Cat(segmentIndex, kernelIndex)
  }

  def getSegmentInfoSRAMAddr(kernelId: UInt, segmentId: UInt): UInt = {
    require(math.pow(2, kernelId.getWidth) == p(FabricKey).maxNumKernels)
    require(segmentId.getWidth == log2Ceil(numOfSegmentsPerKernel))
    Cat(segmentId, kernelId)
  }

  def getKernelInfoSRAMAddr(configAddr: UInt): UInt = {
    require(configAddr.getWidth >= log2Ceil(params.size))
    val entryAddr =
      (configAddr & kernelInfoLUTAddressSetMask.asUInt(configAddr.getWidth.W)) >> log2Ceil(mmuEntryByteSize)
    entryAddr(log2Ceil(p(FabricKey).maxNumKernels) - 1, 0)
  }

  def getPhyAddrFromLogicalAddr(
    logicalAddrOffset: UInt,
    sectionScope:      UInt,
    segInfoEntry:      MMUSegmentInfoEntry,
    linearLogicalCrId: UInt,
    ceId:              UInt,
  ) = {
    val nCEs = p(FabricKey).nCEs
    require(ceId.getWidth >= log2Ceil(nCEs))

    val logicalOffsetHighFull = logicalAddrOffset >> log2Ceil(segmentGranularitySize)
    val logicalOffsetHigh     = logicalOffsetHighFull(segInfoEntry.size.getWidth - 1, 0)
    assert(logicalOffsetHighFull(logicalOffsetHighFull.getWidth - 1, segInfoEntry.size.getWidth).orR === false.B)
    val outOfBounds = logicalOffsetHigh >= segInfoEntry.size

    val resType = Mux(
      segInfoEntry.valid,
      Mux(outOfBounds, MMUResultType.ACCESS_DENIED, MMUResultType.LEGAL),
      MMUResultType.ADDRESS_FAULT,
    )

    val tmpCeId = Wire(UInt(log2Ceil(nCEs).W))
    if (nCEs == 1) {
      tmpCeId := 0.U(0.W)
    } else {
      tmpCeId := ceId(log2Ceil(nCEs) - 1, 0)
    }

    val segCeId = Mux(sectionScope === KernelSectionScopeType.PRIVATE, tmpCeId, 0.U)
    val segCrId = Mux(sectionScope =/= KernelSectionScopeType.GLOBAL, linearLogicalCrId, 0.U)

    val segNCes      = Mux(sectionScope === KernelSectionScopeType.PRIVATE, nCEs.U, 1.U)
    val largeSegSize = Math.max(params.largestLocalSectionSegmentSize, params.largestPrivateSectionSegmentSize)
    // Bits required to hold the `largestSegSize` must be less-than-or-equal to size field of the segmentInfoEntry
    require(log2Ceil(largeSegSize + 1) <= (segInfoEntry.size.getWidth + log2Ceil(segmentGranularitySize)))
    val segSize = segInfoEntry.size((log2Ceil(largeSegSize + 1) - log2Ceil(segmentGranularitySize)) - 1, 0)

    (
      resType,
      Cat(
        segInfoEntry.basePhyAddr + ((((segCrId * segNCes) + segCeId) * segSize) + logicalOffsetHigh),
        logicalAddrOffset(log2Ceil(segmentGranularitySize) - 1, 0),
      ),
    )

  }
}

object MMUResultType extends ChiselEnum {
  val LEGAL         = Value(0.U) // Logical address has an valid physical address
  val ACCESS_DENIED = Value(1.U) // Requester has insufficient permission or address out-of bounds
  val ADDRESS_FAULT = Value(2.U) // Logical address has no backing physical address (invalid kernel ID or segment ID)
}
