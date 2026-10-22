package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
class SessionStore(private val directory:File) {
    @Synchronized fun save(position:ConfirmedPosition) {
        directory.mkdirs(); val temp=File(directory,"session.json.tmp")
        try { temp.outputStream().use { it.write(Json.encodeToString(position).toByteArray()); it.flush(); (it as java.io.FileOutputStream).fd.sync() }; Files.move(temp.toPath(),File(directory,"session.json").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        finally { temp.delete() }
    }
    @Synchronized fun loadCandidate():ConfirmedPosition?=runCatching {
        val file=File(directory,"session.json"); require(file.length() in 1..1048576)
        Json.decodeFromString<ConfirmedPosition>(file.readText()).also { p ->
            FenCodec.decode(FenCodec.encode(p.snapshot)).getOrThrow(); var board=FenCodec.decode(p.history.initialFen).getOrThrow().board
            for(move in p.history.moves)board=board.apply(move)
            require(board==p.snapshot.board)
        }
    }.getOrNull()
}
