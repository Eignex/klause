package com.eignex.klause.bench.metric

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class NativeCliBuildTest {
    @Test
    fun `native runtime paths should include the dynamic loader and resolved libraries`() {
        val output = """
            linux-vdso.so.1 (0x00007fff)
            libc.so.6 => /lib/libc.so.6 (0x00007f00)
            /lib/ld-linux-x86-64.so.2 (0x00007f01)
        """.trimIndent()

        assertEquals(listOf(File("/lib/libc.so.6"), File("/lib/ld-linux-x86-64.so.2")),
            NativeCliBuild.libraryPaths(output))
    }

    @Test
    fun `an unresolved native dependency should withhold runtime identity`() {
        assertFailsWith<IllegalArgumentException> {
            NativeCliBuild.libraryPaths("libmissing.so => not found")
        }
    }

    @Test
    fun `changed native executable or runtime bytes should change the fingerprint`() {
        val root = Files.createTempDirectory("native-cli-identity").toFile()
        try {
            val executable = root.resolve("klause-cli.kexe").apply { writeText("native executable") }
            val library = root.resolve("libc.so.6").apply { writeText("runtime library") }
            val harness = mapOf("bench.jar" to "harness identity")
            val before = NativeCliBuild.capture(executable, listOf(library), harness, emptyMap())

            for (file in listOf(executable, library)) {
                val bytes = file.readBytes()
                val modified = file.lastModified()
                file.writeText("changed bytes")
                file.setLastModified(modified)

                val after = NativeCliBuild.capture(executable, listOf(library), harness, emptyMap())

                assertNotEquals(before.fingerprint, after.fingerprint, file.name)
                file.writeBytes(bytes)
                file.setLastModified(modified)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
