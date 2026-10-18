package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import kotlinx.serialization.Serializable
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.Opcode
import java.lang.classfile.attribute.CodeAttribute
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.ConstantDynamicEntry
import java.lang.classfile.constantpool.ConstantPool
import java.lang.classfile.constantpool.DynamicConstantPoolEntry
import java.lang.classfile.constantpool.PoolEntry
import java.util.Base64

internal object JvmBytecodeMetadataReader {
    private const val MAX_CLASS_BYTES = 16 * 1024 * 1024
    private const val MAX_CLASS_INSTRUCTIONS = 1_000_000

    @Suppress("LongMethod")
    fun read(bytes: ByteArray): JvmBytecodeMetadata {
        require(bytes.size in 1..MAX_CLASS_BYTES) { "Class metadata input exceeds bounds" }
        val model = ClassFile.of().parse(bytes)
        val pool = model.constantPool()
        require(model.findAttributes(Attributes.bootstrapMethods()).size <= 1) { "Duplicate bootstrap tables" }
        var instructions = 0
        val methods = model.methods().mapIndexed { index, method ->
            val codes = method.findAttributes(Attributes.code())
            require(codes.size <= 1) { "Duplicate method Code attributes" }
            val code = codes.singleOrNull()?.let { attribute ->
                readCode(attribute, pool).also {
                    instructions += it.instructions.size
                    require(instructions <= MAX_CLASS_INSTRUCTIONS) {
                        "Whole class instruction inventory exceeds bounds"
                    }
                }
            }
            JvmMethodBytecode(
                index,
                method.methodName().stringValue(),
                method.methodType().stringValue(),
                method.flags().flagsMask(),
                method.attributes().map { it.attributeName().stringValue() },
                code,
            )
        }
        val bootstraps = (0 until pool.bootstrapMethodCount()).map { index ->
            val entry = pool.bootstrapMethodEntry(index)
            require(entry.bsmIndex() == index)
            JvmBytecodeBootstrap(index, entry.bootstrapMethod().index(), entry.arguments().map { it.index() })
        }
        val sourceDebug = model.findAttributes(Attributes.sourceDebugExtension()).map { it.contents() }
        return JvmBytecodeMetadata(
            model.thisClass().asInternalName(), CaptureFile(bytes.size.toLong(), sha256(bytes)),
            model.majorVersion(), model.minorVersion(), model.flags().flagsMask(),
            model.superclass().map { it.asInternalName() }.orElse(null),
            model.interfaces().map { it.asInternalName() }, model.attributes().map { it.attributeName().stringValue() },
            model.findAttributes(Attributes.sourceFile()).map { it.sourceFile().stringValue() },
            pool.size(), JvmBytecodeSymbols.read(pool), bootstraps,
            model.fields().mapIndexed { index, field ->
                JvmBytecodeField(
                    index,
                    field.fieldName().stringValue(),
                    field.fieldType().stringValue(),
                    field.flags().flagsMask(),
                    field.attributes().map { it.attributeName().stringValue() },
                )
            },
            methods,
            sourceDebugExtensionIdentities = sourceDebug.map { CaptureFile(it.size.toLong(), sha256(it)) },
            sourceDebugExtensionContentsBase64 = sourceDebug.map { Base64.getEncoder().encodeToString(it) },
        )
    }

