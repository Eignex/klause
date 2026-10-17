package com.eignex.klause.bench.profile

import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JvmRuntimeImageTest {
    @Test
    fun `class bytes are located under their original module`() {
        val expected = requireNotNull(ArrayList::class.java.getResourceAsStream("ArrayList.class"))
            .use { it.readBytes() }

        val actual = withJvmRuntimeImage(Path.of(System.getProperty("java.home"))) { image ->
            Files.readAllBytes(image.getPath("/modules/java.base/java/util/ArrayList.class"))
        }

        assertContentEquals(expected, actual)
    }

    @Test
    fun `observer runtime home is retained`() {
        val original = System.getProperty("java.home")

        withJvmRuntimeImage(Path.of(original)) { image ->
            Files.size(image.getPath("/modules/java.base/module-info.class"))
        }

        assertEquals(original, System.getProperty("java.home"))
    }

    @Test
    fun `runtime image closes when inspection fails`() {
        var opened: FileSystem? = null

        assertFailsWith<IllegalStateException> {
            withJvmRuntimeImage(Path.of(System.getProperty("java.home"))) { image ->
                opened = image
                error("Inspection failed")
            }
        }

        assertFalse(requireNotNull(opened).isOpen)
    }
}
