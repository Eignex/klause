package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.classfile.ClassFile
import java.lang.classfile.attribute.SourceDebugExtensionAttribute
import java.lang.constant.ClassDesc
import java.lang.invoke.MethodType
import java.util.ArrayList
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmClassMetadataTest {
    @Test
    fun `every declared overload retains its descriptor`() {
        val type = ArrayList::class.java
        val bytes = requireNotNull(type.getResourceAsStream("ArrayList.class")).use { it.readBytes() }
        val expected = type.declaredMethods.map {
            it.name to MethodType.methodType(it.returnType, it.parameterTypes.toList()).toMethodDescriptorString()
        } + type.declaredConstructors.map {
            "<init>" to MethodType.methodType(Void.TYPE, it.parameterTypes.toList()).toMethodDescriptorString()
        }

        val metadata = jvmClassMetadata(bytes)

        assertEquals(
            expected.toSet(),
            metadata.methods.filter { it.name != "<clinit>" }
            .map { it.name to it.descriptor }.toSet()
        )
    }

    @Test
    fun `source lines remain associated with their method`() {
        val type = javaClass
        val bytes = requireNotNull(type.getResourceAsStream("JvmClassMetadataTest.class")).use { it.readBytes() }

        val metadata = jvmClassMetadata(bytes)

        assertEquals(listOf("JvmClassMetadataTest.kt"), metadata.sourceFiles)
        val method = metadata.methods.single { it.name == "source lines remain associated with their method" }
        assertTrue(requireNotNull(method.code).lineNumberTables.flatten().any { it.lineNumber > 0 })
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", "00", "fffe", "c080eda0bdedb880", "534d41500a4578616d706c652e6b740a4b6f746c696e0a2a450a"],
    )
    fun `source debug payloads retain their exact bytes`(hex: String) {
        val contents = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val bytes = ClassFile.of().build(ClassDesc.of("MetadataSample")) {
            it.with(SourceDebugExtensionAttribute.of(contents))
        }

        val metadata = jvmClassMetadata(bytes)

        assertContentEquals(
            contents, Base64.getDecoder().decode(metadata.sourceDebugExtensionContentsBase64.single()),
        )
        assertEquals(
            CaptureFile(contents.size.toLong(), sha256(contents)),
            metadata.sourceDebugExtensionIdentities.single(),
        )
    }

    @Test
    fun `classes without source debug payloads retain an empty payload inventory`() {
        val bytes = requireNotNull(ArrayList::class.java.getResourceAsStream("ArrayList.class")).use { it.readBytes() }

        val metadata = jvmClassMetadata(bytes)

        assertTrue(metadata.sourceDebugExtensionContentsBase64.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3, 7])
    fun `truncated class files are rejected`(length: Int) {
        val bytes = requireNotNull(ArrayList::class.java.getResourceAsStream("ArrayList.class"))
            .use { it.readNBytes(length) }

        assertFailsWith<IllegalArgumentException> { jvmClassMetadata(bytes) }
    }
}
