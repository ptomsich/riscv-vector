package smartVector
import chisel3._
import chisel3.util._
import darecreek.VDecode
import utils._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import chipsalliance.rocketchip.config.{Config, Field, Parameters}
import xiangshan.MicroOp
import SmartParam._

class VLSUXcpt extends Bundle {
    val exception_vld   = Bool()
    val update_vl       = Bool()
    val update_data     = UInt(bVL.W)
    val xcpt_cause      = new HellaCacheExceptions()
}

class LdstIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
    val mUop            = Input(ValidIO(new Muop()(p)))
    val mUopMergeAttr   = Input(ValidIO(new MuopMergeAttr))
    val lsuOut          = Output(ValidIO(new LsuOutput))
    val xcpt            = Output(new VLSUXcpt)
    val dataExchange    = new RVUMemory()
    val lsuReady        = Output(Bool())
}

object VRegSegmentStatus {
  val invalid :: srcData :: needLdst :: notReady :: ready :: xcpt :: Nil = Enum(6)
}

object Mop {
    val unit_stride     = "b0001".U
    val index_unodered  = "b0010".U
    val constant_stride = "b0100".U
    val index_ordered   = "b1000".U
}

object UnitStrideMop {
    val not_unit_strde   = "b11111".U
    val unit_stride      = "b00000".U
    val whole_register   = "b01000".U
    val mask             = "b01011".U
    val fault_only_first = "b10000".U
}

object VMemCmd {
    val read  = false.B
    val write = true.B
}

object LdstUopXcptCause {
    val misalign = 1.U
    val mem_xcpt = 2.U
}

class LdstUop extends Bundle {
    val valid = Bool()
    val addr  = UInt(64.W)
    val pos   = UInt(bVL.W) // position in vl
    val xcpt  = UInt(2.W)
}

class VRegSegmentInfo extends Bundle {
    // VRegSegmentStatus
    val status  = UInt(3.W)
    // corresponding ldstuop idx of current vreg segement
    val idx     = UInt(ldstUopQueueWidth.W)
    // offset of writeback valid data for current vreg segement
    val offset  = UInt(log2Ceil(8).W)
    // data of current vreg segement
    val data    = UInt(8.W)
}

class mUopInfo extends Bundle {
    val uopIdx      = UInt(3.W)
    val uopEnd      = Bool()
    val segIdx      = UInt(log2Ceil(8).W)

    val rs1Val      = UInt(XLEN.W)
    val rs2Val      = UInt(XLEN.W)
    val vs2         = UInt(VLEN.W)
    val mask        = UInt(VLEN.W)

    // merge attr
    val muopEnd     = Bool()
    val rfWriteEn   = Bool()
    val ldest       = UInt(5.W)

}

object mUopInfoSelecter {
    def apply(mUop: Muop, mUopMergeAttr: MuopMergeAttr): mUopInfo = {
        val info        = Wire(new mUopInfo)

        info.uopIdx     := mUop.uop.uopIdx
        info.uopEnd     := mUop.uop.uopEnd
        info.segIdx     := mUop.uop.segIndex

        info.rs1Val     := mUop.scalar_opnd_1
        info.rs2Val     := mUop.scalar_opnd_2
        info.vs2        := mUop.uopRegInfo.vs2
        info.mask       := mUop.uopRegInfo.mask

        info.muopEnd    := mUopMergeAttr.muopEnd
        info.rfWriteEn  := mUopMergeAttr.rfWriteEn
        info.ldest      := mUopMergeAttr.ldest

        info
    }
}

class LSULdstCtrl extends Bundle {
    val isLoad          = Bool()
    val isStore         = Bool()
    val nfield          = UInt(3.W)
    val ldstType        = UInt(4.W)
    val unitSMop        = UInt(5.W)

    val vm              = Bool()
    val memwb           = UInt(8.W)
    val eewb            = UInt(8.W)
    val log2Memwb       = UInt(log2Ceil(8).W)
    val log2Eewb        = UInt(log2Ceil(8).W)

    val mlen            = UInt(vlenbWidth.W)
    val elen            = UInt(vlenbWidth.W)
    val minLen          = UInt(vlenbWidth.W)
    val log2Mlen        = UInt(log2Ceil(vlenbWidth).W)
    val log2Elen        = UInt(log2Ceil(vlenbWidth).W)
    val log2MinLen      = UInt(log2Ceil(vlenbWidth).W)
}

