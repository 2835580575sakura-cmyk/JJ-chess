package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.*
import java.io.File

interface NativeEnginePort {
    fun validate(history: PositionHistory): Result<LegalPosition>
    fun configure(options: EngineOptions)
    fun start(request: AnalysisRequest, callback: (EngineEvent) -> Unit)
    fun requestStop()
    fun awaitStopped()
    fun close()
}
internal object NativeLoader {
    private external fun hasDotProduct(): Boolean
    private val initialized: Boolean by lazy { System.loadLibrary("cpu_dispatch"); System.loadLibrary(if(hasDotProduct()) "pikafish_dotprod" else "pikafish_base"); true }
    fun load() { check(initialized) }
}
internal object NativeBindings {
    init { NativeLoader.load() }
    external fun create(path: String, threads: Int, hash: Int): Long
    external fun legal(fen: String, moves: Array<String>): Array<String>
    external fun configure(pointer: Long, threads: Int, hash: Int)
    external fun start(pointer: Long, fen: String, moves: Array<String>, ms: Int, callback: NativeLineListener)
    external fun stop(pointer: Long)
    external fun await(pointer: Long)
    external fun destroy(pointer: Long)
}
class NativeLineListener(private val receive: (String) -> Unit) { fun onLine(line: String) = receive(line) }
class NativeEngine(network: File, options: EngineOptions) : NativeEnginePort {
    @Volatile private var pointer=NativeBindings.create(network.absolutePath, options.threads.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(1)), options.hashMiB)
    override fun validate(history: PositionHistory): Result<LegalPosition> = runCatching {
        FenCodec.decode(history.initialFen).getOrThrow()
        require(history.moves.size<=8192)
        val result=NativeBindings.legal(history.initialFen,history.moves.map { it.toString() }.toTypedArray())
        LegalPosition(FenCodec.decode(result.first()).getOrThrow(),result.drop(1).map { UciMove.parse(it) })
    }
    override fun configure(options: EngineOptions) { check(pointer!=0L); NativeBindings.configure(pointer,options.threads.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(1)),options.hashMiB) }
    override fun start(request: AnalysisRequest, callback: (EngineEvent) -> Unit) {
        check(pointer!=0L)
        NativeBindings.start(pointer,request.history.initialFen,request.history.moves.map { it.toString() }.toTypedArray(),request.movetimeMs,NativeLineListener { line -> parseLine(request.key,line)?.let(callback) })
    }
    override fun requestStop() { NativeBindings.stop(pointer) }
    override fun awaitStopped() { NativeBindings.await(pointer) }
    override fun close() { val p=pointer; if(p!=0L) { NativeBindings.destroy(p); pointer=0 } }
    companion object {
        fun parseLine(key: AnalysisKey,line: String): EngineEvent? = runCatching {
            val words=line.trim().split(Regex("\\s+"))
            if(words.firstOrNull()=="bestmove") return@runCatching EngineEvent.BestMove(key,words.getOrNull(1)?.let { runCatching { UciMove.parse(it) }.getOrNull() })
            if(words.firstOrNull()!="info" || "depth" !in words) return@runCatching null
            fun value(name: String)=words.getOrNull(words.indexOf(name)+1)
            val scoreIndex=words.indexOf("score")
            val score=if(scoreIndex>=0 && words.getOrNull(scoreIndex+1)=="mate") EngineScore(mate=words.getOrNull(scoreIndex+2)?.toIntOrNull()) else EngineScore(cp=words.getOrNull(scoreIndex+2)?.toIntOrNull())
            val pvIndex=words.indexOf("pv")
            val pv=if(pvIndex>=0) words.drop(pvIndex+1).mapNotNull { runCatching { UciMove.parse(it) }.getOrNull() } else emptyList()
            EngineEvent.Info(key,value("depth")?.toIntOrNull()?:0,value("nodes")?.toLongOrNull()?:0,value("nps")?.toLongOrNull()?:0,score,pv)
        }.getOrNull()
    }
}
