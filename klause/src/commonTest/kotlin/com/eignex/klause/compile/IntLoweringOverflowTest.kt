package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.abs
import com.eignex.klause.schema.le
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.times
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression for issue #73 — compiler lowering paths that accumulate constants / coefficients
 * or fold scales must do so in `Long` and fail loudly when the result doesn't fit `Int`, rather
 * than wrapping into a garbage domain or bound. Each case asserts a clean compile error
 * ([IllegalArgumentException] from the `require`s) instead of silent corruption.
 */
class IntLoweringOverflowTest {

    @Test
    fun `large-coefficient scale domain overflow is a clean error`() {
        // domainOf(IntScale): 1_000_000 · [0, 100_000] = [0, 1e11], past Int. abs() forces the
        // scaled expression to be materialized, which computes its domain.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 100_000)
            val c by constraint { abs(1_000_000 * x) le 5 }
        }
        assertFailsWith<IllegalArgumentException> { S().compile() }
    }

    @Test
    fun `large-literal affine sum overflow is a clean error`() {
        // affine(IntSum): the running constant 2e9 + 2e9 = 4e9 overflows a 32-bit accumulator.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val c by constraint { (x + 2_000_000_000 + 2_000_000_000) le 0 }
        }
        assertFailsWith<IllegalArgumentException> { S().compile() }
    }

    @Test
    fun `nested scale coefficient fold overflow is a clean error`() {
        // IntOperators.scale constant fold: 100_000 · 100_000 = 1e10 overflows Int.
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val c by constraint { (100_000 * (100_000 * x)) le 0 }
        }
        assertFailsWith<IllegalArgumentException> { S().compile() }
    }
}
