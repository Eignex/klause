package com.eignex.klause.compile

import com.eignex.klause.ast.eq
import com.eignex.klause.ast.le
import com.eignex.klause.ast.minus
import com.eignex.klause.ast.plus
import com.eignex.klause.schema.VariableSchema
import kotlin.test.Test
import kotlin.test.assertFails

class CompileTimeUnsatTest {

    @Test
    fun `constant false equality fails at compile time`() {
        // (x - x) eq 5 reduces to 0 eq 5 in affine form; the compiler should refuse rather
        // than silently emit an unrepairable empty clause.
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
