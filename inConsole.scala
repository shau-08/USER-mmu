
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import _root_.circt.stage.ChiselStage
val dut = LazyModule(new explorerTL.point2point.Point2Point()(Parameters.empty))
ChiselStage.emitCHIRRTL(dut.module)
