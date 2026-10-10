package com.eignex.klause.solver.integration

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.*
import com.eignex.klause.solver.objective.maximizeBool
import com.eignex.klause.solver.objective.minimizeBool
import com.eignex.klause.solver.objective.minimizeInt
import kotlin.test.Test
import kotlin.test.assertEquals

class VariableObjectiveTest {

    @Test
    fun `Problem maximizeBool extension negates the coefficient`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val maxObj = problem.maximizeBool(boolVar = 0)
        assertEquals(-1L, maxObj.boolWeights[0])
        assertEquals(0L, maxObj.boolWeights[1])
    }

    @Test
    fun `objective factories reject an out-of-range variable id`() {
        val intsOnly = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 1) },
            factors = emptyArray(),
        )
        val boolsOnly = Problem(numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        try {
            intsOnly.minimizeInt(intVar = 5)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {}
        try {
            boolsOnly.minimizeBool(boolVar = -1)
            error("should have thrown")
        } catch (_: IllegalArgumentException) {}
    }
}
