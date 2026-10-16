package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import kotlinx.serialization.Serializable

internal fun jvmClassMetadata(bytes: ByteArray): JvmClassMetadata {
    val model = ClassFile.of().parse(bytes)
    return JvmClassMetadata(
        model.thisClass().asInternalName(),
        CaptureFile(bytes.size.toLong(), sha256(bytes)),
        JvmClassVersion(model.majorVersion(), model.minorVersion()),
        model.flags().flagsMask(),
        model.findAttributes(Attributes.sourceFile()).map { it.sourceFile().stringValue() },
        model.findAttributes(Attributes.sourceDebugExtension()).map {
            val contents = it.contents()
            CaptureFile(contents.size.toLong(), sha256(contents))
        },
        model.attributes().map { it.attributeName().stringValue() },
        model.methods().map { method ->
            JvmMethodMetadata(
                method.methodName().stringValue(),
                method.methodType().stringValue(),
                method.flags().flagsMask(),
                method.attributes().map { it.attributeName().stringValue() },
                method.code().map { code ->
                    JvmCodeMetadata(
                        code.attributes().map { it.attributeName().stringValue() },
                        code.findAttributes(Attributes.lineNumberTable()).map { table ->
                            table.lineNumbers().map { JvmLineNumber(it.startPc(), it.lineNumber()) }
                        },
                    )
                }.orElse(null),
            )
        },
    )
}

@Serializable
internal data class JvmClassMetadata(
    val internalName: String,
    val identity: CaptureFile,
    val version: JvmClassVersion,
    val accessFlags: Int,
    val sourceFiles: List<String>,
    val sourceDebugExtensionIdentities: List<CaptureFile>,
    val attributes: List<String>,
    val methods: List<JvmMethodMetadata>,
)

@Serializable
internal data class JvmClassVersion(val major: Int, val minor: Int)

@Serializable
internal data class JvmMethodMetadata(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val attributes: List<String>,
    val code: JvmCodeMetadata?,
)

@Serializable
internal data class JvmCodeMetadata(val attributes: List<String>, val lineNumberTables: List<List<JvmLineNumber>>)

@Serializable
internal data class JvmLineNumber(val startPc: Int, val lineNumber: Int)
