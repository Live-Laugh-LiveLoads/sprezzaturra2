package com.ideaforge.export

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ZipPathsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun resolvesNormalEntriesInsideRoot() {
        val root = tmp.newFolder("staging")
        val f = ZipPaths.resolveSafe(root, "files/sessions/1/chunk-001.m4a")
        assertThat(f.canonicalPath).startsWith(root.canonicalPath + File.separator)
    }

    @Test
    fun rejectsParentTraversal() {
        val root = tmp.newFolder("staging")
        assertThrows(IOException::class.java) { ZipPaths.resolveSafe(root, "../evil.txt") }
        assertThrows(IOException::class.java) { ZipPaths.resolveSafe(root, "files/../../evil.txt") }
    }

    @Test
    fun rejectsSiblingDirectoryWithSamePrefix() {
        val root = tmp.newFolder("staging")
        tmp.newFolder("staging-evil")
        assertThrows(IOException::class.java) { ZipPaths.resolveSafe(root, "../staging-evil/x") }
    }

    @Test
    fun slugIsAsciiAndNeverEmpty() {
        assertThat(ZipPaths.slug("Weekly Sync: Q3 plans!")).isEqualTo("weekly-sync-q3-plans")
        assertThat(ZipPaths.slug("???")).isEqualTo("untitled")
        assertThat(ZipPaths.slug("a".repeat(100), 10)).hasLength(10)
    }

    @Test
    fun safeFileNameStripsDirectories() {
        assertThat(ZipPaths.safeFileName("../../etc/passwd")).isEqualTo("passwd")
        assertThat(ZipPaths.safeFileName("a:b*c.md")).isEqualTo("a_b_c.md")
        assertThat(ZipPaths.safeFileName("..", "fallback")).isEqualTo("fallback")
    }
}
