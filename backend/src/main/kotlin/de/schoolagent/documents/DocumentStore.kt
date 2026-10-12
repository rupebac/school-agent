package de.schoolagent.documents

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Content-addressed storage for raw mail, attachments and crawled documents (spec §33). */
interface DocumentStore {
    /** Stores content, returns a stable ref (content-addressed). */
    fun put(content: ByteArray): String

    fun get(ref: String): ByteArray?

    fun exists(ref: String): Boolean
}

/**
 * Local filesystem store: data/documents/ab/cd/<sha256>. Deduplicates for free
 * because the ref IS the content hash.
 */
@Service
class FileSystemDocumentStore(
    @Value("\${schoolagent.documents.dir:./data/documents}") baseDir: String,
) : DocumentStore {

    private val root: Path = Path.of(baseDir)

    override fun put(content: ByteArray): String {
        val hash = sha256(content)
        val file = pathFor(hash)
        if (!Files.exists(file)) {
            Files.createDirectories(file.parent)
            Files.write(file, content)
        }
        return hash
    }

    override fun get(ref: String): ByteArray? {
        val file = pathFor(ref)
        return if (Files.exists(file)) Files.readAllBytes(file) else null
    }

    override fun exists(ref: String): Boolean = Files.exists(pathFor(ref))

    private fun pathFor(hash: String): Path =
        root.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash)

    companion object {
        fun sha256(content: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(content)
                .joinToString("") { "%02x".format(it) }
    }
}
