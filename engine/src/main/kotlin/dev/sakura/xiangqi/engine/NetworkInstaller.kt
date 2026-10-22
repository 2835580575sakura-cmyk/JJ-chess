package dev.sakura.xiangqi.engine
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class NetworkInstaller(private val directory: File, private val expectedSha256: String, private val openAsset: () -> InputStream) {
    @Synchronized fun ensureInstalled(): Result<File> = runCatching {
        directory.mkdirs()
        val target=File(directory,"pikafish.nnue")
        if (target.isFile && sha(target)==expectedSha256) return@runCatching target
        val temporary=File(directory,"pikafish.nnue.tmp")
        try {
            openAsset().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            check(sha(temporary)==expectedSha256) { "引擎权重校验失败，请重新安装应用" }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            target
        } finally { temporary.delete() }
    }
    private fun sha(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b=ByteArray(65536); while(true) { val n=input.read(b); if(n<0) break; digest.update(b,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object { const val NETWORK_SHA256="7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e" }
}
