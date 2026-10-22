package dev.sakura.xiangqi.engine
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
class NetworkInstallerTest {
    private val bytes = "trusted network fixture".toByteArray()
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun verifiedNetworkInstallsAtomically() {
        val dir=Files.createTempDirectory("net-test").toFile()
        val installer=NetworkInstaller(dir,hash) { ByteArrayInputStream(bytes) }
        assertArrayEquals(bytes,installer.ensureInstalled().getOrThrow().readBytes())
        assertEquals(1,dir.listFiles()!!.size)
    }
    @Test fun corruptNetworkRejected() {
        val dir=Files.createTempDirectory("net-test").toFile()
        assertTrue(NetworkInstaller(dir,hash) { ByteArrayInputStream(byteArrayOf(1)) }.ensureInstalled().isFailure)
        assertFalse(dir.resolve("pikafish.nnue").exists())
    }
    @Test fun interruptedCopyPreservesExistingFile() {
        val dir=Files.createTempDirectory("net-test").toFile()
        dir.resolve("pikafish.nnue").writeBytes(byteArrayOf(0))
        val before=dir.resolve("pikafish.nnue").readBytes()
        val result=NetworkInstaller(dir,hash) { throw IOException("storage failure") }.ensureInstalled()
        assertTrue(result.isFailure)
        assertArrayEquals(before,dir.resolve("pikafish.nnue").readBytes())
        assertFalse(dir.resolve("pikafish.nnue.tmp").exists())
    }
    @Test fun diskFullDoesNotPublishPartialNetwork() {
        val dir=Files.createTempDirectory("net-test").toFile()
        val result=NetworkInstaller(dir,hash) { object:java.io.InputStream() { var count=0; override fun read():Int { if(count++>=5)throw IOException("No space left on device"); return 1 } } }.ensureInstalled()
        assertTrue(result.isFailure); assertFalse(dir.resolve("pikafish.nnue").exists()); assertFalse(dir.resolve("pikafish.nnue.tmp").exists())
    }

}
