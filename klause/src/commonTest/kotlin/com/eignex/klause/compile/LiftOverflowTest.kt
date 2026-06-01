package com.eignex.klause.compile

import com.eignex.klause.ast.le
import com.eignex.klause.ast.times
import com.eignex.klause.schema.VariableSchema
import kotlin.test.Test
import kotlin.test.assertFails

class LiftOverflowTest {

    @Test
    fun `mul product domain overflow fails at compile time`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 100_000)
            val y by intVar(min = 0, max = 100_000)
            val cap by constraint { (x * y) le 1 }
        }
        assertFails { S().compile() }
    }
}
