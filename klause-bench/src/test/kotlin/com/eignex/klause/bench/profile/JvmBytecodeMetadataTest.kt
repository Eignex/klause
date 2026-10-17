package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDesc
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.DirectMethodHandleDesc.Kind.STATIC
import java.lang.constant.DynamicCallSiteDesc
import java.lang.constant.MethodHandleDesc
import java.lang.constant.MethodTypeDesc
import java.lang.invoke.MethodType
import java.util.ArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmBytecodeMetadataTest {
    @Test
    fun `every declared overload constructor and field retains its descriptor`() {
        val type = ArrayList::class.java
        val bytes = requireNotNull(type.getResourceAsStream("ArrayList.class")).use { it.readBytes() }
        val expected = type.declaredMethods.map {
            it.name to MethodType.methodType(it.returnType, it.parameterTypes.toList()).toMethodDescriptorString()
        } + type.declaredConstructors.map {
            "<init>" to MethodType.methodType(Void.TYPE, it.parameterTypes.toList()).toMethodDescriptorString()
        }

        val metadata = JvmBytecodeMetadataReader.read(bytes)

        assertEquals(expected.toSet(), metadata.methods.filter { it.name != "<clinit>" }
            .map { it.name to it.descriptor }.toSet())
        assertEquals(type.declaredFields.map { it.name to it.type.descriptorString() }.toSet(),
            metadata.fields.map { it.name to it.descriptor }.toSet())
    }

    @Test
    fun `symbolic calls retain missing owners without resolving them`() {
        val bytes = ClassFile.of().build(ClassDesc.of("BytecodeSample")) { builder ->
            builder.withMethodBody("entry", MethodTypeDesc.of(CD_void), ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC) {
                it.invokestatic(ClassDesc.of("MissingTarget"), "coldHelper", MethodTypeDesc.of(CD_void)).return_()
            }
        }

        val metadata = JvmBytecodeMetadataReader.read(bytes)

        val code = assertNotNull(metadata.methods.single().code)
        val call = code.instructions.first()
        val symbols = metadata.constantPool.associateBy { it.index }
        val reference = symbols.getValue(assertNotNull(call.constantPoolIndex))
        val owner = symbols.getValue(reference.references[0])
        val signature = symbols.getValue(reference.references[1])
        assertEquals("INVOKESTATIC", call.opcode)
        assertEquals("MissingTarget", symbols.getValue(owner.references.single()).symbolicText)
        assertEquals(listOf("coldHelper", "()V"), signature.references.map { symbols.getValue(it).symbolicText })
        assertEquals(CaptureFile(bytes.size.toLong(), sha256(bytes)), metadata.identity)
        assertFalse(metadata.constantResolutionRequestedByBytecodeParser)
        assertFalse(metadata.classDefinitionRequestedByBytecodeParser)
        assertFalse(metadata.runtimeCalleeSelectionProven)
    }

    @Test
    fun `private native declarations retain their absent code`() {
        val bytes = ClassFile.of().build(ClassDesc.of("BytecodeSample")) { builder ->
            builder.withMethod("nativeHelper", MethodTypeDesc.of(CD_void),
                ClassFile.ACC_PRIVATE or ClassFile.ACC_NATIVE) {}
        }

        val metadata = JvmBytecodeMetadataReader.read(bytes)

        assertEquals("nativeHelper", metadata.methods.single().name)
        assertNull(metadata.methods.single().code)
    }

    @Test
    fun `dynamic calls retain bootstrap argument references without exposing unrelated literals`() {
        val bootstrap = MethodHandleDesc.ofMethod(STATIC, ClassDesc.of("BootstrapHost"), "choose",
            MethodTypeDesc.of(ClassDesc.of("java.lang.invoke.CallSite"),
                ClassDesc.of("java.lang.invoke.MethodHandles\$Lookup"), ClassDesc.of("java.lang.String"),
                ClassDesc.of("java.lang.invoke.MethodType")))
        val worker = MethodHandleDesc.ofMethod(STATIC, ClassDesc.of("MissingTarget"), "worker",
            MethodTypeDesc.of(CD_void))
        val site = DynamicCallSiteDesc.of(bootstrap, "dispatch", MethodTypeDesc.of(CD_void), worker,
            MethodTypeDesc.of(CD_void), ConstantDesc::class.java.cast("literal-private"))
        val bytes = ClassFile.of().build(ClassDesc.of("BytecodeSample")) { builder ->
            builder.withMethodBody("entry", MethodTypeDesc.of(CD_void), ClassFile.ACC_STATIC) {
                it.invokedynamic(site).return_()
            }
        }

        val metadata = JvmBytecodeMetadataReader.read(bytes)

        val call = assertNotNull(metadata.methods.single().code).instructions.first()
        val symbols = metadata.constantPool.associateBy { it.index }
        val dynamic = symbols.getValue(assertNotNull(call.constantPoolIndex))
        val recorded = metadata.bootstrapMethods.single { it.index == dynamic.bootstrapIndex }
        assertEquals("INVOKEDYNAMIC", call.opcode)
        assertEquals(listOf(15, 16, 8), recorded.argumentIndices.map { symbols.getValue(it).tag })
        val literal = symbols.getValue(recorded.argumentIndices.last())
        assertNull(symbols.getValue(literal.references.single()).symbolicText)
        assertTrue(metadata.constantPool.none { it.symbolicText == "literal-private" })
        assertFalse(metadata.generatedBodySelectionProven)
    }
}
