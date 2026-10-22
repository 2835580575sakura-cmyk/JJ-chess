package dev.sakura.xiangqi.app

import dev.sakura.xiangqi.vision.TemplateClassifier
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stores normalized character features only, without screen pixels. */
class TemplateStore(private val directory:File) {
    private val target get()=File(directory,"piece-templates-v2.bin")
    fun load():TemplateClassifier? = runCatching {
        val file=target
        if(!file.isFile || file.length()>150_000)return null
        TemplateClassifier.fromBytes(file.readBytes()).getOrNull()
    }.getOrNull()
    fun save(classifier:TemplateClassifier) {
        directory.mkdirs()
        val temp=File(directory,"piece-templates-v2.tmp")
        try {
            temp.outputStream().use { output -> output.write(classifier.toBytes()); output.fd.sync() }
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
