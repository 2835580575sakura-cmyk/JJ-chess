package dev.sakura.xiangqi.core

sealed class TrackingUpdate {
    data object Unchanged:TrackingUpdate()
    data class Confirmed(val position: ConfirmedPosition):TrackingUpdate()
    data class NeedsConfirmation(val board: Board,val historyIndices: List<Int>,val reason: String):TrackingUpdate()
    data class Unsynced(val board: Board?,val reason: String):TrackingUpdate()
}
class GameTracker {
    var current:ConfirmedPosition?=null; private set
    private val nodes=mutableListOf<ConfirmedPosition>()
    private var revision=0L
    fun start(snapshot: PositionSnapshot,historyComplete: Boolean):ConfirmedPosition {
        FenCodec.decode(FenCodec.encode(snapshot)).getOrThrow()
        val full=historyComplete && snapshot.board==FenCodec.decode(START_FEN).getOrThrow().board && snapshot.sideToMove==Side.RED
        val state=ConfirmedPosition(snapshot,PositionHistory(FenCodec.encode(snapshot),emptyList(),full),++revision)
        nodes.clear(); nodes.add(state); current=state; return state
    }
    fun accept(observation: BoardObservation,legalMoves: List<UciMove>):TrackingUpdate {
        val before=current ?: return TrackingUpdate.Unsynced(observation.board,"请先确认棋盘")
        val board=observation.board ?: return TrackingUpdate.Unsynced(null,"画面不清晰，等待稳定棋盘")
        if(observation.occluded)return TrackingUpdate.Unsynced(board,"画面有遮挡")
        if(board==before.snapshot.board)return TrackingUpdate.Unchanged
        val candidates=legalMoves.filter { runCatching { before.snapshot.board.apply(it)==board }.getOrDefault(false) }
        if(candidates.size==1) {
            val move=candidates.single(); val capture=before.snapshot.board[move.to]!=null
            val snapshot=PositionSnapshot(board,before.snapshot.sideToMove.opposite(),if(capture)0 else before.snapshot.halfmoveClock+1,before.snapshot.fullmoveNumber+if(before.snapshot.sideToMove==Side.BLACK)1 else 0)
            val next=ConfirmedPosition(snapshot,before.history.copy(moves=before.history.moves+move),++revision)
            current=next; nodes.add(next); return TrackingUpdate.Confirmed(next)
        }
        val matches=nodes.indices.filter { nodes[it].snapshot.board==board }
        if(matches.isNotEmpty())return TrackingUpdate.NeedsConfirmation(board,matches,"检测到悔棋或重开，请确认行棋方")
        return TrackingUpdate.Unsynced(board,"局面出现跳变，请重新确认棋盘及行棋方")
    }
    fun historyAt(index:Int):ConfirmedPosition?=nodes.getOrNull(index)
    fun confirmRecovery(snapshot: PositionSnapshot,historyIndex: Int?):ConfirmedPosition {
        if(historyIndex==null) return start(snapshot,snapshot.board==FenCodec.decode(START_FEN).getOrThrow().board && snapshot.sideToMove==Side.RED)
        val selected=nodes.getOrNull(historyIndex) ?: error("历史节点不存在")
        require(selected.snapshot.board==snapshot.board && selected.snapshot.sideToMove==snapshot.sideToMove) { "历史局面或行棋方不匹配" }
        val recovered=selected.copy(positionRevision=++revision)
        while(nodes.size>historyIndex+1)nodes.removeAt(nodes.lastIndex)
        nodes[nodes.lastIndex]=recovered; current=recovered; return recovered
    }
}
