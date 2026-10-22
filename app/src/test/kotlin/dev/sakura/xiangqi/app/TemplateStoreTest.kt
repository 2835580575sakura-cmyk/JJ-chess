package dev.sakura.xiangqi.app

import dev.sakura.xiangqi.vision.TemplateClassifier
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TemplateStoreTest {
    @Test fun savedTemplatesRoundTripAndCorruptDataIsIgnored() {
        val folder=Files.createTempDirectory("piece-model").toFile()
        try {
            val store=TemplateStore(folder)
            assertNull(store.load())
            val classifier=TemplateClassifier.fromResource()
            store.save(classifier)
            assertArrayEquals(classifier.toBytes(),store.load()!!.toBytes())
            folder.listFiles()!!.single().writeBytes(byteArrayOf(0,1,2))
            assertNull(store.load())
        } finally { folder.deleteRecursively() }
    }
}