    private fun readCode(code: CodeAttribute, pool: ConstantPool): JvmMethodCode {
        val bytes = code.codeArray()
        require(bytes.size == code.codeLength())
        val instructions = JvmBytecodeLayout.read(bytes)
        for (instruction in instructions) {
            instruction.constantPoolIndex?.let { validateOperand(instruction, pool.entryByIndex(it)) }
        }
        val starts = instructions.mapTo(mutableSetOf()) { it.bci }
        val handlers = code.exceptionHandlers().map { handler ->
            val start = code.labelToBci(handler.tryStart())
            val end = code.labelToBci(handler.tryEnd())
            val target = code.labelToBci(handler.handler())
            require(start in starts && (end in starts || end == bytes.size) && start < end && target in starts) {
                "Exception handler does not follow code boundaries"
            }
            JvmBytecodeHandler(start, end, target, handler.catchType().map { it.index() }.orElse(null))
        }
        val lines = code.findAttributes(Attributes.lineNumberTable()).map { table ->
            table.lineNumbers().map { line ->
                JvmBytecodeLine(line.startPc(), line.lineNumber(), line.startPc() in starts)
            }
        }
        return JvmMethodCode(
            CaptureFile(bytes.size.toLong(), sha256(bytes)),
            code.maxStack(),
            code.maxLocals(),
            code.attributes().map { it.attributeName().stringValue() },
            lines,
            instructions,
            handlers,
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun validateOperand(instruction: JvmBytecodeInstruction, entry: PoolEntry) {
        val tag = entry.tag()
        val allowed = when (Opcode.valueOf(instruction.opcode)) {
            Opcode.GETSTATIC, Opcode.PUTSTATIC, Opcode.GETFIELD, Opcode.PUTFIELD -> setOf(PoolEntry.TAG_FIELDREF)

            Opcode.INVOKEVIRTUAL -> setOf(PoolEntry.TAG_METHODREF)

            Opcode.INVOKESPECIAL, Opcode.INVOKESTATIC ->
                setOf(PoolEntry.TAG_METHODREF, PoolEntry.TAG_INTERFACE_METHODREF)

            Opcode.INVOKEINTERFACE -> setOf(PoolEntry.TAG_INTERFACE_METHODREF)

            Opcode.INVOKEDYNAMIC -> setOf(PoolEntry.TAG_INVOKE_DYNAMIC)

            Opcode.NEW, Opcode.ANEWARRAY, Opcode.CHECKCAST, Opcode.INSTANCEOF, Opcode.MULTIANEWARRAY ->
                setOf(PoolEntry.TAG_CLASS)

            Opcode.LDC, Opcode.LDC_W -> setOf(
                PoolEntry.TAG_INTEGER,
                PoolEntry.TAG_FLOAT,
                PoolEntry.TAG_STRING,
                PoolEntry.TAG_CLASS,
                PoolEntry.TAG_METHOD_TYPE,
                PoolEntry.TAG_METHOD_HANDLE,
                PoolEntry.TAG_DYNAMIC,
            )

            Opcode.LDC2_W -> setOf(PoolEntry.TAG_LONG, PoolEntry.TAG_DOUBLE, PoolEntry.TAG_DYNAMIC)

            else -> error("Unexpected constant pool operand")
        }
        require(tag in allowed) { "Constant pool operand has the wrong kind" }
        if (entry is DynamicConstantPoolEntry) {
            require(entry.bootstrapMethodIndex() in 0 until entry.constantPool().bootstrapMethodCount())
        }
        if (entry is ConstantDynamicEntry) {
            val categoryTwo = entry.type().stringValue() in setOf("J", "D")
            require((instruction.opcode == "LDC2_W") == categoryTwo) { "Dynamic constant has the wrong category" }
        }
        if (entry is ClassEntry) entry.asSymbol()
    }
}

@Serializable
internal data class JvmBytecodeMetadata(
    val internalName: String,
    val identity: CaptureFile,
    val majorVersion: Int,
    val minorVersion: Int,
    val accessFlags: Int,
    val superclass: String?,
    val interfaces: List<String>,
    val attributes: List<String>,
    val sourceFiles: List<String>,
    val constantPoolSlots: Int,
    val constantPool: List<JvmBytecodeSymbol>,
    val bootstrapMethods: List<JvmBytecodeBootstrap>,
    val fields: List<JvmBytecodeField>,
    val methods: List<JvmMethodBytecode>,
    val classFileVerificationPerformed: Boolean = false,
    val constantResolutionRequestedByBytecodeParser: Boolean = false,
    val classDefinitionRequestedByBytecodeParser: Boolean = false,
    val runtimeCalleeSelectionProven: Boolean = false,
    val loadedClassSelectionProven: Boolean = false,
    val generatedBodySelectionProven: Boolean = false,
    val fullSourcePhaseCoverageProven: Boolean = false,
    val compilerElapsedTimeProven: Boolean = false,
    val acceptedSavingsCredit: Boolean = false,
    val sourceDebugExtensionIdentities: List<CaptureFile> = emptyList(),
    val sourceDebugExtensionContentsBase64: List<String> = emptyList(),
)

@Serializable
internal data class JvmBytecodeField(
    val fieldIndex: Int,
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val attributes: List<String>,
)

@Serializable
internal data class JvmMethodBytecode(
    val methodIndex: Int,
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val attributes: List<String>,
    val code: JvmMethodCode?,
)

@Serializable
internal data class JvmMethodCode(
    val identity: CaptureFile,
    val maxStack: Int,
    val maxLocals: Int,
    val attributes: List<String>,
    val lineNumberTables: List<List<JvmBytecodeLine>>,
    val instructions: List<JvmBytecodeInstruction>,
    val exceptionHandlers: List<JvmBytecodeHandler>,
)

@Serializable
internal data class JvmBytecodeLine(val startPc: Int, val lineNumber: Int, val instructionBoundary: Boolean)

@Serializable
internal data class JvmBytecodeHandler(
    val startBci: Int,
    val endBciExclusive: Int,
    val handlerBci: Int,
    val catchType: Int?,
)
