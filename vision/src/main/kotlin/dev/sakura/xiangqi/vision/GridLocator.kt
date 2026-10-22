package dev.sakura.xiangqi.vision
import dev.sakura.xiangqi.core.*
import kotlin.math.*

object GridLocator {
    fun locate(frame: PixelFrame):GridCalibration? {
        if(frame.width<180 || frame.height<200) return null
        val w=frame.width.toFloat(); val h=frame.height.toFloat()
        var r=if(h/w>1.4f) FloatRect(w*40/576,h*366/1280,w*545/576,h*909/1280)
              else FloatRect(w*32/568,h*36/615,w*537/568,h*579/615)
        // Refine the theme's normalized region against grid-line contrast, not avatars.
        fun evidence(rect: FloatRect,vertical: Boolean):Float {
            var score=0f
            fun luma(x: Float,y: Float):Float { val c=frame.rgb(x,y); return c[0]*.25f+c[1]*.55f+c[2]*.2f }
            for(file in 0..8) for(row in 0..9) {
                if(vertical && (row==4 || row==9))continue
                val x=rect.left+rect.width*(if(vertical)file.toFloat() else file+.5f)/8
                val y=rect.top+rect.height*(if(vertical)row+.5f else row.toFloat())/9
                if(!vertical && file==8)continue
                val delta=max(1f,w/288)
                val a=if(vertical) luma(x-delta,y) else luma(x,y-delta)
                val b=if(vertical) luma(x+delta,y) else luma(x,y+delta)
                score+=((a+b)/2-luma(x,y)).coerceIn(-20f,30f)
            }
            return score
        }
        for(vertical in listOf(true,false)) {
            var best=r; var value=evidence(r,vertical)
            val step=if(vertical) r.width/8 else r.height/9
            for(shift in -2..2) for(scale in listOf(.96f,.97f,.98f,.99f,.995f,1f,1.005f,1.01f,1.02f,1.03f,1.04f)) {
                val d=step*.025f*shift
                val next=if(vertical) FloatRect(r.left+d,r.top,r.left+d+r.width*scale,r.bottom)
                         else FloatRect(r.left,r.top+d,r.right,r.top+d+r.height*scale)
                val score=evidence(next,vertical)
                if(score>value) { best=next; value=score }
            }
            r=best
        }
        // Coarse scale changes can still leave several pixels of drift at the far edge.
        // Fit both ends more precisely; character matching must not compensate for a bad grid.
        for(vertical in listOf(true,false)) {
            var best=r; var value=evidence(r,vertical)
            val step=if(vertical)r.width/8 else r.height/9
            for(shift in -5..5) for(scaleStep in -4..4) {
                val d=step*.01f*shift; val scale=1+scaleStep*.0025f
                val next=if(vertical)FloatRect(r.left+d,r.top,r.left+d+r.width*scale,r.bottom)
                         else FloatRect(r.left,r.top+d,r.right,r.top+d+r.height*scale)
                val score=evidence(next,vertical)
                if(score>value) { best=next; value=score }
            }
            r=best
        }
        return GridCalibration(r,r).takeIf { it.isValid() && r.right<w && r.bottom<h }
    }
}
