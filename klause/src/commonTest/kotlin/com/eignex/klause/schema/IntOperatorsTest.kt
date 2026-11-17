package com.eignex.klause.schema

import com.eignex.klause.compile.compile
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntScale
import com.eignex.klause.model.IntSum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IntOperatorsTest {
    @Test
    fun `negating an Int MIN_VALUE literal should keep its exact magnitude`() {
        assertEquals(IntScale(-1, IntLit(Int.MIN_VALUE)), -IntLit(Int.MIN_VALUE))
    }

    @Test
    fun `subtracting an Int MIN_VALUE constant should keep its exact magnitude`() {
        val x = IntRef("x")
        assertEquals(IntSum(listOf(x, IntScale(-1, IntLit(Int.MIN_VALUE)))), x - Int.MIN_VALUE)
    }

    @Test
    fun `negating an Int MIN_VALUE coefficient should keep its exact magnitude`() {
        val scaled = IntScale(Int.MIN_VALUE, IntRef("x"))
        assertEquals(IntScale(-1, scaled), -scaled)
    }

    @Test
    fun `subtracting the smallest negatable constant should fold to Int MAX_VALUE`() {
        val x = IntRef("x")
        assertEquals(IntSum(listOf(x, IntLit(Int.MAX_VALUE))), x - (Int.MIN_VALUE + 1))
    }

    @Test
    fun `subtracting Int MIN_VALUE should fail compilation instead of wrapping`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val cap by constraint { x - Int.MIN_VALUE le 0 }
        }
        assertFailsWith<IllegalArgumentException> { S().compile() }
    }
}
