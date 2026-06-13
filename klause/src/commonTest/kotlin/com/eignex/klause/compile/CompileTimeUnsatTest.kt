package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.le
import com.eignex.klause.schema.minus
import com.eignex.klause.schema.plus
import kotlin.test.Test
import kotlin.test.assertFails

class CompileTimeUnsatTest {

    @Test
    fun `constant false equality fails at compile time`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val cap by constraint { (x - x) eq 5 }
        }
        assertFails { S().compile() }
    }

    @Test
    fun `constant false inequality fails at compile time`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val cap by constraint { (x - x + 5) le 1 }
        }
        assertFails { S().compile() }
    }
}
