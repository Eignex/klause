package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmBytecodeLayoutTest {
    @Test
    fun `wide operands retain instruction boundaries and unresolved returns`() {
        val code = byteArrayOf(0, 196.toByte(), 21, 1, 44, 196.toByte(), 132.toByte(), 1, 44, 0, 2,
            196.toByte(), 169.toByte(), 1, 44)

        val instructions = JvmBytecodeLayout.read(code)

        assertEquals(listOf(0, 1, 5, 11), instructions.map { it.bci })
        assertEquals(listOf(1, 4, 6, 4), instructions.map { it.byteLength })
        assertEquals(listOf(null, 300, 300, 300), instructions.map { it.localIndex })
        assertEquals(listOf("NOP", "ILOAD_W", "IINC_W", "RET_W"), instructions.map { it.opcode })
        assertTrue(instructions.last().subroutineReturnTargetUnresolved)
    }

    @Test
    fun `signed branches retain forward and backward targets`() {
        val code = byteArrayOf(0, 153.toByte(), 0, 6, 167.toByte(), 255.toByte(), 252.toByte(), 177.toByte())

        val instructions = JvmBytecodeLayout.read(code)

        assertEquals(listOf(7, 0), instructions.flatMap { it.branchTargets }.map { it.bci })
    }

    @ParameterizedTest
    @ValueSource(strings = ["table-0", "table-1", "table-2", "table-3",
        "lookup-0", "lookup-1", "lookup-2", "lookup-3"])
    fun `switch alignment preserves case order and repeated destinations`(variant: String) {
        val bci = variant.substringAfter('-').toInt()
        val table = variant.startsWith("table")
        val padding = (4 - (bci + 1) % 4) % 4
        val start = bci + 1 + padding
        val end = start + if (table) 20 else 24
        val bytes = ByteArray(end + 1)
        bytes[bci] = (if (table) 170 else 171).toByte()
        for (index in bci + 1 until start) bytes[index] = 127
        val data = ByteBuffer.wrap(bytes)
        data.putInt(start, end - bci)
        if (table) {
            data.putInt(start + 4, -3).putInt(start + 8, -2)
                .putInt(start + 12, end - bci).putInt(start + 16, end - bci)
        } else {
            data.putInt(start + 4, 2).putInt(start + 8, -3).putInt(start + 12, end - bci)
                .putInt(start + 16, 7).putInt(start + 20, end - bci)
        }
        bytes[end] = 177.toByte()

        val instruction = JvmBytecodeLayout.read(bytes).single { it.bci == bci }

        assertEquals(padding, instruction.switchPaddingBytes)
        assertEquals(listOf("default", "case", "case"), instruction.branchTargets.map { it.kind })
        assertEquals(listOf(null, -3, if (table) -2 else 7), instruction.branchTargets.map { it.caseKey })
        assertEquals(listOf(end, end, end), instruction.branchTargets.map { it.bci })
        assertEquals(end - bci, instruction.byteLength)
    }

    @Test
    fun `a switch with no explicit cases retains its default target`() {
        val code = ByteArray(13)
        code[0] = 171.toByte()
        ByteBuffer.wrap(code).putInt(4, 12).putInt(8, 0)
        code[12] = 177.toByte()

        val targets = JvmBytecodeLayout.read(code).first().branchTargets

        assertEquals(listOf(JvmBytecodeTarget("default", 12, null)), targets)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "c4", "c4000000", "a70001b1", "a7ffffb1", "c880000000",
        "00c87fffffffb1", "ba00010001", "b900010000", "b900010101", "bc03b1", "c5000100b1",
        "1200b1", "fe", "1100", "b800"])
    fun `invalid operands and incomplete or unaligned instructions are rejected`(hex: String) {
        val code = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertFailsWith<IllegalArgumentException> { JvmBytecodeLayout.read(code) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["negative-count", "large-count", "repeated-key", "reversed-keys", "middle-target"])
    fun `malformed switch tables cannot admit a parsed prefix`(variant: String) {
        val code = ByteArray(29)
        code[0] = 171.toByte()
        val data = ByteBuffer.wrap(code).putInt(4, 28).putInt(8, 2)
            .putInt(12, -3).putInt(16, 28).putInt(20, 7).putInt(24, 28)
        code[28] = 177.toByte()
        when (variant) {
            "negative-count" -> data.putInt(8, -1)
            "large-count" -> data.putInt(8, Int.MAX_VALUE)
            "repeated-key" -> data.putInt(20, -3)
            "reversed-keys" -> data.putInt(20, -4)
            else -> data.putInt(24, 1)
        }

        assertFailsWith<IllegalArgumentException> { JvmBytecodeLayout.read(code) }
    }
}
