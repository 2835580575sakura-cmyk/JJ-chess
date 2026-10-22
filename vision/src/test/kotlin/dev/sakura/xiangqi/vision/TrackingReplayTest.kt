package dev.sakura.xiangqi.vision
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties
class TrackingReplayTest {
    @Test fun manuallyAnnotatedOpeningSequenceMatchesVideoBoards() {
        val labels=Properties().apply { load(TrackingReplayTest::class.java.getResourceAsStream("/jj/labels.properties")) }
        val tracker=GameTracker(); tracker.start(FenCodec.decode(START_FEN).getOrThrow(),true)
        val checkpoints=mapOf(3 to "cal6",5 to "holdout8",6 to "cal10")
        for((i,text) in listOf("h2e2","b7e7","h0g2","b9c7","c3c4","a9b9").withIndex()) {
            val move=UciMove.parse(text); val board=tracker.current!!.snapshot.board.apply(move)
            val update=tracker.accept(BoardObservation(board,List(90){1f},i.toLong()),listOf(move)) as TrackingUpdate.Confirmed
            checkpoints[i+1]?.let { name -> assertEquals(name,FenCodec.decode(labels.getProperty(name)).getOrThrow().board,update.position.snapshot.board) }
        }
        assertEquals(6,tracker.current!!.history.moves.size)
    }
}
