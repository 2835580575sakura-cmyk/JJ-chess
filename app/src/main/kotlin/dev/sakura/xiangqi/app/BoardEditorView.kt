package dev.sakura.xiangqi.app
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.view.*
import dev.sakura.xiangqi.core.*
import kotlin.math.*
fun Piece.glyph():String=when(kind) { PieceKind.ROOK->"车"; PieceKind.KNIGHT->"马"; PieceKind.ELEPHANT->if(side==Side.RED)"相" else "象"; PieceKind.ADVISOR->if(side==Side.RED)"仕" else "士"; PieceKind.KING->if(side==Side.RED)"帅" else "将"; PieceKind.CANNON->"炮"; PieceKind.PAWN->if(side==Side.RED)"兵" else "卒" }
class BoardEditorView(context:Context,val editor:BoardEditorState,private val orientation:Orientation):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun calibration():GridCalibration { val gap=min(width/9.2f,height/10.2f); val x=(width-gap*8)/2; val y=(height-gap*9)/2; val r=FloatRect(x,y,x+gap*8,y+gap*9); return GridCalibration(r,r,orientation) }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas); if(width==0||height==0)return
        canvas.drawColor(0xffffdfa4.toInt()); val cal=calibration(); val r=cal.captureRect; val gap=r.width/8
        paint.color=Color.DKGRAY; paint.strokeWidth=2f
        for(i in 0..8)canvas.drawLine(r.left+i*gap,r.top,r.left+i*gap,r.bottom,paint)
        for(i in 0..9)canvas.drawLine(r.left,r.top+i*gap,r.right,r.top+i*gap,paint)
        for(rank in 0..9)for(file in 0..8) {
            val sq=Square(file,rank); val p=editor.board[sq]?:continue; val pt=cal.center(sq,CoordinateSpace.CAPTURE)
            paint.style=Paint.Style.FILL; paint.color=0xffffefc8.toInt(); canvas.drawCircle(pt.x,pt.y,gap*.44f,paint)
            paint.color=if(p.side==Side.RED)0xffb82720.toInt() else Color.BLACK; paint.textSize=gap*.63f; paint.textAlign=Paint.Align.CENTER
            canvas.drawText(p.glyph(),pt.x,pt.y-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.action!=MotionEvent.ACTION_UP)return true
        val cal=calibration(); val r=cal.captureRect; val col=((event.x-r.left)/r.width*8).roundToInt(); val row=((event.y-r.top)/r.height*9).roundToInt()
        if(col !in 0..8||row !in 0..9)return true
        val sq=if(orientation==Orientation.RED_BOTTOM)Square(col,9-row) else Square(8-col,row)
        val pieces=listOf<Piece?>(null)+Side.entries.flatMap { side -> PieceKind.entries.map { Piece(side,it) } }
        val labels=pieces.map { if(it==null)"清空" else (if(it.side==Side.RED)"红" else "黑")+it.glyph() }.toTypedArray()
        AlertDialog.Builder(context).setTitle("修改棋子 $sq").setItems(labels) { _,index -> editor.set(sq,pieces[index]); invalidate() }.show()
        performClick(); return true
    }
    override fun performClick():Boolean { super.performClick(); return true }
}
