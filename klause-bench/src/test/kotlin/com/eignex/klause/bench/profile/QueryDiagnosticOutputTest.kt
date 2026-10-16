package com.eignex.klause.bench.profile

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueryDiagnosticOutputTest {
    @Test
    fun `multiple streams should share one output byte allowance`() {
        val output = QueryDiagnosticOutput(4L)
        val left = ByteArrayOutputStream()
        val right = ByteArrayOutputStream()
        output.wrap(left).write(byteArrayOf(1, 2, 3))
        output.wrap(right).write(4)

        assertFailsWith<IOException> { output.wrap(right).write(5) }

        assertTrue(output.exceeded)
        assertEquals(3, left.size())
        assertEquals(1, right.size())
    }
}
