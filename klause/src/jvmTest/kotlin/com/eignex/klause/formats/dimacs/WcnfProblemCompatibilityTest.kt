package com.eignex.klause.formats.dimacs

import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WcnfProblemCompatibilityTest {
    @Test
    fun `legacy constructor retains the supplied model without fold metadata`() {
        val decoded = Dimacs.parseWcnf("3 1 -2 0\n").toProblem()

        val legacy = WcnfProblem::class.java.getConstructor(
            Problem::class.java, LinearObjectiveSpec::class.java, Int::class.javaPrimitiveType,
        ).newInstance(decoded.problem, decoded.objective, decoded.numOriginalBoolVars)

        assertSame(decoded.problem, legacy.problem)
        assertSame(decoded.objective, legacy.objective)
        assertEquals(decoded.numOriginalBoolVars, legacy.numOriginalBoolVars)
        assertTrue(legacy.boolFolds.isEmpty())
    }

    @Test
    fun `legacy copy descriptors retain exact fold metadata`() {
        val decoded = Dimacs.parseWcnf("3 1 -2 0\n").toProblem()
        val type = WcnfProblem::class.java
        assertTrue(decoded.boolFolds.isNotEmpty())

        val direct = type.getMethod(
            "copy", Problem::class.java, LinearObjectiveSpec::class.java, Int::class.javaPrimitiveType,
        ).invoke(decoded, decoded.problem, decoded.objective, decoded.numOriginalBoolVars) as WcnfProblem
        val defaulted = type.getMethod(
            "copy\$default", type, Problem::class.java, LinearObjectiveSpec::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java,
        ).invoke(null, decoded, null, null, 0, 7, null) as WcnfProblem

        assertEquals(decoded, direct)
        assertEquals(decoded, defaulted)
        assertSame(decoded.boolFolds, direct.boolFolds)
        assertSame(decoded.boolFolds, defaulted.boolFolds)
    }
}
