package com.eignex.klause.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxCpuTasksTest {
    @Test
    fun `task names containing spaces and closing parentheses should retain counter positions`() {
        val fields = MutableList(20) { "0" }.apply {
            this[0] = "R"
            this[11] = "120"
            this[12] = "30"
            this[19] = "456"
        }

        val task = parseLinuxTaskCpu("7 (C2) Compiler) " + fields.joinToString(" "))

        assertEquals(LinuxTaskCpu(7L, "C2) Compiler", 456L, 150L), task)
    }

    @Test
    fun `truncated task records should remain unavailable`() {
        assertNull(parseLinuxTaskCpu("7 (main) R 0 0"))
    }

    @Test
    fun `negative task counters should remain unavailable`() {
        val fields = MutableList(20) { "0" }.apply {
            this[0] = "R"
            this[11] = "-1"
        }

        assertNull(parseLinuxTaskCpu("7 (main) " + fields.joinToString(" ")))
    }

    @Test
    fun `overflowing task counters should remain unavailable`() {
        val fields = MutableList(20) { "0" }.apply {
            this[0] = "R"
            this[11] = Long.MAX_VALUE.toString()
            this[12] = "1"
        }

        assertNull(parseLinuxTaskCpu("7 (main) " + fields.joinToString(" ")))
    }
}
