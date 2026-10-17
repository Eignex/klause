package com.eignex.klause.bench.profile

import kotlinx.serialization.Serializable
import java.lang.classfile.Opcode

internal object JvmBytecodeLayout {
    private val opcodes = Opcode.values().associateBy { it.bytecode() }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun read(code: ByteArray): List<JvmBytecodeInstruction> {
        require(code.size in 1..65535) { "Invalid method code length" }
        val input = Bytes(code)
        val result = mutableListOf<JvmBytecodeInstruction>()
        var bci = 0
        while (bci < code.size) {
            val encoded = if (input.u1(bci) == 196) input.u2(bci) else input.u1(bci)
            val opcode = requireNotNull(opcodes[encoded]) { "Unknown bytecode at $bci" }
            val targets = mutableListOf<JvmBytecodeTarget>()
            var padding = 0
            val size = if (opcode == Opcode.TABLESWITCH || opcode == Opcode.LOOKUPSWITCH) {
                padding = (4 - (bci + 1) % 4) % 4
                val start = bci + 1 + padding
                targets += JvmBytecodeTarget("default", input.target(bci, input.s4(start)), null)
                val count = if (opcode == Opcode.TABLESWITCH) {
                    val low = input.s4(start + 4)
                    val high = input.s4(start + 8)
                    val entries = high.toLong() - low + 1
                    require(entries > 0 && entries <= (code.size - start - 12) / 4) { "Invalid tableswitch" }
                    repeat(entries.toInt()) { index ->
                        targets += JvmBytecodeTarget("case", input.target(bci, input.s4(start + 12 + index * 4)),
                            low + index)
                    }
                    12 + entries.toInt() * 4
                } else {
                    val entries = input.s4(start + 4)
                    require(entries >= 0 && entries <= (code.size - start - 8) / 8) { "Invalid lookupswitch" }
                    var previous: Int? = null
                    repeat(entries) { index ->
                        val key = input.s4(start + 8 + index * 8)
                        require(previous == null || key > requireNotNull(previous)) { "Unordered lookupswitch keys" }
                        previous = key
                        targets += JvmBytecodeTarget("case", input.target(bci, input.s4(start + 12 + index * 8)), key)
                    }
                    8 + entries * 8
                }
                1 + padding + count
            } else {
                opcode.sizeIfFixed().also { require(it > 0 && bci + it <= code.size) { "Truncated bytecode" } }
            }
            if (encoded in 153..168 || encoded == 198 || encoded == 199) {
                targets += JvmBytecodeTarget("branch", input.target(bci, input.u2(bci + 1).toShort().toInt()), null)
            } else if (encoded == 200 || encoded == 201) {
                targets += JvmBytecodeTarget("branch", input.target(bci, input.s4(bci + 1)), null)
            }
            val pool = when (encoded) {
                18 -> input.u1(bci + 1)
                19, 20, in 178..187, 189, 192, 193, 197 -> input.u2(bci + 1)
                else -> null
            }
            require(pool == null || pool > 0) { "Invalid constant pool operand" }
            val local = when {
                opcode.isWide() -> input.u2(bci + 2)
                encoded in 21..25 || encoded in 54..58 || encoded == 132 || encoded == 169 -> input.u1(bci + 1)
                else -> null
            }
            if (encoded == 185) {
                require(input.u1(bci + 3) > 0 && input.u1(bci + 4) == 0) { "Invalid invokeinterface operands" }
            }
            if (encoded == 186) require(input.u2(bci + 3) == 0) { "Invalid invokedynamic operands" }
            if (encoded == 188) require(input.u1(bci + 1) in 4..11) { "Invalid newarray type" }
            if (encoded == 197) require(input.u1(bci + 3) > 0) { "Invalid multianewarray dimensions" }
            result += JvmBytecodeInstruction(bci, opcode.name, size, pool, local, targets, padding,
                opcode == Opcode.RET || opcode == Opcode.RET_W)
            bci += size
        }
        require(bci == code.size)
        val starts = result.mapTo(mutableSetOf()) { it.bci }
        require(result.all { instruction -> instruction.branchTargets.all { it.bci in starts } }) {
            "Branch target is not an instruction boundary"
        }
        return result
    }

    private class Bytes(val code: ByteArray) {
        fun u1(index: Int): Int {
            require(index in code.indices) { "Truncated bytecode operand" }
            return code[index].toInt() and 255
        }

        fun u2(index: Int): Int = (u1(index) shl 8) or u1(index + 1)

        fun s4(index: Int): Int = (u1(index) shl 24) or (u1(index + 1) shl 16) or
            (u1(index + 2) shl 8) or u1(index + 3)

        fun target(origin: Int, offset: Int): Int {
            val value = origin.toLong() + offset
            require(value >= 0 && value < code.size) { "Branch target is outside the method" }
            return value.toInt()
        }
    }
}

@Serializable
internal data class JvmBytecodeInstruction(
    val bci: Int,
    val opcode: String,
    val byteLength: Int,
    val constantPoolIndex: Int?,
    val localIndex: Int?,
    val branchTargets: List<JvmBytecodeTarget>,
    val switchPaddingBytes: Int,
    val subroutineReturnTargetUnresolved: Boolean,
)

@Serializable
internal data class JvmBytecodeTarget(val kind: String, val bci: Int, val caseKey: Int?)
