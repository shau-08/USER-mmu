package explorerTL.adapterNode

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, TransferSizes}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, LazyModuleImp}

/* An example demonstrating the use of TLAdapterNode to connect two incompatible master and slave nodes using TLAdapterNode
 * The master has address width of 18 and sourceId width of 6 but the slave expects an address width of 22 and sourceId width of 9
 * Adapter Node is used to expand the sourceId and Address bits. master and slave nodes are connected via the TLAdapterNode
 * additional bits which are used to expand address and sourceId fields are received as inputs to the module

 */

class MyTLAdapter(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(
    clientFn = { case cp =>
      cp.v1copy(clients = cp.clients.map { c =>
        c.v1copy(sourceId = IdRange(c.sourceId.start, c.sourceId.end << 3)) // Expand source-id by 3-bits
      })
    },
    managerFn = { case mp =>
      mp.v1copy(managers = mp.managers.map { m =>
        m.v1copy(address = Seq(AddressSet(0x20000, 0xffff))) // 18 Address bits
      })
    },
  )

  lazy val module = new MyTLAdapterImp(this)
}

class MyTLAdapterImp(outer: MyTLAdapter) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val additionalAddrBits  = Input(UInt(4.W))
    val additionalSrcIdBits = Input(UInt(3.W))
  })

  val (slaveTLIn, _)   = outer.node.in(0)
  val (masterTLOut, _) = outer.node.out(0)

  require(
    masterTLOut.params.addressBits == (slaveTLIn.params.addressBits + 4),
    s"Address bits mismatch: masterTLOut.params.addressBits = ${masterTLOut.params.addressBits}, " +
      s"slaveTLIn.params.addressBits = ${slaveTLIn.params.addressBits}, expected master address bits = slave + 4",
  )

  require(
    masterTLOut.params.sourceBits == (slaveTLIn.params.sourceBits + 3),
    s"sourceId bits mismatch: masterTLOut.params.sourceBits = ${masterTLOut.params.sourceBits}, " +
      s"slaveTLIn.params.sourceBits = ${slaveTLIn.params.sourceBits}, expected master sourceId bits = slave + 3",
  )

  masterTLOut.a.valid := slaveTLIn.a.valid
  masterTLOut.a.bits.exclude(_.address, _.source) :<= slaveTLIn.a.bits.exclude(_.address, _.source)
  slaveTLIn.a.ready          := masterTLOut.a.ready
  masterTLOut.a.bits.address := Cat(io.additionalAddrBits, slaveTLIn.a.bits.address) // Expanded address
  masterTLOut.a.bits.source  := Cat(io.additionalSrcIdBits, slaveTLIn.a.bits.source) // Expanded sourceId

  slaveTLIn.d.bits.exclude(_.source) :<= masterTLOut.d.bits.exclude(_.source)
  slaveTLIn.d.bits.source := masterTLOut.d.bits
    .source(5, 0) // remove the expanded sourceId bits when sending the response over D-Channel
  slaveTLIn.d.valid   := masterTLOut.d.valid
  masterTLOut.d.ready := slaveTLIn.d.ready
}

class DUT()(implicit p: Parameters) extends LazyModule {

  val adapterModule = LazyModule(new MyTLAdapter)

  val tl_out = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        Seq(
          TLSlaveParameters.v1(
            address = Seq(AddressSet(0x200000, 0xfffff)), // 22 Address bits
            supportsGet = TransferSizes(1, 8),
            supportsPutFull = TransferSizes(1, 8),
            supportsPutPartial = TransferSizes(1, 8),
          ),
        ),
        8, // BeatBytes
      ),
    ),
  )

  val tl_in = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(TLMasterParameters.v1(name = "my-client", sourceId = IdRange(0, 64))), // sourceId bits = 6
      ),
    ),
  )

  adapterModule.node := tl_in
  tl_out             := adapterModule.node

  val tl_out_IO = InModuleBody(tl_out.makeIOs())
  val tl_in_IO  = InModuleBody(tl_in.makeIOs())

  lazy val module = new DUT_Imp(this)
}

class DUT_Imp(outer: DUT) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val additionalAddrBits  = Input(UInt(4.W))
    val additionalSrcIdBits = Input(UInt(3.W))
  })

  outer.adapterModule.module.io <> io

}
