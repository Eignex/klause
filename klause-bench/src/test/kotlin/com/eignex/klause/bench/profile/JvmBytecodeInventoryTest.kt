package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmBytecodeInventoryTest {
    @Test
    fun `alternate class entries retain their separate origins`(@TempDir root: File) {
        val bytes = ClassFile.of().build(ClassDesc.of("Sample")) { builder ->
            builder.withMethodBody("entry", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC) { it.return_() }
        }
        val jar = File(root, "sample.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            for (name in listOf("Sample.class", "META-INF/versions/25/Sample.class")) {
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        val input = JvmBytecodeInput("original", "jars", listOf("a".repeat(40)), root.path,
            mapOf(jar.name to CaptureFile(jar.length(), sha256(jar.readBytes()))))
        val rows = mutableListOf<JsonObject>()

        val summary = JvmBytecodeInventory.inspect(listOf(input), rows::add)

        assertEquals(listOf("Sample.class", "META-INF/versions/25/Sample.class"),
            rows.map { it.getValue("origin").jsonObject.getValue("entry").jsonPrimitive.content })
        assertEquals(listOf(JsonPrimitive(0), JsonPrimitive(1)),
            rows.map { it.getValue("origin").jsonObject.getValue("entryIndex") })
        assertEquals(JsonPrimitive(true), summary["allClassMetadataAvailable"])
        assertEquals(JsonPrimitive(false), summary["loadedClassSelectionProven"])
    }

    @Test
    fun `unavailable class metadata retains subsequent entries and rejects completeness`(@TempDir root: File) {
        val bytes = ClassFile.of().build(ClassDesc.of("Sample")) {}
        val jar = File(root, "sample.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry("Broken.class"))
            output.write(byteArrayOf(1, 2, 3))
            output.closeEntry()
            output.putNextEntry(JarEntry("Sample.class"))
            output.write(bytes)
            output.closeEntry()
        }
        val input = JvmBytecodeInput("original", "jars", listOf("a".repeat(40)), root.path,
            mapOf(jar.name to CaptureFile(jar.length(), sha256(jar.readBytes()))))
        val rows = mutableListOf<JsonObject>()

        val summary = JvmBytecodeInventory.inspect(listOf(input), rows::add)

        assertEquals(2, rows.size)
        assertEquals(JsonNull, rows.first()["metadata"])
        assertTrue(rows.last()["metadata"] is JsonObject)
        assertEquals(JsonPrimitive(1), summary["classErrors"])
        assertEquals(JsonPrimitive(true), summary["allClassEntriesVisited"])
        assertEquals(JsonPrimitive(false), summary["allClassMetadataAvailable"])
    }

    @Test
    fun `duplicate archive entry names retain ambiguity for every position`(@TempDir root: File) {
        val bytes = ClassFile.of().build(ClassDesc.of("Sample")) {}
        val jar = File(root, "sample.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            for (name in listOf("First.class", "Other.class")) {
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        jar.writeBytes(String(jar.readBytes(), Charsets.ISO_8859_1).replace("Other.class", "First.class")
            .toByteArray(Charsets.ISO_8859_1))
        val input = JvmBytecodeInput("original", "jars", listOf("a".repeat(40)), root.path,
            mapOf(jar.name to CaptureFile(jar.length(), sha256(jar.readBytes()))))
        val rows = mutableListOf<JsonObject>()

        val summary = JvmBytecodeInventory.inspect(listOf(input), rows::add)

        assertEquals(2, rows.size)
        assertTrue(rows.all { it["entryBytesAmbiguous"] == JsonPrimitive(true) && it["metadata"] == JsonNull })
        assertEquals(JsonPrimitive(2), summary["classErrors"])
        assertEquals(JsonPrimitive(false), summary["allClassMetadataAvailable"])
    }

    @Test
    fun `input changes during traversal invalidate the entire inventory`(@TempDir root: File) {
        val bytes = ClassFile.of().build(ClassDesc.of("Sample")) {}
        val jar = File(root, "sample.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry("Sample.class"))
            output.write(bytes)
            output.closeEntry()
        }
        val input = JvmBytecodeInput("original", "jars", listOf("a".repeat(40)), root.path,
            mapOf(jar.name to CaptureFile(jar.length(), sha256(jar.readBytes()))))

        assertFailsWith<IllegalArgumentException> {
            JvmBytecodeInventory.inspect(listOf(input)) { File(root, "unexpected").writeText("changed") }
        }
    }
}
