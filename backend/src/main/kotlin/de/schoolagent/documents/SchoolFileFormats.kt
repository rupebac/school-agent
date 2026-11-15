package de.schoolagent.documents

/** Identify school downloads from their content, never a filename or an untrusted HTTP MIME label. */
object SchoolFileFormats {
    fun detect(bytes: ByteArray): String? {
        fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 255 == values[it] }
        return when {
            starts(0x25, 0x50, 0x44, 0x46, 0x2d) -> "application/pdf"
            starts(0xff, 0xd8, 0xff) -> "image/jpeg"
            starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
            bytes.take(6).toByteArray().decodeToString() in setOf("GIF87a", "GIF89a") -> "image/gif"
            starts(0x50, 0x4b, 3, 4) -> runCatching { DocxReader.read(bytes); DocxReader.MIME_TYPE }.getOrNull()
            else -> null
        }
    }
}
