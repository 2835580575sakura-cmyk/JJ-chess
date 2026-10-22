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

@Serializable data class SessionConfig(val playerSide:Side=Side.RED,val movetimeMs:Int=15000,val orientation:Orientation=Orientation.RED_BOTTOM,val initialTurn:Side=Side.RED,val options:EngineOptions=EngineOptions()) {
    init { require(movetimeMs in listOf(3000,15000,30000)) }
}
enum class SessionPhase { STOPPED,WAITING_BOARD,AWAITING_CONFIRMATION,ANALYZING,WAITING_OPPONENT,READY,PAUSED,ERROR,NEEDS_SYNC }
data class SessionState(val phase:SessionPhase=SessionPhase.STOPPED,val message:String="尚未开始",val position:PositionSnapshot?=null,val calibration:GridCalibration?=null,val key:AnalysisKey?=null,val info:EngineEvent.Info?=null,val historyComplete:Boolean=false,val pendingHistoryIndices:List<Int> = emptyList())
object ScorePresentation {
    fun playerCp(score:EngineScore,turn:Side,player:Side):Int?=score.cp?.times(if(turn==player)1 else -1)
    fun format(score:EngineScore,turn:Side,player:Side):String {
        val sign=if(turn==player)1 else -1
        return score.mate?.let { if(it*sign>0)"预计 ${kotlin.math.abs(it)} 步将杀" else "面临 ${kotlin.math.abs(it)} 步将杀" }
            ?:playerCp(score,turn,player)?.let { "我方评分 %+.2f".format(java.util.Locale.ROOT,it/100.0) }?:"评分计算中"
    }
}
class SessionCoordinator(private val scope:CoroutineScope,private val gateway:EngineGateway,private val overlay:HintOverlay,private val screenSize:()->Pair<Int,Int> = {576 to 1280},private val onConfirmed:(ConfirmedPosition)->Unit={}) {
    private val mutable=MutableStateFlow(SessionState()); val state:StateFlow<SessionState> = mutable.asStateFlow()
    private val mutex=Mutex(); private val tracker=GameTracker(); private val stable=StabilityGate()
    private val recognizer by lazy { BoardRecognizer(TemplateClassifier.fromResource()) }
    private val frames=Channel<PixelFrame>(Channel.CONFLATED)
    private var config=SessionConfig(); private var session=""; private var search=0L; private var transform=0L
    private var stopped=true; private var closed=false; private var paused=false
    private var legal=emptyList<UciMove>(); private var collector:Job?=null
    init { scope.launch { for(frame in frames) operate { processFrame(frame) } } }
    private fun launch(block:suspend ()->Unit)=scope.launch { operate(block) }
    private suspend fun operate(block:suspend ()->Unit) { mutex.withLock { try { block() } catch(e:CancellationException) { throw e } catch(t:Throwable) { mutable.value=mutable.value.copy(key=null,phase=SessionPhase.ERROR,message="${t.message?:"分析失败"}；请停止后重试"); overlay.clear(); overlay.status(mutable.value.message) } } }
    private fun update(phase:SessionPhase,message:String) { mutable.value=mutable.value.copy(phase=phase,message=message); overlay.status(message) }
    fun start(config:SessionConfig)=launch { check(!closed); this.config=config; session=UUID.randomUUID().toString(); stopped=false; paused=false; mutable.value=SessionState(SessionPhase.WAITING_BOARD,"切换到 JJ 人机棋盘，等待稳定画面"); overlay.status(mutable.value.message) }
    fun onFrame(frame:PixelFrame) { if(!stopped && !paused)frames.trySend(frame) }
    private suspend fun invalidate() {
        val old=mutable.value.key; mutable.value=mutable.value.copy(key=null,info=null); overlay.clear()
        collector?.cancel(); collector=null
        if(old!=null)withContext(NonCancellable) { gateway.cancel(old.searchId) }
    }
    private suspend fun processFrame(frame:PixelFrame) {
        if(stopped||paused)return
        var calibration=mutable.value.calibration
        if(calibration==null) {
            val located=withContext(Dispatchers.Default) { GridLocator.locate(frame) }?:return
            val (w,h)=screenSize(); val r=located.captureRect
            calibration=located.copy(displayRect=FloatRect(r.left*w/frame.width,r.top*h/frame.height,r.right*w/frame.width,r.bottom*h/frame.height),orientation=config.orientation,transformRevision=++transform)
            mutable.value=mutable.value.copy(calibration=calibration); overlay.setCalibration(calibration)
        }
        val observation=withContext(Dispatchers.Default) { recognizer.recognize(frame,calibration) }
        if(observation.board!=tracker.current?.snapshot?.board || observation.occluded || observation.motion>.10f)invalidate()
        else mutable.value.key?.let { overlay.restore(it) }
        stable.accept(observation)?.let { handleObservation(it,calibration) }
    }
    fun onObservation(observation:BoardObservation,calibration:GridCalibration)=launch { if(!stopped && !paused)handleObservation(observation,calibration) }
    private suspend fun handleObservation(observation:BoardObservation,calibration:GridCalibration) {
        if(observation.board==null||observation.occluded) { invalidate(); return }
        if(mutable.value.calibration?.transformRevision!=calibration.transformRevision) { invalidate(); mutable.value=mutable.value.copy(calibration=calibration); overlay.setCalibration(calibration); transform=maxOf(transform,calibration.transformRevision) }
        if(tracker.current==null) {
            mutable.value=mutable.value.copy(position=PositionSnapshot(requireNotNull(observation.board),config.initialTurn)); update(SessionPhase.AWAITING_CONFIRMATION,"棋盘已识别，点「校验」确认棋子及行棋方"); return
        }
        when(val result=tracker.accept(observation,legal)) {
            TrackingUpdate.Unchanged -> { if(mutable.value.phase !in listOf(SessionPhase.NEEDS_SYNC,SessionPhase.ERROR) && mutable.value.key==null)beginSearch() }
            is TrackingUpdate.Confirmed -> { invalidate(); validateAndSearch(result.position) }
            is TrackingUpdate.NeedsConfirmation -> { invalidate(); mutable.value=mutable.value.copy(position=PositionSnapshot(result.board,config.initialTurn),pendingHistoryIndices=result.historyIndices); update(SessionPhase.NEEDS_SYNC,result.reason) }
            is TrackingUpdate.Unsynced -> { invalidate(); mutable.value=mutable.value.copy(position=result.board?.let { PositionSnapshot(it,config.initialTurn) },pendingHistoryIndices=emptyList()); update(SessionPhase.NEEDS_SYNC,result.reason) }
        }
    }
    fun historyCandidate(index:Int)=tracker.historyAt(index)
    fun confirm(snapshot:PositionSnapshot,calibration:GridCalibration,historyIndex:Int?=null)=launch {
        if(stopped)return@launch
        invalidate(); FenCodec.decode(FenCodec.encode(snapshot)).getOrThrow()
        val candidate=historyIndex?.let { requireNotNull(tracker.historyAt(it)); require(tracker.historyAt(it)!!.snapshot.board==snapshot.board && tracker.historyAt(it)!!.snapshot.sideToMove==snapshot.sideToMove); tracker.historyAt(it)!!.history }
            ?:PositionHistory(FenCodec.encode(snapshot),emptyList(),snapshot.board==FenCodec.decode(START_FEN).getOrThrow().board && snapshot.sideToMove==Side.RED)
        val valid=gateway.validate(candidate).getOrThrow()
        require(valid.snapshot.board==snapshot.board && valid.snapshot.sideToMove==snapshot.sideToMove)
        val p=tracker.confirmRecovery(snapshot,historyIndex); legal=valid.legalMoves
        transform=maxOf(transform,calibration.transformRevision); mutable.value=mutable.value.copy(position=p.snapshot,calibration=calibration,key=null,historyComplete=p.history.historyComplete,pendingHistoryIndices=emptyList())
        overlay.setCalibration(calibration); stable.reset(); onConfirmed(p)
        if(paused)update(SessionPhase.PAUSED,"已确认；返回 JJ 后继续") else beginSearch()
    }
    fun confirmCandidate(candidate:ConfirmedPosition,calibration:GridCalibration)=launch {
        if(stopped)return@launch
        require(mutable.value.position?.board==candidate.snapshot.board) { "上一局与屏幕不同，请重新确认" }
        invalidate(); val valid=gateway.validate(candidate.history).getOrThrow()
        require(valid.snapshot.board==candidate.snapshot.board && valid.snapshot.sideToMove==candidate.snapshot.sideToMove)
        val restored=tracker.restore(candidate.history); legal=valid.legalMoves
        mutable.value=mutable.value.copy(position=restored.snapshot,calibration=calibration,key=null,historyComplete=restored.history.historyComplete,pendingHistoryIndices=emptyList())
        overlay.setCalibration(calibration); onConfirmed(restored); stable.reset()
        if(paused)update(SessionPhase.PAUSED,"历史已确认；返回 JJ 后继续") else beginSearch()
    }
    private suspend fun validateAndSearch(position:ConfirmedPosition) {
        val valid=gateway.validate(position.history).getOrThrow(); require(valid.snapshot.board==position.snapshot.board && valid.snapshot.sideToMove==position.snapshot.sideToMove)
        legal=valid.legalMoves; mutable.value=mutable.value.copy(position=position.snapshot,historyComplete=position.history.historyComplete); onConfirmed(position); beginSearch()
    }
    private fun beginSearch() {
        val p=tracker.current?:return
        if(stopped||paused)return
        if(legal.isEmpty()) { update(SessionPhase.READY,"已无合法走法，本局结束"); return }
        if(p.snapshot.sideToMove!=config.playerSide) { overlay.clear(); update(SessionPhase.WAITING_OPPONENT,"等待对方走棋"); return }
        val cal=mutable.value.calibration?:return
        val key=AnalysisKey(session,p.positionRevision,++search,cal.transformRevision)
        mutable.value=mutable.value.copy(key=key,info=null); update(SessionPhase.ANALYZING,"正在计算（${config.movetimeMs/1000} 秒）")
        collector=scope.launch {
            try { gateway.analyze(AnalysisRequest(key,p.history,config.movetimeMs)).collect { event -> mutex.withLock {
                if(event.key!=mutable.value.key||paused||stopped)return@withLock
                when(event) {
                    is EngineEvent.Info -> { mutable.value=mutable.value.copy(info=event); overlay.status(progress(event,p.snapshot)) }
                    is EngineEvent.BestMove -> {
                        val move=event.move
                        if(move==null||move !in legal) { overlay.clear(); mutable.value=mutable.value.copy(key=null); update(SessionPhase.ERROR,"引擎走法无效，请停止后重试") }
                        else { val text=MoveNotation.format(p.snapshot,move,p.snapshot.sideToMove); if(overlay.show(key,move,text))update(SessionPhase.READY,text+"\n"+(mutable.value.info?.let { progress(it,p.snapshot) }?:"请在 JJ 手动落子")) else { mutable.value=mutable.value.copy(key=null); update(SessionPhase.ERROR,"悬浮窗权限已关闭，请停止并重新授权") } }
                    }
                    is EngineEvent.Failure -> { overlay.clear(); mutable.value=mutable.value.copy(key=null); update(SessionPhase.ERROR,"引擎异常：${event.message}；请停止后重试") }
                    is EngineEvent.Stopped -> { overlay.clear(); mutable.value=mutable.value.copy(key=null) }
                }
            } } } catch(e:CancellationException) { throw e } catch(t:Throwable) { mutex.withLock { if(mutable.value.key==key) { mutable.value=mutable.value.copy(key=null); overlay.clear(); update(SessionPhase.ERROR,"${t.message?:"引擎已断开"}；请停止后重试") } } }
        }
    }
    private fun progress(info:EngineEvent.Info,p:PositionSnapshot):String {
        val pv=info.pv.take(4).joinToString(" ") { it.toString() }
        return "深度 ${info.depth} · ${info.nps/1000} 千节点/秒\n${ScorePresentation.format(info.score,p.sideToMove,config.playerSide)}\n变化 $pv"
    }
    fun pause()=launch { if(!stopped) { paused=true; invalidate(); stable.reset(); update(SessionPhase.PAUSED,"已暂停，等待继续") } }
    fun resume()=launch { if(!stopped) { paused=false; stable.reset(); update(SessionPhase.WAITING_BOARD,"等待新鲜稳定棋盘") } }
    fun recalibrate(calibration:GridCalibration)=launch { invalidate(); recognizer.reset(); stable.reset(); mutable.value=mutable.value.copy(calibration=calibration.copy(transformRevision=++transform)); overlay.setCalibration(mutable.value.calibration!!); update(SessionPhase.NEEDS_SYNC,"标定已更新，请重新校验棋盘") }
    fun stop()=launch { if(!closed) {
        stopped=true; paused=true; closed=true
        try { invalidate() } finally { gateway.close(); frames.close(); mutable.value=SessionState(); overlay.clear(); overlay.status("已停止") }
    } }
}
