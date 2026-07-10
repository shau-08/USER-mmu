package redefine.rrm.mmu

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule

/** To run from a terminal shell
  * {{{
  * mill mmu.runMain redefine.rrm.mmu.genRTLMain mmu
  * }}}
  */
object genRTLMain extends App with emitrtl.LazyToplevel {
  // import org.chipsalliance.cde.config.Parameters
  val str = if (args.length == 0) "" else args(0)
  val lazyTop = str match {
    case "MMU"    => LazyModule(new redefine.rrm.mmu.dut.DUT_MMU()(new Config(new dut.DefaultMMUParams)))
    case "SimMMU" => LazyModule(new redefine.rrm.mmu.sim.SimMMU()(new Config(new dut.DefaultMMUParams)))
    case _        => throw new Exception("Unknown Module Name!")
  }

  showModuleComposition(lazyTop)
  chisel2firrtl()
  firrtl2sv()
  genDiplomacyGraph()

}
