package com.eignex.klause.formats.xcsp3

import kotlin.test.Test
import kotlin.test.assertFailsWith

class FExprTest {
    @Test
    fun `a truncated intension call should fail with a format error`() {
        assertFailsWith<IllegalArgumentException> { FExpr.parse("eq(") }
    }
}
