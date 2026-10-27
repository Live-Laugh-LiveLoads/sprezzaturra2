package com.ideaforge.export

import java.io.File
import java.io.IOException
import java.util.Locale

/** Small path helpers shared by export and restore. Pure JVM so they can be unit tested. */
object ZipPaths {

    /**
     * Resolves [entryName] under [root] and refuses anything that escapes it ("zip-slip":
     * entries named `../../x` or absolute paths that, once canonicalised, leave the root).
     */
    @Throws(IOException::class)
    fun resolveSafe(root: File, entryName: String): File {
        val dest = File(root, entryName)
        val rootPath = root.canonicalPath
        val destPath = dest.canonicalPath
        if (destPath != rootPath && !destPath.startsWith(rootPath + File.separator)) {
            throw IOException("Blocked unsafe path in archive: $entryName")
        }
        return dest
    }

    /** Lower-case ASCII slug for file and folder names; never empty. */
    fun slug(text: String, maxLength: Int = 40): String {
        val s = text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(maxLength)
            .trim('-')
        return s.ifEmpty { "untitled" }
    }

    /** A file name safe to use inside an archive: no separators or control characters. */
    fun safeFileName(name: String, fallback: String = "file"): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
            .trim()
            .trim('.')
        return base.ifEmpty { fallback }
    }
}
