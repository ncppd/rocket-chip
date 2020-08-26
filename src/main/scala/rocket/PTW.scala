// See LICENSE.Berkeley for license details.
// See LICENSE.SiFive for license details.

package freechips.rocketchip.rocket

import Chisel._
import Chisel.ImplicitConversions._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.CacheBlockBytes
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.util.property._
import chisel3.internal.sourceinfo.SourceInfo
import chisel3.experimental._
import scala.collection.mutable.ListBuffer

class PTWReq(implicit p: Parameters) extends CoreBundle()(p) {
  val addr = UInt(width = vpnBits)
}

class PTWResp(implicit p: Parameters) extends CoreBundle()(p) {
  val ae = Bool()
  val pte = new PTE
  val level = UInt(width = log2Ceil(pgLevels))
  val fragmented_superpage = Bool()
  val homogeneous = Bool()
}

class TLBPTWIO(implicit p: Parameters) extends CoreBundle()(p)
    with HasCoreParameters {
  val req = Decoupled(Valid(new PTWReq))
  val resp = Valid(new PTWResp).flip
  val ptbr = new PTBR().asInput
  val status = new MStatus().asInput
  val pmp = Vec(nPMPs, new PMP).asInput
  val customCSRs = coreParams.customCSRs.asInput
}

class PTWPerfEvents extends Bundle {
  val l2miss = Bool()
}

class DatapathPTWIO(implicit p: Parameters) extends CoreBundle()(p)
    with HasCoreParameters {
  val ptbr = new PTBR().asInput
  val sfence = Valid(new SFenceReq).flip
  val status = new MStatus().asInput
  val pmp = Vec(nPMPs, new PMP).asInput
  val perf = new PTWPerfEvents().asOutput
  val customCSRs = coreParams.customCSRs.asInput
  val clock_enabled = Bool(OUTPUT)
}

class PTE(implicit p: Parameters) extends CoreBundle()(p) {
  val ppn = UInt(width = 54)
  val reserved_for_software = Bits(width = 2)
  val d = Bool()
  val a = Bool()
  val g = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()
  val v = Bool()

  def table(dummy: Int = 0) = v && !r && !w && !x
  def leaf(dummy: Int = 0) = v && (r || (x && !w)) && a
  def ur(dummy: Int = 0) = sr() && u
  def uw(dummy: Int = 0) = sw() && u
  def ux(dummy: Int = 0) = sx() && u
  def sr(dummy: Int = 0) = leaf() && r
  def sw(dummy: Int = 0) = leaf() && w && d
  def sx(dummy: Int = 0) = leaf() && x
}

@chiselName
class PTW(n: Int)(implicit edge: TLEdgeOut, p: Parameters) extends CoreModule()(p) {
  val io = new Bundle {
    val requestor = Vec(n, new TLBPTWIO).flip
    val mem = new HellaCacheIO
    val dpath = new DatapathPTWIO
  }

  val s_ready :: s_req :: s_wait1 :: s_dummy1 :: s_wait2 :: s_wait3 :: s_dummy2 :: s_fragment_superpage :: Nil = Enum(UInt(), 8)
  val state = Reg(init=s_ready)

  val arb = Module(new RRArbiter(Valid(new PTWReq), n))
  arb.io.in <> io.requestor.map(_.req)
  arb.io.out.ready := state === s_ready

  val resp_valid = Reg(next = Vec.fill(io.requestor.size)(Bool(false)))

