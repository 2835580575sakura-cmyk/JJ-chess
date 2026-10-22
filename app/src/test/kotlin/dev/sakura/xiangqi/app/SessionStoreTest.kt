package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class SessionStoreTest {
    @Test fun corruptStoredSessionNotConfirmed() { val dir=Files.createTempDirectory("session").toFile(); dir.resolve("session.json").writeText("broken"); assertNull(SessionStore(dir).loadCandidate()) }
    @Test fun savedHistoryIsOnlyCandidate() { val dir=Files.createTempDirectory("session").toFile(); val p=GameTracker().start(FenCodec.decode(START_FEN).getOrThrow(),true); SessionStore(dir).save(p); assertEquals(p,SessionStore(dir).loadCandidate()); assertFalse(dir.resolve("session.json.tmp").exists()) }
}
