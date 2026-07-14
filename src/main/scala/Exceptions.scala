package redefine.rrm

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.regmapper.{RegField, RegFieldDesc, RegFieldGroup}
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import redefine.header.kernelSections.KernelSectionConst
import redefine.header.{CrLoc, FabricKey}
import redefine.rrm.mmu.MMUConst

class MMUExceptionUnit(val base: BigInt)(implicit p: Parameters) extends LazyModule {

  val device = new SimpleDevice("MMU exception capture", Seq("mmu_excpt_Info"))

  val regNode = TLRegisterNode(
    address = Seq(AddressSet(base, 0xfff)),
    device = device,
    beatBytes = MMUConst.mmuEntryByteSize,
    concurrency = 1,
  )

  lazy val module = new MMUExceptionUnitImp(this)
}

class MMUExceptionUnitImp(outer: MMUExceptionUnit) extends LazyModuleImp(outer) {
  val fabricParams = p(FabricKey)
  val io = IO(new Bundle {
    val out = Output(Bool())

    val in = Flipped(Valid(new Bundle {
      val kernelId    = UInt(fabricParams.kernelIdWidth.W)
      val phyCrId     = new CrLoc(yWidth = fabricParams.yWidth, xWidth = fabricParams.xWidth)
      val logicalAddr = UInt(KernelSectionConst.kernelAddrWidth.W)
    }))
  })

  // Latches exception info only when no exception is already pending.
  // New exceptions are silently dropped until software clears the pending flag (w1ToClear).
  val exceptionPendingReg = RegInit(false.B)
  val captureEn           = !exceptionPendingReg && io.in.valid
  val kernelId            = RegEnable(io.in.bits.kernelId, captureEn)
  val phyCrId             = RegEnable(io.in.bits.phyCrId, captureEn)
  val logicalAddr         = RegEnable(io.in.bits.logicalAddr, captureEn)

  // Drive interrupt/exception-pending signal to upstream
  io.out := exceptionPendingReg

  outer.regNode.regmap(
    0x000 -> RegFieldGroup(
      "MMU_Exception_Capture",
      Some("MMU Illegal request Info"),
      Seq(
        // Write 1 to clear; set by hardware when a new exception is captured
        RegField.w1ToClear(
          1,
          exceptionPendingReg,
          io.in.valid,
          Some(RegFieldDesc(name = "exception_pending", desc = "")),
        ),
        RegField(7, RegFieldDesc("unused", "Reserved")),
        RegField.r(8, kernelId, RegFieldDesc("kernel_id", "")),
        RegField.r(8, phyCrId.x, RegFieldDesc("cr_id_x", "")),
        RegField.r(8, phyCrId.y, RegFieldDesc("cr_id_y", "")),
        RegField.r(32, logicalAddr, RegFieldDesc("logical_addr", "Logical address received from Fabric")),
      ),
    ),
  )
}
