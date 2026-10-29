package com.eignex.klause.solver.search

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BooleanCoverageAttributionTest {
    private val context = BooleanProofContext(SourceScope(SourceRevision("cnf", "exact-cnf")), "identity:0")

    @Test
    fun `real finite replicas should attribute exact duplicate branch regions`() {
        val model = Problem(3, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2, 4)))).bake()
        val capture = BooleanCoverageAttribution(context)
        val params = BacktrackParams(randomSeed = 17, nativeSat = false,
            variableSelector = InputOrder, valueSelector = IndomainMin)

        for (worker in 0..1) {
            val result = BacktrackSolver(model).solve(params.copy(branchObserver = capture.observer(worker, 3)))
            assertIs<SolveResult.Sat>(result)
        }

        assertTrue(capture.regions > 0)
        assertEquals(capture.regions.toLong(), capture.duplicateEntries)
        assertEquals(capture.regions * 2L, capture.entries)
    }

    @Test
    fun `actual refuted replicas should attribute completed native coverage separately from visits`() {
        val model = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 3)),
        )).bake()
        val capture = BooleanCoverageAttribution(context)

        for (worker in 0..1) {
            val result = BacktrackSolver(model).solve(BacktrackParams(nativeSat = false, randomSeed = 17))
            assertIs<SolveResult.Unsat>(result)
            capture.recordRefutation(context, worker, BooleanCube(emptyList()))
        }

        assertEquals(1, capture.refutedRegions)
        assertEquals(1L, capture.duplicateRefutations)
        assertEquals(0L, capture.entries)
    }

    @Test
    fun `bounded capture should distinguish retries overlap and refused regions`() {
        val capture = BooleanCoverageAttribution(context, maximumRegions = 1, maximumLiterals = 2)
        val cube = BooleanCube(listOf(2, 0))
        assertTrue(capture.record(context, 0, cube))

        assertTrue(capture.record(context, 0, BooleanCube(listOf(0, 2))))
        assertTrue(capture.record(context, 1, cube))
        assertFalse(capture.record(context, 1, BooleanCube(listOf(1))))
        assertFalse(capture.record(context.copy(mapping = "other"), 1, cube))

        assertEquals(1L, capture.repeatedEntries)
        assertEquals(1L, capture.duplicateEntries)
        assertEquals(1L, capture.declinedEntries)
        capture.clear()
        assertEquals(0L, capture.literalBytes)
        assertEquals(0, capture.regions)
    }
}