object LSULdstDecoder {
    def apply(mUop: Muop, mUopMergeAttr: MuopMergeAttr): LSULdstCtrl = {
        val (funct6, funct3) = (mUop.uop.ctrl.funct6, mUop.uop.ctrl.funct3)
        val (vsew, vm)       = (mUop.uop.info.vsew, mUop.uop.ctrl.vm)
        val unitStrdeMop     =  mUop.uop.ctrl.vs2

        val nf   = funct6(5, 3)
        val mop  = funct6(1, 0)
        val ctrl = Wire(new LSULdstCtrl)

        ctrl.isLoad   := mUop.uop.ctrl.load
        ctrl.isStore  := mUop.uop.ctrl.store

        ctrl.ldstType := MuxLookup(funct6(1, 0), Mop.unit_stride, Seq(
            "b00".U -> Mop.unit_stride,     "b01".U -> Mop.index_unodered,
            "b10".U -> Mop.constant_stride, "b11".U -> Mop.index_ordered
        ))
        ctrl.unitSMop := Mux((mop === "b00".U), unitStrdeMop, UnitStrideMop.not_unit_strde)
        // eew and sew in bytes calculation
        val sewb   = 1.U << vsew
        ctrl.eewb := MuxLookup(Cat(funct6(2), funct3), 1.U, Seq("b0000".U -> 1.U, "b0101".U -> 2.U, "b0110".U -> 4.U, "b0111".U -> 8.U))


        ctrl.memwb      := Mux(ctrl.ldstType === Mop.index_ordered || ctrl.ldstType === Mop.index_unodered, sewb, ctrl.eewb)
        ctrl.nfield     := Mux(ctrl.unitSMop === UnitStrideMop.whole_register, 1.U, nf +& 1.U)
        ctrl.vm         := vm
        // 1->0, 2->1, 4->2, 8->3
        ctrl.log2Memwb  := Mux1H(ctrl.memwb, Seq(0.U, 1.U, 2.U, 3.U))
        ctrl.log2Eewb   := Mux1H(ctrl.eewb,  Seq(0.U, 1.U, 2.U, 3.U))
        ctrl.mlen       := vlenb.U >> ctrl.log2Memwb
        ctrl.elen       := vlenb.U >> ctrl.log2Eewb
        ctrl.log2Mlen   := Mux1H(ctrl.mlen, Seq(0.U, 1.U, 2.U, 3.U, 4.U))
        ctrl.log2Elen   := Mux1H(ctrl.elen, Seq(0.U, 1.U, 2.U, 3.U, 4.U))
        ctrl.minLen     := ctrl.elen min ctrl.mlen
        ctrl.log2MinLen := ctrl.log2Elen min ctrl.log2Mlen
        
        ctrl
    }
}



class SVlsu(implicit p: Parameters) extends Module {
    val io = IO(new LdstIO())

    // split fsm states
    val uop_idle :: uop_split :: uop_split_finish :: Nil = Enum(3)
    val uopState        = RegInit(uop_idle)
    val nextUopState    = WireInit(uop_idle)
    val completeLdst    = WireInit(false.B)
    val stopSplit       = WireInit(false.B)

    // address reg
    val addrReg         = RegInit(0.U(64.W))

    // uop & control related
    val mUopInfoReg     = RegInit(0.U.asTypeOf(new mUopInfo))
    val ldstCtrlReg     = RegInit(0.U.asTypeOf(new LSULdstCtrl))

    // vreg seg info
    val vregInitVal     = Wire(new VRegSegmentInfo)
    vregInitVal.status := VRegSegmentStatus.invalid
    vregInitVal.idx    := ldstUopQueueSize.U
    vregInitVal.offset := DontCare
    vregInitVal.data   := DontCare

    val vregInfo        = RegInit(VecInit(Seq.fill(vlenb)(vregInitVal)))

    // Split info
    val splitCount      = RegInit(0.U(vlenbWidth.W))
    val curSplitIdx     = RegInit(0.U(vlenbWidth.W))
    val splitStart      = RegInit(0.U(vlenbWidth.W))

