package de.schoolagent.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FileSystemDocumentStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `put returns content-addressed ref and get round-trips`() {
        val store = FileSystemDocumentStore(tempDir.toString())
        val content = "Elternbrief Schullandheim".toByteArray()

        val ref = store.put(content)

        assertThat(ref).hasSize(64)
        assertThat(store.exists(ref)).isTrue()
        assertThat(store.get(ref)).isEqualTo(content)
    }

    @Test
    fun `identical content deduplicates to the same ref`() {
        val store = FileSystemDocumentStore(tempDir.toString())
        val content = ByteArray(1024) { (it % 251).toByte() }

        val first = store.put(content)
        val second = store.put(content.copyOf())

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `unknown ref misses`() {
        val store = FileSystemDocumentStore(tempDir.toString())
        assertThat(store.exists("ab".repeat(32))).isFalse()
        assertThat(store.get("ab".repeat(32))).isNull()
    }
}
