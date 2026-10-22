package dev.sakura.xiangqi.app

import dev.sakura.xiangqi.core.*
import dev.sakura.xiangqi.vision.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable data class SessionConfig(val playerSide:Side=Side.RED,val movetimeMs:Int=15000,val orientation:Orientation=Orientation.RED_BOTTOM,val initialTurn:Side=Side.RED,val options:EngineOptions=EngineOptions()) {
    init { require(movetimeMs in listOf(3000,15000,30000)) }
}
enum class SessionPhase { STOPPED,WAITING_BOARD,AWAITING_CONFIRMATION,ANALYZING,WAITING_OPPONENT,READY,PAUSED,ERROR,NEEDS_SYNC }
data class SessionState(val phase:SessionPhase=SessionPhase.STOPPED,val message:String="尚未开始",val position:PositionSnapshot?=null,val calibration:GridCalibration?=null,val key:AnalysisKey?=null,val info:EngineEvent.Info?=null,val historyComplete:Boolean=false,val pendingHistoryIndices:List<Int> = emptyList(),val failureReason:EngineFailureReason?=null)
object ScorePresentation {
    fun playerCp(score:EngineScore,turn:Side,player:Side):Int?=score.cp?.times(if(turn==player)1 else -1)
    fun format(score:EngineScore,turn:Side,player:Side):String {
        val sign=if(turn==player)1 else -1
        return score.mate?.let { if(it*sign>0)"预计 ${kotlin.math.abs(it)} 步将杀" else "面临 ${kotlin.math.abs(it)} 步将杀" }
            ?:playerCp(score,turn,player)?.let { "我方评分 %+.2f".format(java.util.Locale.ROOT,it/100.0) }?:"评分计算中"
    }
}
class SessionCoordinator(
    private val scope:CoroutineScope,private val gateway:EngineGateway,private val overlay:HintOverlay,
    private val screenSize:()->Pair<Int,Int> = {576 to 1280},
    private val restoreCalibration:(PixelFrame)->GridCalibration?={null},
    private val onConfirmed:(ConfirmedPosition)->Unit={},
    classifier:TemplateClassifier=TemplateClassifier.fromResource()
) {
    var diagnosticSample:DiagnosticSample?=null; private set
    private val mutable=MutableStateFlow(SessionState())
    val state:StateFlow<SessionState> = mutable.asStateFlow()
    private val mutex=Mutex()
    private val tracker=GameTracker()
    private val stable=StabilityGate()
    private var recognizer=BoardRecognizer(classifier)
    private val frames=Channel<PixelFrame>(Channel.CONFLATED)
    private val operations=ConcurrentHashMap.newKeySet<Job>()
    private var config=SessionConfig()
    private var session=""
    private var search=0L
    private var transform=0L
    @Volatile private var stopped=true
    private var closed=false
    @Volatile private var paused=false
    private var stopJob:Job?=null
    private var needsReconfirm=false
    private var confirmedDuringPause=false
    private var lastRawBoard:Board?=null
    private var manualBaseline:Board?=null
    private var legal=emptyList<UciMove>()
    private var collector:Job?=null
    private val processor=scope.launch { for(frame in frames)operate { processFrame(frame) } }

    private fun launch(block:suspend ()->Unit):Job {
        val job=scope.launch(start=CoroutineStart.LAZY) { operate(block) }
        operations.add(job)
        job.invokeOnCompletion { operations.remove(job) }
        job.start()
        return job
    }
    private suspend fun operate(block:suspend ()->Unit) {
        mutex.withLock {
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(t:Throwable) { if(!stopped)error(t.message?:"分析失败",(t as? EngineFailureException)?.reason?:EngineFailureReason.GENERIC) }
        }
    }
    private fun error(message:String,reason:EngineFailureReason=EngineFailureReason.GENERIC) {
        mutable.value=mutable.value.copy(key=null,info=null,phase=SessionPhase.ERROR,message="$message；请停止后重试",failureReason=reason)
        overlay.clear(); overlay.status(mutable.value.message)
    }
    private fun update(phase:SessionPhase,message:String) {
        mutable.value=mutable.value.copy(phase=phase,message=message,failureReason=null)
        overlay.status(message)
    }
    private fun recognitionMessage(observation:BoardObservation):String = when(observation.recognitionIssue) {
        RecognitionIssue.OUT_OF_BOUNDS -> "棋盘位置超出画面，请点「校验」后校准棋盘"
        RecognitionIssue.RIVER_OBSTRUCTION -> "棋盘中央有遮挡或动画，请移开遮挡后稍候"
        RecognitionIssue.UNKNOWN_CELLS -> (if(observation.unknownCellCount>0)"有 ${observation.unknownCellCount} 个位置未识别" else "有位置未识别")+"，请点「校验」适配棋子或校准棋盘"
        RecognitionIssue.NONE -> if(observation.motion>.10f)"棋子或动画正在变化，等待稳定棋盘" else "未能识别棋盘，请点「校验」检查棋盘位置与棋子样式"
    }
    private suspend fun stillRunning() { currentCoroutineContext().ensureActive(); check(!stopped) { "会话已停止" } }
    fun start(config:SessionConfig)=launch {
        check(!closed && stopped)
        this.config=config; session=UUID.randomUUID().toString(); stopped=false; paused=false
        mutable.value=SessionState(SessionPhase.WAITING_BOARD,"切换到 JJ 人机棋盘，等待稳定画面")
        overlay.status(mutable.value.message)
    }
    fun onFrame(frame:PixelFrame) { if(!stopped && !paused)frames.trySend(frame) }
    private suspend fun invalidate() {
        val old=mutable.value.key
        mutable.value=mutable.value.copy(key=null,info=null)
        overlay.clear()
        if(mutable.value.phase in listOf(SessionPhase.READY,SessionPhase.ANALYZING))update(SessionPhase.WAITING_BOARD,"画面变化，等待稳定棋盘")
        collector?.cancel(); collector=null
        if(old!=null)withContext(NonCancellable) { gateway.cancel(old.searchId) }
    }
    private suspend fun processFrame(frame:PixelFrame) {
        if(stopped||paused)return
        var calibration=mutable.value.calibration
        if(calibration==null) {
            val restored=restoreCalibration(frame)
            val located=restored?:withContext(Dispatchers.Default) { GridLocator.locate(frame) }?:return
            val (w,h)=screenSize(); val r=located.captureRect
            calibration=located.copy(displayRect=restored?.displayRect?:FloatRect(r.left*w/frame.width,r.top*h/frame.height,r.right*w/frame.width,r.bottom*h/frame.height),orientation=config.orientation,transformRevision=++transform)
            mutable.value=mutable.value.copy(calibration=calibration); overlay.setCalibration(calibration)
        }
        val observation=withContext(Dispatchers.Default) { recognizer.recognize(frame,calibration) }
        stillRunning()
        val expected=manualBaseline?:tracker.current?.snapshot?.board
        if(observation.board==null || observation.board!=expected || observation.occluded || observation.motion>.10f) {
            invalidate()
            if(manualBaseline!=null) { manualBaseline=null; needsReconfirm=true }
            if(observation.board==null||observation.occluded||observation.motion>.10f)update(SessionPhase.WAITING_BOARD,recognitionMessage(observation))
        }
        stable.accept(observation)?.let {
            if(it.board!=null && FenCodec.decode(FenCodec.encode(PositionSnapshot(it.board!!,config.initialTurn))).isSuccess)diagnosticSample=DiagnosticSample(frame,calibration)
            handleObservation(it,calibration)
        }
        val current=mutable.value.key
        val rawMatches=observation.board==(manualBaseline?:tracker.current?.snapshot?.board) && !observation.occluded && observation.motion<=.10f
        overlay.completeSample(frame.sampleGeneration,current.takeIf { rawMatches && !needsReconfirm && !paused && !stopped })
    }
    fun onObservation(observation:BoardObservation,calibration:GridCalibration)=launch {
        if(!stopped && !paused)handleObservation(observation,calibration)
    }
    private suspend fun requireConfirmation(board:Board?,reason:String) {
        needsReconfirm=true; invalidate()
        mutable.value=mutable.value.copy(position=board?.let { PositionSnapshot(it,config.initialTurn) },pendingHistoryIndices=board?.let(tracker::matchingHistory)?:emptyList())
        update(SessionPhase.NEEDS_SYNC,reason)
    }
    private suspend fun handleObservation(observation:BoardObservation,calibration:GridCalibration) {
        val board=observation.board
        if(board==null || observation.occluded || observation.motion>.10f) {
            invalidate()
            if(manualBaseline!=null) { manualBaseline=null; needsReconfirm=true }
            update(SessionPhase.WAITING_BOARD,recognitionMessage(observation)); return
        }
        lastRawBoard=board
        if(mutable.value.calibration?.transformRevision!=calibration.transformRevision) {
            invalidate(); mutable.value=mutable.value.copy(calibration=calibration)
            overlay.setCalibration(calibration); transform=maxOf(transform,calibration.transformRevision)
        }
        if(needsReconfirm) { requireConfirmation(board,"监测曾中断，请校验棋盘、行棋方及是否恢复历史"); return }
        manualBaseline?.let { baseline ->
            if(board!=baseline) { manualBaseline=null; requireConfirmation(board,"校正后的画面已变化，请重新校验") }
            else if(mutable.value.key==null)beginSearch()
            return
        }
        if(tracker.current==null) {
            mutable.value=mutable.value.copy(position=PositionSnapshot(board,config.initialTurn))
            update(SessionPhase.AWAITING_CONFIRMATION,"棋盘已识别，点「校验」确认棋子及行棋方"); return
        }
        when(val result=tracker.accept(observation,legal)) {
            TrackingUpdate.Unchanged -> if(mutable.value.phase !in listOf(SessionPhase.NEEDS_SYNC,SessionPhase.ERROR) && mutable.value.key==null)beginSearch()
            is TrackingUpdate.Confirmed -> { invalidate(); validateAndSearch(result.position) }
            is TrackingUpdate.NeedsConfirmation -> requireConfirmation(result.board,result.reason)
            is TrackingUpdate.Unsynced -> requireConfirmation(result.board,result.reason)
        }
    }
    fun historyCandidate(index:Int)=tracker.historyAt(index)
    fun confirm(snapshot:PositionSnapshot,calibration:GridCalibration,historyIndex:Int?=null)=launch {
        if(stopped)return@launch
        invalidate(); stillRunning(); FenCodec.decode(FenCodec.encode(snapshot)).getOrThrow()
        val candidate=historyIndex?.let {
            val p=requireNotNull(tracker.historyAt(it))
            require(p.snapshot.board==snapshot.board && p.snapshot.sideToMove==snapshot.sideToMove)
            p.history
        }?:PositionHistory(FenCodec.encode(snapshot),emptyList(),snapshot.board==FenCodec.decode(START_FEN).getOrThrow().board && snapshot.sideToMove==Side.RED)
        val valid=gateway.validate(candidate).getOrThrow(); stillRunning()
        require(valid.snapshot.board==snapshot.board && valid.snapshot.sideToMove==snapshot.sideToMove)
        val raw=lastRawBoard
        manualBaseline=raw?.takeIf { it!=snapshot.board }
        val p=tracker.confirmRecovery(snapshot,historyIndex); legal=valid.legalMoves
        transform=maxOf(transform,calibration.transformRevision)
        mutable.value=mutable.value.copy(position=p.snapshot,calibration=calibration,key=null,historyComplete=p.history.historyComplete,pendingHistoryIndices=emptyList())
        needsReconfirm=false; confirmedDuringPause=paused
        overlay.setCalibration(calibration); stable.reset(); onConfirmed(p)
        if(paused)update(SessionPhase.PAUSED,"已确认；返回 JJ 后继续") else beginSearch()
    }
    fun confirmCandidate(candidate:ConfirmedPosition,calibration:GridCalibration)=launch {
        if(stopped)return@launch
        require(mutable.value.position?.board==candidate.snapshot.board) { "上一局与屏幕不同，请重新确认" }
        invalidate(); val valid=gateway.validate(candidate.history).getOrThrow(); stillRunning()
        require(valid.snapshot.board==candidate.snapshot.board && valid.snapshot.sideToMove==candidate.snapshot.sideToMove)
        val restored=tracker.restore(candidate.history); legal=valid.legalMoves
        mutable.value=mutable.value.copy(position=restored.snapshot,calibration=calibration,key=null,historyComplete=restored.history.historyComplete,pendingHistoryIndices=emptyList())
        manualBaseline=null; needsReconfirm=false; confirmedDuringPause=paused
        overlay.setCalibration(calibration); onConfirmed(restored); stable.reset()
        if(paused)update(SessionPhase.PAUSED,"历史已确认；返回 JJ 后继续") else beginSearch()
    }
    private suspend fun validateAndSearch(position:ConfirmedPosition) {
        val valid=gateway.validate(position.history).getOrThrow(); stillRunning()
        require(valid.snapshot.board==position.snapshot.board && valid.snapshot.sideToMove==position.snapshot.sideToMove)
        legal=valid.legalMoves
        mutable.value=mutable.value.copy(position=position.snapshot,historyComplete=position.history.historyComplete)
        onConfirmed(position); beginSearch()
    }
    private fun beginSearch() {
        val p=tracker.current?:return
        if(stopped||paused||needsReconfirm)return
        if(legal.isEmpty()) { update(SessionPhase.READY,"已无合法走法，本局结束"); return }
        if(p.snapshot.sideToMove!=config.playerSide) { overlay.clear(); update(SessionPhase.WAITING_OPPONENT,"等待对方走棋"); return }
        val cal=mutable.value.calibration?:return
        val key=AnalysisKey(session,p.positionRevision,++search,cal.transformRevision)
        mutable.value=mutable.value.copy(key=key,info=null)
        update(SessionPhase.ANALYZING,"正在计算（${config.movetimeMs/1000} 秒）")
        collector=scope.launch {
            try {
                withTimeout(config.movetimeMs.toLong()+10000) { gateway.analyze(AnalysisRequest(key,p.history,config.movetimeMs)).first { event -> mutex.withLock {
                    if(event.key!=mutable.value.key||paused||stopped||needsReconfirm)return@withLock false
                    when(event) {
                        is EngineEvent.Info -> { mutable.value=mutable.value.copy(info=event); overlay.status(progress(event,p.snapshot)) }
                        is EngineEvent.BestMove -> {
                            val move=event.move
                            if(move==null||move !in legal)error("引擎走法无效")
                            else {
                                val text=MoveNotation.format(p.snapshot,move,p.snapshot.sideToMove)
                                if(overlay.show(key,move,text))update(SessionPhase.READY,text+"\n"+(if(manualBaseline!=null)"手动校正，本步有效；变化后再校验" else mutable.value.info?.let { progress(it,p.snapshot) }?:"请在 JJ 手动落子"))
                                else error("悬浮窗权限已关闭")
                            }
                        }
                        is EngineEvent.Failure -> error("引擎异常：${event.message}",event.reason)
                        is EngineEvent.Stopped -> { invalidate(); update(SessionPhase.WAITING_BOARD,"搜索已停止，等待稳定棋盘") }
                    }
                    event !is EngineEvent.Info
                } } }
            } catch(e:TimeoutCancellationException) { mutex.withLock { if(mutable.value.key==key && !stopped)error("引擎未及时返回走法，本次分析已结束") } }
            catch(e:CancellationException) { throw e }
            catch(t:Throwable) { mutex.withLock { if(mutable.value.key==key && !stopped)error(t.message?:"引擎已断开",(t as? EngineFailureException)?.reason?:EngineFailureReason.GENERIC) } }
        }
    }
    private fun progress(info:EngineEvent.Info,p:PositionSnapshot):String =
        "深度 ${info.depth} · ${info.nps/1000} 千节点/秒\n${ScorePresentation.format(info.score,p.sideToMove,config.playerSide)}\n变化 ${info.pv.take(4).joinToString(" ")}"
    fun pause()=launch {
        if(!stopped) {
            if(!paused) { confirmedDuringPause=false; needsReconfirm=needsReconfirm||tracker.current!=null }
            paused=true; invalidate(); stable.reset(); update(SessionPhase.PAUSED,"已暂停，继续前需校验当前局面")
        }
    }
    fun resume()=launch {
        if(!stopped) {
            paused=false; stable.reset()
            if(confirmedDuringPause)needsReconfirm=false
            confirmedDuringPause=false
            update(if(needsReconfirm)SessionPhase.NEEDS_SYNC else SessionPhase.WAITING_BOARD,if(needsReconfirm)"监测曾中断，请重新校验棋盘及行棋方" else "等待新鲜稳定棋盘")
        }
    }
    fun recalibrate(calibration:GridCalibration)=launch {
        if(stopped)return@launch
        invalidate(); recognizer.reset(); stable.reset(); manualBaseline=null; needsReconfirm=true; confirmedDuringPause=false
        mutable.value=mutable.value.copy(calibration=calibration.copy(transformRevision=++transform))
        overlay.setCalibration(mutable.value.calibration!!); update(SessionPhase.NEEDS_SYNC,"标定已更新，请重新校验棋盘")
    }
    fun useTemplates(classifier:TemplateClassifier,calibration:GridCalibration)=launch {
        if(stopped)return@launch
        require(paused) { "请先暂停再适配棋子" }
        invalidate(); stillRunning(); recognizer=BoardRecognizer(classifier); stable.reset()
        lastRawBoard=null; manualBaseline=null; diagnosticSample=null
        needsReconfirm=true; confirmedDuringPause=false
        mutable.value=mutable.value.copy(position=null,calibration=calibration.copy(transformRevision=++transform),pendingHistoryIndices=emptyList())
        overlay.setCalibration(mutable.value.calibration!!)
        update(SessionPhase.PAUSED,"棋子已适配，请确认开局后返回 JJ")
    }
    fun invalidateCoordinates():Job=stop()
    @Synchronized fun stop():Job {
        stopJob?.let { return it }
        stopped=true; paused=true; closed=true; diagnosticSample=null
        mutable.value=SessionState(); overlay.clear(); overlay.status("已停止")
        collector?.cancel(); processor.cancel(); frames.close()
        operations.toList().forEach { it.cancel() }
        return scope.launch(start=CoroutineStart.LAZY) { withContext(NonCancellable) { gateway.close() } }.also { stopJob=it; it.start() }
    }
}