    // ldQueue
    val ldstEnqPtr      = RegInit(0.U(ldstUopQueueWidth.W))
    val issueLdstPtr    = RegInit(0.U(ldstUopQueueWidth.W))
    val ldstUopQueue    = RegInit(VecInit(Seq.fill(ldstUopQueueSize)(0.U.asTypeOf(new LdstUop))))

    // xcpt info
    val xcptVlReg       = RegInit(0.U(bVL.W))
    val hellaXcptReg    = RegInit(0.U.asTypeOf(new HellaCacheExceptions))

    // val hasXcptHappened
    // assertion
    // exception only once
    val addrMisalign    = WireInit(false.B)
    val memXcpt        = io.dataExchange.xcpt.asUInt.orR
    val hasXcpt         = RegInit(false.B)

    /****************************SPLIT STAGE*********************************/
    /*
                                                     splitId+1
                    +--------+                       +--------+
                    |        |                       |        |
                    |   +----+---+  mUop.Valid  +----+----+   |
                    |-> |uop_idle|--------------|uop_split| <-|
                        +---+----+              +----+----+
                            |                        |
                completeLdst|                        |stopSplit
                            |   +----------------+   |
                            |-> |uop_split_finish| <-|
                                +----------------+

    */        

    io.lsuReady := Mux(uopState === uop_idle, true.B, false.B)
    // SPLIT FSM -- decide next state
    when(uopState === uop_idle) {
        when(io.mUop.valid && io.mUop.bits.uop.ctrl.isLdst) {
            nextUopState := uop_split
        }.otherwise {
            nextUopState := uop_idle
        }
    }.elsewhen(uopState === uop_split) {
        when(stopSplit) {
            nextUopState := uop_split_finish
        }.otherwise {
            nextUopState := uop_split
        }
    }.elsewhen(uopState === uop_split_finish) {
        when(completeLdst) {
            nextUopState := uop_idle
        }.otherwise {
            nextUopState := uop_split_finish
        }
    }.otherwise {
        nextUopState := uop_idle
    }
    // SPLIT FSM -- transition
    uopState := nextUopState

    // if exception occurs or split finished, stop split
    stopSplit := memXcpt || (uopState === uop_split && curSplitIdx < splitCount && addrMisalign) ||
                (splitCount - 1.U === curSplitIdx) || (splitCount === 0.U)

    /*****************************SPLIT -- IDLE stage****************************************/
    val (vstart, vl)     = (io.mUop.bits.uop.info.vstart, io.mUop.bits.uop.info.vl)
    val (uopIdx, uopEnd) = (io.mUop.bits.uop.uopIdx, io.mUop.bits.uop.uopEnd)
    
    val ldstCtrl = LSULdstDecoder(io.mUop.bits, io.mUopMergeAttr.bits)

    // decide micro vl
    val actualVl    = Mux(ldstCtrl.unitSMop === UnitStrideMop.mask, (vl + 7.U) >> 3.U, vl) // ceil(vl/8)
    val doneLen     = uopIdx << ldstCtrl.log2MinLen
    val leftLen     = Mux(ldstCtrl.unitSMop === UnitStrideMop.whole_register, ldstCtrl.mlen, Mux(actualVl > doneLen, actualVl - doneLen, 0.U))
    val microVl     = ldstCtrl.minLen min leftLen
    val microVstart = Mux(vstart < doneLen, 0.U, ldstCtrl.minLen min (vstart - doneLen))

    val memVl       = leftLen min ldstCtrl.mlen
    val memVstart   = Mux(vstart < doneLen, 0.U, ldstCtrl.mlen min (vstart - doneLen))
    
    val vregClean   = vregInfo.forall(info => info.status === VRegSegmentStatus.invalid)

    when(uopState === uop_idle) {
        when(io.mUop.valid && io.mUop.bits.uop.ctrl.isLdst) {
            mUopInfoReg  := mUopInfoSelecter(io.mUop.bits, io.mUopMergeAttr.bits)
            ldstCtrlReg  := ldstCtrl
            addrReg      := Mux(io.mUop.bits.uop.uopIdx === 0.U, io.mUop.bits.scalar_opnd_1, addrReg)
            // Set split info
            ldstEnqPtr   := 0.U
            issueLdstPtr := 0.U
            curSplitIdx  := 0.U
            splitCount   := microVl  
            splitStart   := microVstart
            // set vreg
            when(vregClean) {
                (0 until vlenb).foreach { i => 
                    val pos = i.U >> ldstCtrl.log2Memwb
                    vregInfo(i).data   := io.mUop.bits.uopRegInfo.old_vd(8 * i + 7, 8 * i)
                    vregInfo(i).status := Mux(pos < memVl, VRegSegmentStatus.needLdst, VRegSegmentStatus.srcData)
                }
            }
        }
    }

