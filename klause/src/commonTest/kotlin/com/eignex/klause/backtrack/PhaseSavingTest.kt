package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhaseSavingTest {

    @Test
    fun `a propagated phase survives backtracking`() {
        for (nativeSat in listOf(false, true)) {
            val problem = Problem(2, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            ))
            val native = PropagationSession(problem, nativeSat = nativeSat)
            val cp = CpSearchComponent(native, branching = CpBranching.None)
            val session = SearchSession(listOf(cp), atoms = SearchAtomRegistry(2))
            val selector = object : VariableSelector by InputOrder {
                override fun fresh(): VariableSelector = this
                override fun pick(session: PropagationSession, rng: Random): VarRef = VarRef.Bool(1)
            }
            val brancher = BacktrackBrancher(
                native,
                BacktrackParams(variableSelector = selector, valueSelector = IndomainMin, phaseSaving = true),
                null,
                NoRestartSchedule,
            )
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            session.popTo(0)

            val alternatives = brancher.nextBranch(session)

            assertEquals(SearchDecision.Bool(Lit.make(1, true)), alternatives?.first())
        }
    }

    private fun boolSession(numBools: Int): PropagationSession =
        PropagationSession(Problem(numBools, 0, emptyArray(), emptyList()))

    @Test
    fun `a captured int phase is tried first`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyList()))
        session.pinInt(0, 3)
        val phase = PhaseSaving(0, 1, BacktrackParams(phaseSaving = true))
        phase.capture(VarRef.IntVar(0), session)
        assertEquals(listOf(3L, 1L, 5L), phase.applyPhase(VarRef.IntVar(0), sequenceOf(1, 3, 5)).toList())
    }

    @Test
    fun `captureTargetIfDeeper only snapshots at a new maximum depth`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true))
        phase.captureTargetIfDeeper(session, trailSize = 3)
        // A shallower prefix must not overwrite the target: flip the assignment, re-offer at depth 1.
        session.pinBool(0, false)
        phase.captureTargetIfDeeper(session, trailSize = 1)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `managed STABLE mode dives on the solution phase`() {
        val phase = PhaseSaving(1, 0, BacktrackParams(solutionPhasing = true))
        phase.onSolution(Sample(booleanArrayOf(true), longArrayOf()))
        phase.setManagedMode(PhaseMode.STABLE)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `managed FOCUSED mode ignores the target phase`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true))
        phase.captureTargetIfDeeper(session, trailSize = 1)
        // The default TARGET source would prefer 1L; FOCUSED uses plain saved phase, and with none
        // captured it falls through to the heuristic order.
        phase.setManagedMode(PhaseMode.FOCUSED)
        assertEquals(listOf(0L, 1L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `onConflictTick rotates the polarity source off TARGET after the rephase interval`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true, rephaseInterval = 2))
        phase.captureTargetIfDeeper(session, trailSize = 1)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
        // Two conflicts rotate TARGET -> SAVED; with no saved bool phase, SAVED mode falls through to the
        // heuristic order unchanged.
        phase.onConflictTick()
        phase.onConflictTick()
        assertEquals(listOf(0L, 1L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }
}
