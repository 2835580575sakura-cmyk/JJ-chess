package dev.sakura.xiangqi.vision
import dev.sakura.xiangqi.core.*
import kotlin.math.*

class BoardRecognizer(private val classifier: TemplateClassifier) {
    private var previous:List<FloatArray>?=null
    fun reset() { previous=null }
    fun recognize(frame: PixelFrame,calibration: GridCalibration):BoardObservation {
        val r=calibration.captureRect
        if(!calibration.isValid() || r.right>=frame.width || r.bottom>=frame.height) return BoardObservation(null,List(90){0f},frame.timestampNs,occluded=true)
        val step=min(r.width/8,r.height/9)
        // In this JJ theme a capture effect fills the otherwise clear stripe between river ranks.
        var bad=0
        for(i in 0 until 80) {
            val c=frame.rgb(r.left+r.width*(.15f+.7f*i/79),r.top+r.height/2)
            if((c[0]+c[1]+c[2])/3<70 || c[0]-c[1]>25) bad++
        }
        if(bad>12) { reset(); return BoardObservation(null,List(90){0f},frame.timestampNs,1f,true) }
        val cells=MutableList<Piece?>(90){null}; val confidence=MutableList(90){0f}; val features=mutableListOf<FloatArray>(); var known=true; var motion=0f
        for(rank in 0..9) for(file in 0..8) {
            val index=rank*9+file; val point=calibration.center(Square(file,rank),CoordinateSpace.CAPTURE)
            val result=classifier.classify(frame,point.x,point.y,step)
            cells[index]=result.piece; confidence[index]=result.confidence; known=known&&result.known
            val feature=classifier.feature(frame,point.x,point.y,step); features.add(feature)
            previous?.getOrNull(index)?.let { old -> var sum=0f; for(i in old.indices)sum+=abs(old[i]-feature[i]); motion=max(motion,sum/old.size) }
        }
        previous=features
        return BoardObservation(if(known)Board(cells.toList()) else null,confidence,frame.timestampNs,motion,!known)
    }
}