    /*-----------------------------------------calc addr start-------------------------------------------------*/
    /*                                                                                                         */
    val addr        = WireInit(0.U(64.W))
    val addrMask    = WireInit(0.U(64.W))
    val alignedAddr = WireInit(0.U(64.W))
    val offset      = WireInit(0.U(log2Ceil(8).W))

    val curVl       = (mUopInfoReg.uopIdx << ldstCtrlReg.log2MinLen) + curSplitIdx
    val baseSegIdx  = (curVl - ((curVl >> ldstCtrlReg.log2Mlen) << ldstCtrlReg.log2Mlen)) << ldstCtrlReg.log2Memwb

    val isNotMasked = mUopInfoReg.mask(curVl)
    val baseAddr    = mUopInfoReg.rs1Val

    // indexed addr
    val idxVal      = WireInit(0.U(XLEN.W))
    val idxMask     = WireInit(0.U(XLEN.W))
    val eew         = ldstCtrlReg.eewb << 3.U
    val beginIdx    = (curVl - ((curVl >> ldstCtrlReg.log2Elen) << ldstCtrlReg.log2Elen)) << (ldstCtrlReg.log2Eewb + 3.U) // 3.U: to bits
   
    idxMask := (("h1".asUInt(64.W) << eew) - 1.U)
    idxVal  := (mUopInfoReg.vs2 >> beginIdx) & idxMask

    val curUnitStrideAddr = Mux(
        curSplitIdx === 0.U && mUopInfoReg.uopIdx === 0.U, 
        addrReg + (mUopInfoReg.segIdx << ldstCtrlReg.log2Memwb),
        addrReg + (ldstCtrlReg.nfield << ldstCtrlReg.log2Memwb)
    )

    val curStridedAddr = Mux(
        curSplitIdx === 0.U && mUopInfoReg.uopIdx === 0.U, 
        addrReg + (mUopInfoReg.segIdx << ldstCtrlReg.log2Memwb),
        (Cat(false.B, addrReg).asSInt + (mUopInfoReg.rs2Val).asSInt).asUInt
    )

    addr := Mux1H(ldstCtrlReg.ldstType, Seq(
        // unit stride
        curUnitStrideAddr,
        // index_unodered
        ((mUopInfoReg.segIdx << ldstCtrlReg.log2Memwb) + (Cat(false.B, baseAddr).asSInt + idxVal.asSInt).asUInt),
        // strided
        curStridedAddr,
        // index_odered
        ((mUopInfoReg.segIdx << ldstCtrlReg.log2Memwb) + (Cat(false.B, baseAddr).asSInt + idxVal.asSInt).asUInt)
    ))

    addrMask     := (("h1".asUInt(64.W) << ldstCtrlReg.log2Memwb) - 1.U)
    addrMisalign := (addr & addrMask).orR  // align addr to memwb ?
    alignedAddr  := (addr >> 3.U) << 3.U // align addr to 64 bits
    offset       := addr - alignedAddr 
    /*                                                                                                         */
    /*-----------------------------------------calc addr end---------------------------------------------------*/

    when(uopState === uop_split && !memXcpt && curSplitIdx < splitCount) {
        val maskCond = (ldstCtrlReg.vm || isNotMasked) && (curSplitIdx >= splitStart)
        when(maskCond) {
            ldstUopQueue(ldstEnqPtr).valid  := true.B
            ldstUopQueue(ldstEnqPtr).addr   := alignedAddr
            ldstUopQueue(ldstEnqPtr).pos    := curVl
            ldstUopQueue(ldstEnqPtr).xcpt   := Mux(addrMisalign, LdstUopXcptCause.misalign, 0.U)
            ldstEnqPtr  := ldstEnqPtr + 1.U
        }

        (0 until vlenb).foreach { i =>
            when((i.U >> ldstCtrlReg.log2Memwb) === (baseSegIdx >> ldstCtrlReg.log2Memwb)) {
                vregInfo(i).status  := Mux(maskCond && !addrMisalign, VRegSegmentStatus.notReady, VRegSegmentStatus.srcData)
                vregInfo(i).idx     := Mux(maskCond && !addrMisalign, ldstEnqPtr, vregInfo(i).idx)
                vregInfo(i).offset  := Mux(maskCond && !addrMisalign, offset + (i.U - baseSegIdx), vregInfo(i).offset)
            }
        }
        curSplitIdx := curSplitIdx + 1.U
        addrReg     := addr
    }
    /*----------------------------SPLIT -- IDLE stage----------------------------*/


