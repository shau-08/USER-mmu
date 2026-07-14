package redefine.rrm.mmu.dut

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, TransferSizes}
import freechips.rocketchip.subsystem.MasterPortParams
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}
import redefine.header.abi.MMUKernelInfoEntry
import redefine.header.{FabricConfig, FabricIOKey, FabricKey}
import redefine.rrm.mmu.{DeviceMemBusKey, MMU, MMUCoreFunc, MMUKey, MMUParams}

class DefaultMMUParams
  extends Config((_, here, _) => {
    case FabricKey =>
      new FabricConfig(
        maxNumKernels = 32,
        maxNumApps = 8,
        nY = 32,
        nX = 32,
        nCEs = 4,
      )
    case MMUKey =>
      new MMUParams(
        physicalMemoryAddrWidth = 25,
      )

    case DeviceMemBusKey => {
      val params                           = here(MMUKey)
      val fabricConfig                     = here(FabricKey)
      val deviceMemAccessIntraCrSrcIdWidth = here(FabricIOKey).deviceMemAccessIntraCrSrcIdWidth
      val baseAddr                         = BigInt(0x0000_0000L)
      require(BigInt(1L << params.physicalMemoryAddrWidth) > baseAddr)
      val size   = BigInt(1L << params.physicalMemoryAddrWidth) - baseAddr
      val idBits = fabricConfig.xWidth + fabricConfig.yWidth + deviceMemAccessIntraCrSrcIdWidth
      Some(
        new MasterPortParams(
          base = baseAddr,
          size = size,
          idBits = idBits,
          beatBytes = 8,
          maxXferBytes = 64,
        ),
      )
    }
  })

//top-level class for connecting nodes
class DUT_MMU()(implicit p: Parameters) extends LazyModule with MMUCoreFunc {
  val params      = p(MMUKey)
  val mmu         = LazyModule(new MMU)
  lazy val module = new DUT_MMUImp(this)
  override lazy val desiredName: String = "MyTopLevel"

  val memBusParams = p(DeviceMemBusKey).get

  val tl_out = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        Seq(
          TLSlaveParameters.v1(
            address = AddressSet.misaligned(memBusParams.base, memBusParams.size),
            supportsGet = TransferSizes(1, memBusParams.maxXferBytes),
            supportsPutFull = TransferSizes(1, memBusParams.maxXferBytes),
            supportsPutPartial = TransferSizes(1, memBusParams.maxXferBytes),
          ),
        ),
        memBusParams.beatBytes,
      ),
    ),
  )

  val tl_in = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(TLMasterParameters.v1(name = "my-client", sourceId = IdRange(0, 1 << memBusParams.idBits))),
      ),
    ),
  )

  val tl_config_in = TLClientNode(
    Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(name = "my-config-client", sourceId = IdRange(0, 4))))),
  )
  mmu.node     := tl_in
  mmu.ctrlNode := tl_config_in
  tl_out       := mmu.node

  val tl_out_IO       = InModuleBody(tl_out.makeIOs())
  val tl_in_IO        = InModuleBody(tl_in.makeIOs())
  val tl_config_in_IO = InModuleBody(tl_config_in.makeIOs())
}

class DUT_MMUImp(outer: DUT_MMU) extends LazyModuleImp(outer) {
  val fabricConfig = outer.p(FabricKey)
  val io = IO(new Bundle {
    val reqFromEwmu = Flipped(Decoupled(UInt(log2Ceil(fabricConfig.maxNumKernels).W)))
    val resptoEwmu  = Decoupled(new MMUKernelInfoEntry(fabricConfig))
  })

  outer.mmu.module.io.ewmu.kernelInfoReq <> io.reqFromEwmu
  outer.mmu.module.io.ewmu.kernelInfoResp <> io.resptoEwmu

}

//MMU RTL consisting of 1 master and 1 slave node
