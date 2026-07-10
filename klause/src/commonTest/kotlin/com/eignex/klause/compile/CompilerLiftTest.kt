package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.le
import com.eignex.klause.schema.times
import kotlin.test.Test
import kotlin.test.assertFails

class CompilerLiftTest {

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