    /*********************************ISSUE START*********************************/
    val isNoXcptUop = ldstUopQueue(issueLdstPtr).valid && (ldstUopQueue(issueLdstPtr).xcpt === 0.U)
    // update issueLdPtr
    // <= or < ?
    when(io.dataExchange.resp.bits.nack && io.dataExchange.resp.bits.idx <= issueLdstPtr) {
        issueLdstPtr := io.dataExchange.resp.bits.idx
    }.elsewhen(isNoXcptUop && io.dataExchange.req.ready) {
        issueLdstPtr := issueLdstPtr + 1.U
    }


    when(isNoXcptUop) {
        val storeDataVec = VecInit(Seq.fill(8)(0.U(8.W)))
        val storeMaskVec = VecInit(Seq.fill(8)(0.U(1.W)))

        (0 until vlenb).foreach { i =>
            when(ldstCtrlReg.isStore && vregInfo(i).status === VRegSegmentStatus.notReady && vregInfo(i).idx === issueLdstPtr) {
                val offset = vregInfo(i).offset
                storeDataVec(offset) := vregInfo(i).data
                storeMaskVec(offset) := 1.U
            }
        }
        io.dataExchange.req.valid       := true.B
        io.dataExchange.req.bits.addr   := ldstUopQueue(issueLdstPtr).addr
        io.dataExchange.req.bits.cmd    := Mux(ldstCtrlReg.isLoad, VMemCmd.read, VMemCmd.write)
        io.dataExchange.req.bits.idx    := issueLdstPtr
        io.dataExchange.req.bits.data   := Mux(ldstCtrlReg.isLoad, DontCare, storeDataVec.asUInt)
        io.dataExchange.req.bits.mask   := Mux(ldstCtrlReg.isLoad, DontCare, storeMaskVec.asUInt)
    }.otherwise {
        io.dataExchange.req.valid       := false.B
        io.dataExchange.req.bits        := DontCare
    }

    /*---------------------------------ISSUE END---------------------------------*/


    /*********************************RESP START*********************************/
    val (respLdstPtr, respData) = (io.dataExchange.resp.bits.idx, io.dataExchange.resp.bits.data)
    val lastXcptInfo = RegInit(0.U.asTypeOf(new HellaCacheExceptions))
    val lastXcptIdx  = RegInit(ldstUopQueueSize.U(ldstUopQueueWidth.W))

    when(io.dataExchange.resp.valid && uopState =/= uop_idle && respLdstPtr < lastXcptIdx) {
        val loadComplete  = ldstCtrlReg.isLoad && io.dataExchange.resp.bits.has_data
        val storeComplete = ldstCtrlReg.isStore

        ldstUopQueue(respLdstPtr).valid := false.B

        (0 until vlenb).foreach { i =>
            when(vregInfo(i).status === VRegSegmentStatus.notReady && vregInfo(i).idx === respLdstPtr) {
                 vregInfo(i).status := VRegSegmentStatus.ready
            } 
        }

        when(loadComplete) {
            (0 until vlenb).foreach { i =>
                when(vregInfo(i).status === VRegSegmentStatus.notReady && vregInfo(i).idx === respLdstPtr) {
                    val offsetOH = UIntToOH(vregInfo(i).offset, 8)
                    vregInfo(i).data := Mux1H(
                        offsetOH, 
                        Seq(respData( 7,  0), respData(15,  8), respData(23, 16), respData(31, 24),
                            respData(39, 32), respData(47, 40), respData(55, 48), respData(63, 56))
                    )
                }
            }
         }

    }
    
    val ldstMinValidIdx = PriorityEncoder(ldstUopQueue.map(uop => uop.valid))
    val ldstMinXcptIdx  = PriorityEncoder(ldstUopQueue.map(uop => uop.valid & (uop.xcpt =/= 0.U)))

