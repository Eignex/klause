package com.eignex.klause.bench.profile

import kotlinx.serialization.Serializable
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.ConstantPool
import java.lang.classfile.constantpool.DynamicConstantPoolEntry
import java.lang.classfile.constantpool.MemberRefEntry
import java.lang.classfile.constantpool.MethodHandleEntry
import java.lang.classfile.constantpool.MethodTypeEntry
import java.lang.classfile.constantpool.ModuleEntry
import java.lang.classfile.constantpool.NameAndTypeEntry
import java.lang.classfile.constantpool.PackageEntry
import java.lang.classfile.constantpool.PoolEntry
import java.lang.classfile.constantpool.StringEntry
import java.lang.classfile.constantpool.Utf8Entry

internal object JvmBytecodeSymbols {
    fun read(pool: ConstantPool): List<JvmBytecodeSymbol> {
        val entries = pool.toList()
        val symbolicStrings = mutableSetOf<Int>()
        val links = entries.associate { entry ->
            if (entry is DynamicConstantPoolEntry) {
                require(entry.bootstrapMethodIndex() in 0 until pool.bootstrapMethodCount()) {
                    "Dynamic constant refers to an absent bootstrap"
                }
            }
            val children = references(entry)
            if (entry !is StringEntry) {
                for (index in children) if (pool.entryByIndex(index) is Utf8Entry) symbolicStrings += index
            }
            entry.index() to children
        }
        return entries.map { entry ->
            JvmBytecodeSymbol(
                entry.index(),
                entry.tag(),
                entry.width(),
                links.getValue(entry.index()),
                (entry as? DynamicConstantPoolEntry)?.bootstrapMethodIndex(),
                (entry as? MethodHandleEntry)?.kind(),
                if (entry is Utf8Entry && entry.index() in symbolicStrings) entry.stringValue() else null,
            )
        }
    }

    private fun references(entry: PoolEntry): List<Int> = when (entry) {
        is ClassEntry -> listOf(entry.name().index())
        is MemberRefEntry -> listOf(entry.owner().index(), entry.nameAndType().index())
        is NameAndTypeEntry -> listOf(entry.name().index(), entry.type().index())
        is MethodHandleEntry -> listOf(entry.reference().index())
        is MethodTypeEntry -> listOf(entry.descriptor().index())
        is DynamicConstantPoolEntry -> listOf(entry.nameAndType().index())
        is StringEntry -> listOf(entry.utf8().index())
        is ModuleEntry -> listOf(entry.name().index())
        is PackageEntry -> listOf(entry.name().index())
        else -> emptyList()
    }
}

@Serializable
internal data class JvmBytecodeSymbol(
    val index: Int,
    val tag: Int,
    val width: Int,
    val references: List<Int>,
    val bootstrapIndex: Int?,
    val methodHandleKind: Int?,
    val symbolicText: String?,
)

@Serializable
internal data class JvmBytecodeBootstrap(val index: Int, val methodHandleIndex: Int, val argumentIndices: List<Int>)
