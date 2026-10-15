package com.eignex.klause.bench.source

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorpusCacheTest {

    @Test
    fun `enforce should evict the least recently used collection first`() {
        val root = Files.createTempDirectory("corpuscache").toFile()
        try {
            listOf("old" to 1L, "mid" to 2L, "new" to 3L).forEach { (id, usedAt) ->
                collection(root, id)
                CorpusCache(root, capBytes = null, clock = { usedAt }).markUsed(id)
            }

            val result = CorpusCache(root, capBytes = 2 * SIZE).enforce()

            assertEquals(listOf("old"), result.evicted.map { it.id })
            assertFalse(File(root, "old").exists())
            assertTrue(File(root, "mid").isDirectory && File(root, "new").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `enforce should never evict a collection used in the current run`() {
        val root = Files.createTempDirectory("corpuscache").toFile()
        try {
            listOf("pinned" to 1L, "idle" to 2L).forEach { (id, usedAt) ->
                collection(root, id)
                CorpusCache(root, capBytes = null, clock = { usedAt }).markUsed(id)
            }

            val result = CorpusCache(root, capBytes = 1, inUse = mutableSetOf("pinned")).enforce()

            assertEquals(listOf("idle"), result.evicted.map { it.id })
            assertTrue(File(root, "pinned").isDirectory)
            assertEquals(SIZE, result.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `enforce should evict nothing while the cache is under its cap`() {
        val root = Files.createTempDirectory("corpuscache").toFile()
        try {
            collection(root, "a")
            collection(root, "b")

            val result = CorpusCache(root, capBytes = 2 * SIZE).enforce()

            assertTrue(result.evicted.isEmpty())
            assertTrue(File(root, "a").isDirectory && File(root, "b").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an interrupted eviction should not count as present and the next enforce should remove it`() {
        val root = Files.createTempDirectory("corpuscache").toFile()
        try {
            File(root, ".trash/gone.1/fam").mkdirs()
            File(root, ".trash/gone.1/fam/inst.cnf").writeText("p cnf 1 1\n1 0\n")
            val cache = CorpusCache(root, capBytes = SIZE)

            assertFalse(cache.isPresent("gone"))
            assertTrue(cache.collections().isEmpty())
            cache.enforce()
            assertTrue(File(root, ".trash").list().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `parseCapBytes should read zero and off as no cap`() {
        assertNull(CorpusCache.parseCapBytes("0"))
        assertNull(CorpusCache.parseCapBytes("off"))
    }

    private fun collection(root: File, id: String) {
        File(root, "$id/fam").mkdirs()
        File(root, "$id/fam/inst.cnf").writeBytes(ByteArray(SIZE.toInt()))
    }

    private companion object {
        const val SIZE = 100L
    }
}