    when(memXcpt && ldstUopQueue(respLdstPtr).valid && respLdstPtr < lastXcptIdx) { // exception handling
        // 2. update xcpt info
        ldstUopQueue(respLdstPtr).xcpt := LdstUopXcptCause.mem_xcpt
        lastXcptIdx  := respLdstPtr
        lastXcptInfo := io.dataExchange.xcpt
    }
    
    val misalignXcpt    = Wire(new HellaCacheExceptions)
    misalignXcpt.ma.ld := ldstCtrlReg.isLoad
    misalignXcpt.ma.st := ldstCtrlReg.isStore
    misalignXcpt.pf.ld := false.B
    misalignXcpt.pf.st := false.B
    misalignXcpt.gf.ld := false.B
    misalignXcpt.gf.st := false.B
    misalignXcpt.ae.ld := false.B
    misalignXcpt.ae.st := false.B

    when(ldstMinValidIdx === ldstMinXcptIdx && (ldstMinValidIdx =/= (ldstUopQueueSize - 1).U)) {
        // 1. clear ldstUopQueue
        ldstUopQueue.foreach(uop => uop.valid := false.B)
        // 2. update xcpt info
        xcptVlReg       := ldstUopQueue(ldstMinValidIdx).pos

        hellaXcptReg       := Mux(ldstUopQueue(ldstMinValidIdx).xcpt === LdstUopXcptCause.misalign, misalignXcpt, lastXcptInfo)
        hasXcpt            := true.B
    }

    completeLdst := ldstUopQueue.forall(uop => uop.valid === false.B) // ld completed or xcpt happened
    
    /*---------------------------------RESP END---------------------------------*/

    /*****************************writeback to uopQueue*************************/
    val vregWbReady = WireInit(false.B)

    val allSrcData = vregInfo.forall(info => info.status === VRegSegmentStatus.srcData)
    val allReadyOrSrcData = vregInfo.forall(info => info.status === VRegSegmentStatus.ready || info.status === VRegSegmentStatus.srcData)

    when(splitCount === 0.U && allSrcData) {
        vregWbReady := RegNext(splitCount === 0.U && allSrcData) // RegNext for scoreboard clear & write contradiction
    }.elsewhen(allReadyOrSrcData && completeLdst) {
        vregWbReady := true.B
    }.otherwise {
        vregWbReady := false.B
    }

    when(vregWbReady || hasXcpt) {
        io.lsuOut.valid             := true.B
        io.lsuOut.bits.data         := Mux(ldstCtrlReg.isLoad, Cat(vregInfo.reverseMap(entry => entry.data)), DontCare) // Concatenate data from all vregInfo elements)
        io.lsuOut.bits.muopEnd      := mUopInfoReg.muopEnd
        io.lsuOut.bits.rfWriteEn    := mUopInfoReg.rfWriteEn
        io.lsuOut.bits.rfWriteIdx   := mUopInfoReg.ldest

        // Reset vreg info
        for (i <- 0 until vlenb) {
            vregInfo(i).status  := VRegSegmentStatus.invalid
            vregInfo(i).idx     := ldstUopQueueSize.U
            vregInfo(i).offset  := DontCare
            vregInfo(i).data    := DontCare
        }
    }.otherwise {
        io.lsuOut.valid := false.B
        io.lsuOut.bits  := DontCare
    }

    // exception output
    when(hasXcpt) {
        when(ldstCtrlReg.unitSMop === UnitStrideMop.fault_only_first && xcptVlReg > 0.U) {
            io.xcpt.exception_vld   := false.B
            io.xcpt.xcpt_cause      := 0.U.asTypeOf(new HellaCacheExceptions)
        }.otherwise {
            io.xcpt.exception_vld   := true.B
            io.xcpt.xcpt_cause      := hellaXcptReg
        }
        io.xcpt.update_vl           := true.B
        io.xcpt.update_data         := xcptVlReg

        // reset xcpt
        hasXcpt     := false.B
        lastXcptIdx := ldstUopQueueSize.U
    }.otherwise {
        io.xcpt.exception_vld       := false.B
        io.xcpt.update_vl           := false.B
        io.xcpt.update_data         := DontCare
        io.xcpt.xcpt_cause          := 0.U.asTypeOf(new HellaCacheExceptions)
    }
}
