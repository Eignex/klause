package com.eignex.klause.bench.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.lang.classfile.ClassFile
import java.lang.classfile.attribute.SourceDebugExtensionAttribute
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmBytecodeEncodingTest {
    @Test
    fun `column encoding round trips nested symbols instructions and line tables`() {
        val bytes = ClassFile.of().build(ClassDesc.of("EncodingSample")) { builder ->
            builder.with(SourceDebugExtensionAttribute.of(byteArrayOf(0, -1, 1)))
            builder.withMethodBody("entry", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC) {
                it.lineNumber(17).invokestatic(ClassDesc.of("Absent"), "target", MethodTypeDesc.of(CD_void))
                    .lineNumber(11).return_()
            }
        }
        val metadata = JvmBytecodeMetadataReader.read(bytes)

        val encoded = JvmBytecodeEncoding.encode(metadata)
        val actual = Json.decodeFromJsonElement(JvmBytecodeMetadata.serializer(), JvmBytecodeEncoding.expand(encoded))

        assertEquals(metadata, actual)
        assertTrue(encoded.getValue("constantPool").jsonArray.all { it is JsonArray })
        val code = encoded.getValue("methods").jsonArray.single().jsonObject.getValue("code").jsonObject
        assertTrue(code.getValue("instructions").jsonArray.all { it is JsonArray })
        assertEquals(listOf(17, 11), actual.methods.single().code?.lineNumberTables?.flatten()?.map { it.lineNumber })
    }
}
