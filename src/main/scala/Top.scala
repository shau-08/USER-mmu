// See README.md for license details.

package explorerTL

import emitrtl.Toplevel
import explorerTL.serdes.{SerdesPhyImp, TLSerialLoopBack}
import explorerTL.switchboard.Minimal
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import testchipip.serdes.InternalSyncSerialPhyParams

/** To run from a terminal shell
  * {{{
  * mill explorerTL.runMain explorerTL.explorerTLMain
  * }}}
  */

object lazyExplorerTLMain extends App with emitrtl.LazyToplevel {
  val str = if (args.length == 0) "" else args(0)
  val lazyTop = str match {
    case "Point2Point"      => LazyModule(new point2point.Point2Point()(Parameters.empty))
    case "RegNode"          => LazyModule(new regNode.DUT()(Parameters.empty))
    case "SmoketestRegNode" => LazyModule(new regNode.SmoketestRegNode()(Parameters.empty))
    case "AsyncDevice"      => LazyModule(new asyncDevice.DUT()(Parameters.empty))
    case "AdapterNode"      => LazyModule(new adapterNode.DUT()(Parameters.empty))
    case "TLSerDesLoopBack" => LazyModule(new TLSerialLoopBack()(Parameters.empty))
    case "AXI4"             => LazyModule(new axi4.Point2Point()(Parameters.empty))
    case "SBTLLoopback"     => LazyModule(new tilelinkSwitchboard.TLLoopback()(Parameters.empty))
    case "SBTLMem"          => LazyModule(new tilelinkSwitchboard.TLMem()(Parameters.empty))
    case _                  => throw new Exception("Unknown Module Name!")
  }

  showModuleComposition(lazyTop)
  chisel2firrtl()
  firrtl2sv()
  genDiplomacyGraph()
}

object explorerTLMain extends App with Toplevel {
  val str = if (args.length == 0) "" else args(0)
  lazy val topModule = str match {
    case "Minimal" => new Minimal
    case "SerPhy"  => new SerdesPhyImp(InternalSyncSerialPhyParams())
    case _         => throw new Exception("Unknown Module Name!")
  }
  chisel2firrtl()
  firrtl2sv()
}
