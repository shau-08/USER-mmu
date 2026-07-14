package testproj

import chisel3._
import hardfloat.AddRecFN

// Minimal module that just instantiates a real hardfloat unit,
// so this compiles only if the hardfloat dependency is wired correctly.
class HardfloatSmokeTest(expWidth: Int = 8, sigWidth: Int = 24) extends Module {
  val io = IO(new Bundle {
    val a            = Input(UInt((expWidth + sigWidth + 1).W))
    val b            = Input(UInt((expWidth + sigWidth + 1).W))
    val roundingMode = Input(UInt(3.W))
    val out          = Output(UInt((expWidth + sigWidth + 1).W))
  })

  val adder = Module(new AddRecFN(expWidth, sigWidth))
  adder.io.subOp          := false.B
  adder.io.a              := io.a
  adder.io.b              := io.b
  adder.io.roundingMode   := io.roundingMode
  adder.io.detectTininess := false.B

  io.out := adder.io.out
}
