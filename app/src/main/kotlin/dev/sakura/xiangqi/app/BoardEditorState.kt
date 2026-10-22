package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
class BoardEditorState(initial:PositionSnapshot) {
    var board=initial.board; private set
    var sideToMove=initial.sideToMove
    fun set(square:Square,piece:Piece?) { board=board.with(square,piece) }
    fun snapshot():Result<PositionSnapshot> = FenCodec.decode(FenCodec.encode(PositionSnapshot(board,sideToMove)))
}
