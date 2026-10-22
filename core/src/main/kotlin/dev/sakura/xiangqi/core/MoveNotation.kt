package dev.sakura.xiangqi.core
import kotlin.math.abs
object MoveNotation {
    fun format(snapshot: PositionSnapshot,move: UciMove,side: Side):String {
        val piece=snapshot.board[move.from] ?: return "${move.from} → ${move.to}"
        fun number(n: Int)=if(side==Side.RED) "一二三四五六七八九"[n-1].toString() else n.toString()
        fun file(f: Int)=number(if(side==Side.RED)9-f else f+1)
        val name=when(piece.kind) {
            PieceKind.ROOK -> "车"; PieceKind.KNIGHT -> "马"; PieceKind.CANNON -> "炮"
            PieceKind.ELEPHANT -> if(side==Side.RED)"相" else "象"
            PieceKind.ADVISOR -> if(side==Side.RED)"仕" else "士"
            PieceKind.KING -> if(side==Side.RED)"帅" else "将"
            PieceKind.PAWN -> if(side==Side.RED)"兵" else "卒"
        }
        val sameFile=(0..9).filter { snapshot.board[Square(move.from.file,it)]==piece }.sortedBy { if(side==Side.RED)-it else it }
        val prefix=if(sameFile.size>1) {
            val index=sameFile.indexOf(move.from.rank)
            if(sameFile.size>3)return "${move.from} → ${move.to}"
            (if(index==0)"前" else if(index==sameFile.lastIndex)"后" else "中")+name
        } else name+file(move.from.file)
        if(move.from.rank==move.to.rank)return prefix+"平"+file(move.to.file)
        val forward=(move.to.rank-move.from.rank)*(if(side==Side.RED)1 else -1)>0
        val destination=if(piece.kind in listOf(PieceKind.KNIGHT,PieceKind.ELEPHANT,PieceKind.ADVISOR))file(move.to.file) else number(abs(move.to.rank-move.from.rank))
        return prefix+(if(forward)"进" else "退")+destination
    }
}
