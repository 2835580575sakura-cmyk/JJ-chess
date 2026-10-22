package dev.sakura.xiangqi.vision
import dev.sakura.xiangqi.core.*
import java.io.*
import kotlin.math.*

data class CellResult(val piece: Piece?,val confidence: Float,val known: Boolean)
class TemplateClassifier private constructor(private val templates:List<Pair<Piece,FloatArray>>) {
    fun feature(frame: PixelFrame,x: Float,y: Float,step: Float):FloatArray {
        val radius=step*.34f; val values=FloatArray(24*24)
        for(j in 0 until 24) for(i in 0 until 24) {
            val nx=(i+.5f)/12-1; val ny=(j+.5f)/12-1
            if(nx*nx+ny*ny>.93f*.93f) continue
            val c=frame.rgb(x+nx*radius,y+ny*radius)
            val red=redInk(c)
            val dark=((110-(c[0]*.25f+c[1]*.55f+c[2]*.2f))/100).coerceIn(0f,1f)
            values[j*24+i]=max(red,dark)
        }
        return values
    }
    private fun diskEvidence(frame:PixelFrame,x:Float,y:Float,step:Float):Int {
        var wood=0
        for(i in 0 until 48) {
            val angle=i*PI/24; val radius=step*(if(i%2==0).39f else .35f)
            val c=frame.rgb(x+cos(angle).toFloat()*radius,y+sin(angle).toFloat()*radius)
            if(c[0]>130 && c[1]>85 && c[0]-c[1]>7 && c[1]-c[2]>17) wood++
        }
        return wood
    }
    private fun inkSide(frame:PixelFrame,x:Float,y:Float,step:Float):Side {
        var redEnergy=0f
        for(j in -3..3) for(i in -3..3) {
            val c=frame.rgb(x+i*step*.035f,y+j*step*.035f)
            redEnergy+=redInk(c)
        }
        return if(redEnergy>1.5f) Side.RED else Side.BLACK
    }
    fun classify(frame: PixelFrame,x: Float,y: Float,step: Float):CellResult {
        val wood=diskEvidence(frame,x,y,step)
        if(wood<9) return CellResult(null,.98f,true)
        if(wood<20) return CellResult(null,0f,false)
        val side=inkSide(frame,x,y,step)
        val scores=FloatArray(7)
        for(dy in listOf(-.035f,0f,.035f)) for(dx in listOf(-.035f,0f,.035f)) {
            val values=feature(frame,x+dx*step,y+dy*step,step)
            val norm=sqrt(values.sumOf { (it*it).toDouble() }).toFloat()
            if(norm<.5f) continue
            for((piece,template) in templates) if(piece.side==side) {
                var dot=0f; for(i in values.indices) dot+=values[i]*template[i]
                scores[piece.kind.ordinal]=max(scores[piece.kind.ordinal],dot/norm)
            }
        }
        val order=scores.indices.sortedByDescending { scores[it] }
        val best=scores[order[0]]; val margin=best-scores[order[1]]
        return CellResult(Piece(side,PieceKind.entries[order[0]]),best,best>=.70f && margin>=.025f)
    }
    fun toBytes():ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x4A4A5432); output.writeInt(templates.size)
            for((piece,values) in templates) {
                output.writeInt(piece.kind.ordinal+if(piece.side==Side.RED)0 else 7)
                values.forEach(output::writeFloat)
            }
        }
    }.toByteArray()
    companion object {
        // Gold disks can have a large R-G difference too. Only dark saturated strokes are ink.
        private fun redInk(c:FloatArray):Float = if(c[1]<125) ((c[0]-c[1]-45)/110).coerceIn(0f,1f) else 0f
        private fun normalized(values:FloatArray):FloatArray {
            val norm=sqrt(values.sumOf { (it*it).toDouble() }).toFloat()
            require(norm>=.5f) { "未找到清晰字迹，请对齐棋盘交点后重试" }
            return FloatArray(values.size){values[it]/norm}
        }
        fun fromBytes(bytes:ByteArray):Result<TemplateClassifier> = runCatching {
            require(bytes.size<=8+64*(4+24*24*4))
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt()==0x4A4A5432)
                val count=input.readInt(); require(count==64)
                val list=List(count) {
                    val label=input.readInt(); require(label in 0..13)
                    val values=FloatArray(24*24){input.readFloat()}
                    require(values.all { it.isFinite() && it>=0f && it<=1f })
                    require(values.sumOf { (it*it).toDouble() } in .99..1.01)
                    Piece(if(label<7)Side.RED else Side.BLACK,PieceKind.entries[label%7]) to values
                }
                require(input.available()==0)
                require(list.map { it.first }.toSet().size==14)
                TemplateClassifier(list)
            }
        }
        /** User must explicitly confirm the preview is a complete standard opening. */
        fun fromOpening(frame:PixelFrame,calibration:GridCalibration):Result<TemplateClassifier> = runCatching {
            val r=calibration.captureRect
            require(calibration.isValid() && r.right<frame.width && r.bottom<frame.height) { "棋盘位置越界，请先校准" }
            val step=min(r.width/8,r.height/9)
            require(step>=24) { "棋盘太小，请使用竖屏完整棋盘" }
            val board=FenCodec.decode(START_FEN).getOrThrow().board
            val empty=TemplateClassifier(emptyList())
            val list=mutableListOf<Pair<Piece,FloatArray>>()
            for(rank in 0..9) for(file in 0..8) {
                val square=Square(file,rank); val p=calibration.center(square,CoordinateSpace.CAPTURE)
                val piece=board[square]; val evidence=empty.diskEvidence(frame,p.x,p.y,step)
                require(if(piece==null)evidence<9 else evidence>=20) { "棋子位置不符合标准开局，请在 JJ 点重来并检查棋盘交点" }
                if(piece!=null) {
                    require(empty.inkSide(frame,p.x,p.y,step)==piece.side) { "红黑方向不符，请检查屏幕下方是哪一方" }
                    val values=normalized(empty.feature(frame,p.x,p.y,step))
                    list.add(piece to values); list.add(piece to values.reversedArray())
                }
            }
            val adapted=TemplateClassifier(list)
            val observation=BoardRecognizer(adapted).recognize(frame,calibration)
            require(observation.board==board && !observation.occluded) { "开局仍有棋子无法区分，请校准交点并等待动画结束" }
            adapted
        }
        fun fromResource():TemplateClassifier {
            val list=mutableListOf<Pair<Piece,FloatArray>>()
            val classifier=TemplateClassifier(emptyList())
            DataInputStream(checkNotNull(TemplateClassifier::class.java.getResourceAsStream("/jj/templates.bin"))).use { input ->
                check(input.readInt()==0x4A4A5431)
                repeat(input.readInt()) {
                    val label=input.readInt(); val w=input.readInt(); val h=input.readInt()
                    val frame=PixelFrame(w,h,IntArray(w*h){input.readInt()},0)
                    val piece=Piece(if(label<7)Side.RED else Side.BLACK,PieceKind.entries[label%7])
                    for(image in listOf(frame,frame.rotated180())) {
                        val feature=classifier.feature(image,w/2f,h/2f,543f/9)
                        val norm=sqrt(feature.sumOf { (it*it).toDouble() }).toFloat().coerceAtLeast(.01f)
                        list.add(piece to FloatArray(feature.size){feature[it]/norm})
                    }
                }
            }
            return TemplateClassifier(list)
        }
    }
}
