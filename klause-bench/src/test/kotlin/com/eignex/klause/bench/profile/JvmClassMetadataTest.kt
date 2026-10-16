package com.eignex.klause.bench.profile

import java.lang.invoke.MethodType
import java.util.ArrayList
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
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

        assertEquals(expected.toSet(), metadata.methods.filter { it.name != "<clinit>" }
            .map { it.name to it.descriptor }.toSet())
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
    @ValueSource(ints = [0, 3, 7])
    fun `truncated class files are rejected`(length: Int) {
        val bytes = requireNotNull(ArrayList::class.java.getResourceAsStream("ArrayList.class"))
            .use { it.readNBytes(length) }

        assertFailsWith<IllegalArgumentException> { jvmClassMetadata(bytes) }
    }
}
