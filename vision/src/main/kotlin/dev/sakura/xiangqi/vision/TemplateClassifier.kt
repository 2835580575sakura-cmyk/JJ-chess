package dev.sakura.xiangqi.vision
import dev.sakura.xiangqi.core.*
import java.io.DataInputStream
import kotlin.math.*

data class CellResult(val piece: Piece?,val confidence: Float,val known: Boolean)
class TemplateClassifier private constructor(private val templates:List<Pair<Piece,FloatArray>>) {
    fun feature(frame: PixelFrame,x: Float,y: Float,step: Float):FloatArray {
        val radius=step*.34f; val values=FloatArray(24*24)
        for(j in 0 until 24) for(i in 0 until 24) {
            val nx=(i+.5f)/12-1; val ny=(j+.5f)/12-1
            if(nx*nx+ny*ny>.93f*.93f) continue
            val c=frame.rgb(x+nx*radius,y+ny*radius)
            val red=((c[0]-c[1]-45)/110).coerceIn(0f,1f)
            val dark=((110-(c[0]*.25f+c[1]*.55f+c[2]*.2f))/100).coerceIn(0f,1f)
            values[j*24+i]=max(red,dark)
        }
        return values
    }
    fun classify(frame: PixelFrame,x: Float,y: Float,step: Float):CellResult {
        var wood=0
        for(i in 0 until 48) {
            val angle=i*PI/24; val radius=step*(if(i%2==0).39f else .35f)
            val c=frame.rgb(x+cos(angle).toFloat()*radius,y+sin(angle).toFloat()*radius)
            if(c[0]>130 && c[1]>85 && c[0]-c[1]>7 && c[1]-c[2]>17) wood++
        }
        if(wood<9) return CellResult(null,.98f,true)
        if(wood<20) return CellResult(null,0f,false)
        var redEnergy=0f
        for(j in -3..3) for(i in -3..3) {
            val c=frame.rgb(x+i*step*.035f,y+j*step*.035f)
            redEnergy+=((c[0]-c[1]-45)/100).coerceAtLeast(0f)
        }
        val side=if(redEnergy>1.5f) Side.RED else Side.BLACK
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
    companion object {
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