  val clock_en = state =/= s_ready || arb.io.out.valid || io.dpath.sfence.valid || io.dpath.customCSRs.disableDCacheClockGate
  io.dpath.clock_enabled := usingVM && clock_en
  val gated_clock =
    if (!usingVM || !tileParams.dcache.get.clockGate) clock
    else ClockGate(clock, clock_en, "ptw_clock_gate")
  withClock (gated_clock) { // entering gated-clock domain

  val invalidated = Reg(Bool())
  val count = Reg(UInt(width = log2Up(pgLevels)))
  val resp_ae = RegNext(false.B)
  val resp_fragmented_superpage = RegNext(false.B)

  val r_req = Reg(new PTWReq)
  val r_req_dest = Reg(Bits())
  val r_pte = Reg(new PTE)

  val mem_resp_valid = RegNext(io.mem.resp.valid)
  val mem_resp_data = RegNext(io.mem.resp.bits.data)
  io.mem.uncached_resp.map { resp =>
    assert(!(resp.valid && io.mem.resp.valid))
    resp.ready := true
    when (resp.valid) {
      mem_resp_valid := true
      mem_resp_data := resp.bits.data
    }
  }

  val (pte, invalid_paddr) = {
    val tmp = new PTE().fromBits(mem_resp_data)
    val res = Wire(init = tmp)
    res.ppn := tmp.ppn(ppnBits-1, 0)
    when (tmp.r || tmp.w || tmp.x) {
      // for superpage mappings, make sure PPN LSBs are zero
      for (i <- 0 until pgLevels-1)
        when (count <= i && tmp.ppn((pgLevels-1-i)*pgLevelBits-1, (pgLevels-2-i)*pgLevelBits) =/= 0) { res.v := false }
    }
    (res, (tmp.ppn >> ppnBits) =/= 0)
  }
  val traverse = pte.table() && !invalid_paddr && count < pgLevels-1
  val pte_addr = if (!usingVM) 0.U else {
    val vpn_idxs = (0 until pgLevels).map(i => (r_req.addr >> (pgLevels-i-1)*pgLevelBits)(pgLevelBits-1,0))
    val vpn_idx = vpn_idxs(count)
    Cat(r_pte.ppn, vpn_idx) << log2Ceil(xLen/8)
  }
  val fragmented_superpage_ppn = {
    val choices = (pgLevels-1 until 0 by -1).map(i => Cat(r_pte.ppn >> (pgLevelBits*i), r_req.addr(pgLevelBits*i-1, 0)))
    choices(count)
  }

  when (arb.io.out.fire()) {
    r_req := arb.io.out.bits.bits
    r_req_dest := arb.io.chosen
  }

  val (pte_cache_hit, pte_cache_data) = {
    val size = 1 << log2Up(pgLevels * 2)
    val plru = new PseudoLRU(size)
    val valid = RegInit(0.U(size.W))
    val tags = Reg(Vec(size, UInt(width = paddrBits)))
    val data = Reg(Vec(size, UInt(width = ppnBits)))

    val hits = tags.map(_ === pte_addr).asUInt & valid
    val hit = hits.orR
    when (mem_resp_valid && traverse && !hit && !invalidated) {
      val r = Mux(valid.andR, plru.replace, PriorityEncoder(~valid))
      valid := valid | UIntToOH(r)
      tags(r) := pte_addr
      data(r) := pte.ppn
    }
    when (hit && state === s_req) { plru.access(OHToUInt(hits)) }
    when (io.dpath.sfence.valid && !io.dpath.sfence.bits.rs1) { valid := 0.U }

    for (i <- 0 until pgLevels-1)
      ccover(hit && state === s_req && count === i, s"PTE_CACHE_HIT_L$i", s"PTE cache hit, level $i")

    (hit && count < pgLevels-1, Mux1H(hits, data))
  }

  val l2_refill = RegNext(false.B)
  io.dpath.perf.l2miss := false
  val (l2_hit, l2_error, l2_pte, l2_tlb_ram) = if (coreParams.nL2TLBEntries == 0) (false.B, false.B, Wire(new PTE), None) else {
    // TODO: ECC mechanism
    val nL2TLBSets = 128
    require(isPow2(coreParams.nL2TLBEntries))
    require(isPow2(nL2TLBSets))
    
    val idxBits = log2Ceil(nL2TLBSets)
    val tagBits = vpnBits - idxBits
    val (r_tag, r_idx) = Split(r_req.addr, idxBits)

    class Entry extends Bundle {
      val tag = UInt(width = tagBits)
      val ppn = UInt(width = ppnBits)
      val d = Bool()
      val a = Bool()
      val u = Bool()
      val x = Bool()
      val w = Bool()
      val r = Bool()

      override def cloneType = new Entry().asInstanceOf[this.type]
    }

    val entrySize = new Entry().getWidth    
    val (ram, omSRAM) =  DescribedSRAM(
      name = "l2_tlb_ram",
      desc = "L2 TLB",
      size = nL2TLBSets,
      data = Vec(coreParams.nL2TLBEntries, UInt(width = entrySize))
    )

    val g_array = Reg(Vec(nL2TLBSets, UInt(width = coreParams.nL2TLBEntries)))
    val valid_array = RegInit(Vec(Seq.fill(nL2TLBSets)(0.U(coreParams.nL2TLBEntries.W))))
    
    val rand_repl = new RandomReplacement(coreParams.nL2TLBEntries)
    when (l2_refill && !invalidated) {
      val waddr = if (coreParams.nL2TLBEntries > 1) Mux(valid_array(r_idx).andR, rand_repl.way, PriorityEncoder(~valid_array(r_idx))) else 0.U
      val offset = if (coreParams.nL2TLBEntries > 1) Vec(UIntToOH(waddr).toBools) else Vec(true.B)
      val entry_array = Wire(Vec(coreParams.nL2TLBEntries, UInt(width = entrySize)))
      val entry = Wire(new Entry)
      entry := r_pte
      entry.tag := r_tag

      entry_array(waddr) := entry.asUInt
      ram.write(r_idx, entry_array, offset)

      val mask = UIntToOH(waddr)
      valid_array(r_idx) := valid_array(r_idx) | mask
      g_array(r_idx) := Mux(r_pte.g, g_array(r_idx) | mask, g_array(r_idx) & ~mask)

    }

    // It is hard to flush an entry from the SyncReadMem when
    // we have a set-associative organization. For now either
    // flush the selected set or the whole TLB. 
    when (io.dpath.sfence.valid) { 
      val fence_addr_idx = (io.dpath.sfence.bits.addr >> pgIdxBits).extract(idxBits-1, 0)
      when (io.dpath.sfence.bits.rs1) {  
        valid_array(fence_addr_idx) := 0.U // TODO: Temporary solution
      } .otherwise { // TODO: Temporary solution
          for (i <- 0 until nL2TLBSets) {
             valid_array(i) := 0.U
          }
      }
    }

    val s0_lookup_idx = arb.io.out.bits.bits.addr.extract(idxBits-1, 0)
    val s0_valid = !l2_refill && arb.io.out.fire()
    val s1_valid = RegNext(s0_valid && arb.io.out.bits.valid)
    val s2_valid = RegNext(s1_valid)
    val s1_rdata = ram.read(s0_lookup_idx, s0_valid) 
    val s2_rdata = RegEnable(s1_rdata, s1_valid)

    val s2_valid_lookup = RegEnable(valid_array(r_idx), s1_valid)
    val s2_g_lookup = RegEnable(g_array(r_idx), s1_valid)

    val s2_set = s2_rdata.map(_.asTypeOf(new Entry))
    val hit_vec = s2_set.map(_.tag === r_tag).asUInt & s2_valid_lookup
    
    val s2_hit = s2_valid && hit_vec.orR
    io.dpath.perf.l2miss := s2_valid && !hit_vec.orR 
    when (s2_valid && !hit_vec.orR) { rand_repl.miss}

    val s2_pte = Wire(new PTE)
    s2_pte := s2_set(OHToUInt(hit_vec))
    s2_pte.g := s2_g_lookup(OHToUInt(hit_vec))
    s2_pte.v := true

    ccover(s2_hit, "L2_TLB_HIT", "L2 TLB hit")
    (s2_hit, false.B, s2_pte, Some(ram))
  }

  // if SFENCE occurs during walk, don't refill PTE cache or L2 TLB until next walk
  invalidated := io.dpath.sfence.valid || (invalidated && state =/= s_ready)

  io.mem.req.valid := state === s_req || state === s_dummy1
  io.mem.req.bits.phys := Bool(true)
  io.mem.req.bits.cmd  := M_XRD
  io.mem.req.bits.size := log2Ceil(xLen/8)
  io.mem.req.bits.signed := false
  io.mem.req.bits.addr := pte_addr
  io.mem.s1_kill := l2_hit || state =/= s_wait1
  io.mem.s2_kill := Bool(false)

  val pageGranularityPMPs = pmpGranularity >= (1 << pgIdxBits)
  val pmaPgLevelHomogeneous = (0 until pgLevels) map { i =>
    val pgSize = BigInt(1) << (pgIdxBits + ((pgLevels - 1 - i) * pgLevelBits))
    if (pageGranularityPMPs && i == pgLevels - 1) {
      require(TLBPageLookup.homogeneous(edge.manager.managers, pgSize), s"All memory regions must be $pgSize-byte aligned")
      true.B
    } else {
      TLBPageLookup(edge.manager.managers, xLen, p(CacheBlockBytes), pgSize)(pte_addr).homogeneous
    }
  }
  val pmaHomogeneous = pmaPgLevelHomogeneous(count)
  val pmpHomogeneous = new PMPHomogeneityChecker(io.dpath.pmp).apply(pte_addr >> pgIdxBits << pgIdxBits, count)
  val homogeneous = pmaHomogeneous && pmpHomogeneous

  for (i <- 0 until io.requestor.size) {
    io.requestor(i).resp.valid := resp_valid(i)
    io.requestor(i).resp.bits.ae := resp_ae
    io.requestor(i).resp.bits.pte := r_pte
    io.requestor(i).resp.bits.level := count
    io.requestor(i).resp.bits.homogeneous := homogeneous || pageGranularityPMPs
    io.requestor(i).resp.bits.fragmented_superpage := resp_fragmented_superpage && pageGranularityPMPs
    io.requestor(i).ptbr := io.dpath.ptbr
    io.requestor(i).customCSRs := io.dpath.customCSRs
    io.requestor(i).status := io.dpath.status
    io.requestor(i).pmp := io.dpath.pmp
  }

  // control state machine
  val next_state = Wire(init = state)
  state := OptimizationBarrier(next_state)

  switch (state) {
    is (s_ready) {
      when (arb.io.out.fire()) {
        next_state := Mux(arb.io.out.bits.valid, s_req, s_ready)
      }
      count := pgLevels - minPgLevels - io.dpath.ptbr.additionalPgLevels
    }
    is (s_req) {
      when (pte_cache_hit) {
        count := count + 1
      }.otherwise {
        next_state := Mux(io.mem.req.ready, s_wait1, s_req)
      }
    }
    is (s_wait1) {
      // This Mux is for the l2_error case; the l2_hit && !l2_error case is overriden below
      next_state := Mux(l2_hit, s_req, s_wait2)
    }
    is (s_wait2) {
      next_state := s_wait3
      when (io.mem.s2_xcpt.ae.ld) {
        resp_ae := true
        next_state := s_ready
        resp_valid(r_req_dest) := true
      }
    }
    is (s_fragment_superpage) {
      next_state := s_ready
      resp_valid(r_req_dest) := true
      resp_ae := false
      when (!homogeneous) {
        count := pgLevels-1
        resp_fragmented_superpage := true
      }
    }
  }

  def makePTE(ppn: UInt, default: PTE) = {
    val pte = Wire(init = default)
    pte.ppn := ppn
    pte
  }
  r_pte := OptimizationBarrier(
    Mux(mem_resp_valid, pte,
    Mux(l2_hit && !l2_error, l2_pte,
    Mux(state === s_fragment_superpage && !homogeneous, makePTE(fragmented_superpage_ppn, r_pte),
    Mux(state === s_req && pte_cache_hit, makePTE(pte_cache_data, l2_pte),
    Mux(arb.io.out.fire(), makePTE(io.dpath.ptbr.ppn, r_pte),
    r_pte))))))

  when (l2_hit && !l2_error) {
    assert(state === s_req || state === s_wait1)
    next_state := s_ready
    resp_valid(r_req_dest) := true
    resp_ae := false
    count := pgLevels-1
  }
  when (mem_resp_valid) {
    assert(state === s_wait3)
    when (traverse) {
      next_state := s_req
      count := count + 1
    }.otherwise {
      l2_refill := pte.v && !invalid_paddr && count === pgLevels-1
      val ae = pte.v && invalid_paddr
      resp_ae := ae
      when (pageGranularityPMPs && count =/= pgLevels-1 && !ae) {
        next_state := s_fragment_superpage
      }.otherwise {
        next_state := s_ready
        resp_valid(r_req_dest) := true
      }
    }
  }
  when (io.mem.s2_nack) {
    assert(state === s_wait2)
    next_state := s_req
  }

  for (i <- 0 until pgLevels) {
    val leaf = mem_resp_valid && !traverse && count === i
    ccover(leaf && pte.v && !invalid_paddr, s"L$i", s"successful page-table access, level $i")
    ccover(leaf && pte.v && invalid_paddr, s"L${i}_BAD_PPN_MSB", s"PPN too large, level $i")
    ccover(leaf && !mem_resp_data(0), s"L${i}_INVALID_PTE", s"page not present, level $i")
    if (i != pgLevels-1)
      ccover(leaf && !pte.v && mem_resp_data(0), s"L${i}_BAD_PPN_LSB", s"PPN LSBs not zero, level $i")
  }
  ccover(mem_resp_valid && count === pgLevels-1 && pte.table(), s"TOO_DEEP", s"page table too deep")
  ccover(io.mem.s2_nack, "NACK", "D$ nacked page-table access")
  ccover(state === s_wait2 && io.mem.s2_xcpt.ae.ld, "AE", "access exception while walking page table")

  } // leaving gated-clock domain

  private def ccover(cond: Bool, label: String, desc: String)(implicit sourceInfo: SourceInfo) =
    if (usingVM) cover(cond, s"PTW_$label", "MemorySystem;;" + desc)
}

/** Mix-ins for constructing tiles that might have a PTW */
trait CanHavePTW extends HasTileParameters with HasHellaCache { this: BaseTile =>
  val module: CanHavePTWModule
  var nPTWPorts = 1
  nDCachePorts += usingPTW.toInt
}

trait CanHavePTWModule extends HasHellaCacheModule {
  val outer: CanHavePTW
  val ptwPorts = ListBuffer(outer.dcache.module.io.ptw)
  val ptw = Module(new PTW(outer.nPTWPorts)(outer.dcache.node.edges.out(0), outer.p))
  if (outer.usingPTW)
    dcachePorts += ptw.io.mem
}
