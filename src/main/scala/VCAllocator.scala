package astronoc

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.util._

class VCAllocReq(val inParam: ChannelParams, val nOutputs: Int)(implicit val p: Parameters) extends Bundle with HasAstroNoCParams {
  val in_virt_channel = UInt(log2Ceil(inParam.virtualChannelParams.size).W)
  val in_prio = UInt(prioBits.W)
  val out_channels = UInt(nOutputs.W)
}

class VCAllocResp(val inParam: ChannelParams, val outParams: Seq[ChannelParams])(implicit val p: Parameters) extends Bundle with HasAstroNoCParams {
  val in_virt_channel = UInt(log2Ceil(inParam.virtualChannelParams.size).W)
  val out_virt_channel = UInt(log2Ceil(outParams.map(_.virtualChannelParams.size).max).W)
  val out_channel = UInt(log2Ceil(outParams.size).W)
}

class VCAllocator(val rParams: RouterParams)(implicit val p: Parameters) extends Module with HasRouterParams {
  val io = IO(new Bundle {
    val req = MixedVec(inParams.map { u => Flipped(Decoupled(new VCAllocReq(u, outParams.size))) })
    val resp = MixedVec(inParams.map { u => Valid(new VCAllocResp(u, outParams)) })

    val channel_available = MixedVec(outParams.map { u => Vec(u.virtualChannelParams.size, Input(Bool())) })
    val out_alloc = MixedVec(outParams.map { u => Valid(new OutputUnitAlloc(inParams ,u)) })
  })
  val nOutChannels = outParams.map(_.virtualChannelParams.size).sum
  class InternalAlloc extends Bundle {
    val in_channel = UInt(log2Ceil(inParams.size).W)
    val in_virt_channel = UInt(log2Ceil(inParams.map(_.virtualChannelParams.size).max).W)
    val out_channel = UInt(log2Ceil(outParams.size).W)
    val out_virt_channel = UInt(log2Ceil(outParams.map(_.virtualChannelParams.size).max).W)
  }
  val req_matrix = Seq.fill(nOutChannels) { Seq.fill(nInputs) { Wire(Decoupled(new InternalAlloc)) } }

  io.resp.foreach(_.bits := DontCare)
  (io.resp zip io.req).map { case (o,i) => o.bits.in_virt_channel := i.bits.in_virt_channel }

  var idx = 0
  for (j <- 0 until outParams.map(_.virtualChannelParams.size).max) {
    for (i <- 0 until outParams.size){
      if (j < outParams(i).virtualChannelParams.size) {
        for (k <- 0 until inParams.size) {
          val r = io.req(k)
          val legalPathsMatrix = VecInit((0 until inParams(k).virtualChannelParams.size).map { m =>
            VecInit((0 until maxPrio).map { o => rParams.vcAllocLegalPaths(k, m, i, j)(o).B })
          })

          req_matrix(idx)(k).valid := (r.valid &&
            r.bits.out_channels(i) &&
            io.channel_available(i)(j) &&
            legalPathsMatrix(r.bits.in_virt_channel)(r.bits.in_prio)
          )
          req_matrix(idx)(k).bits.in_channel := k.U
          req_matrix(idx)(k).bits.in_virt_channel := r.bits.in_virt_channel
          req_matrix(idx)(k).bits.out_channel := i.U
          req_matrix(idx)(k).bits.out_virt_channel := j.U
          when (req_matrix(idx)(k).fire()) {
            io.resp(k).bits.out_channel := i.U
            io.resp(k).bits.out_virt_channel := j.U
          }
        }
        idx += 1
      }
    }
  }

  val row_reqs = Seq.tabulate(nOutChannels) { i =>
    val rr_arb = Module(new RRArbiter(new InternalAlloc, inParams.size))
    (rr_arb.io.in zip req_matrix(i)).map { case (l,r) => l <> r }
    rr_arb.io.out
  }

  idx = 0
  val out_arbs = outParams.map(u => Module(new Arbiter(new InternalAlloc, u.virtualChannelParams.size)))
  for (j <- 0 until outParams.map(_.virtualChannelParams.size).max) {
    for (i <- 0 until outParams.size) {
      if (j < outParams(i).virtualChannelParams.size) {
        out_arbs(i).io.in(j) <> row_reqs(idx)
        idx += 1
      }
    }
  }
  require(idx == nOutChannels)

  out_arbs.foreach(_.io.out.ready := true.B)
  (out_arbs zip io.out_alloc).map { case (o,a) => 
    a.valid := o.io.out.fire()
    a.bits.in_channel := o.io.out.bits.in_channel
    a.bits.in_virt_channel := o.io.out.bits.in_virt_channel
    a.bits.out_virt_channel := o.io.out.bits.out_virt_channel
  }



  for (k <- 0 until inParams.size) {
    val fires = req_matrix.map(_(k).fire())
    assert(PopCount(fires) <= 1.U)
    io.req(k).ready := fires.reduce(_||_)
    io.resp(k).valid := fires.reduce(_||_)
  }
  require(idx == nOutChannels)
}
