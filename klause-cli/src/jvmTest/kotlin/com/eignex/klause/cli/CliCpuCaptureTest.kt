package com.eignex.klause.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CliCpuCaptureTest {
    @Test
    fun `disabled observation should preserve the returned value`() {
        assertEquals(7, captureCliCpuIf(false) { 7 })
    }

    @Test
    fun `disabled observation should preserve the thrown exception`() {
        val expected = IllegalStateException("failure")

        val actual = assertFailsWith<IllegalStateException> { captureCliCpuIf(false) { throw expected } }

        assertSame(expected, actual)
    }
}
