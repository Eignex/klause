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
import com.eignex.klause.propagation.bake
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
    fun `candidate phases should only fill enabled unobserved Boolean phases`() {
        for (enabled in listOf(false, true)) {
            val phase = PhaseSaving(1, 0, BacktrackParams(phaseSaving = enabled))

            val values = phase.applyPhase(VarRef.Bool(0), sequenceOf(0L, 1L), candidate = true).toList()

            assertEquals(if (enabled) listOf(1L, 0L) else listOf(0L, 1L), values)
            assertEquals(if (enabled) 1L else 0L, phase.candidateUses)
        }
    }

    @Test
    fun `retained phase sources should precede a numerical candidate`() {
        for (kind in listOf("saved", "target", "solution")) {
            val session = boolSession(1)
            session.pinBool(0, false)
            val phase = PhaseSaving(1, 0,
                BacktrackParams(phaseSaving = true, targetPhasing = true, solutionPhasing = true))
            when (kind) {
                "saved" -> phase.capture(VarRef.Bool(0), session)
                "target" -> phase.captureTargetIfDeeper(session, 1)
                "solution" -> {
                    phase.onSolution(Sample(booleanArrayOf(false), longArrayOf()))
                    phase.setManagedMode(PhaseMode.STABLE)
                }
            }

            val values = phase.applyPhase(VarRef.Bool(0), sequenceOf(1L, 0L), candidate = true).toList()

            assertEquals(listOf(0L, 1L), values)
            assertEquals(0L, phase.candidateUses)
        }
    }

    @Test
    fun `a propagated phase should survive backtracking`() {
        for (nativeSat in listOf(false, true)) {
            val problem = Problem(
                2,
                0,
                emptyArray(),
                arrayOf(
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                ),
            )
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
    fun `a captured int phase should be tried first`() {
        val session = PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyList()))
        session.pinInt(0, 3)
        val phase = PhaseSaving(0, 1, BacktrackParams(phaseSaving = true))
        phase.capture(VarRef.IntVar(0), session)
        assertEquals(listOf(3L, 1L, 5L), phase.applyPhase(VarRef.IntVar(0), sequenceOf(1, 3, 5)).toList())
    }

    @Test
    fun `captureTargetIfDeeper should snapshot only at a new maximum depth`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true))
        phase.captureTargetIfDeeper(session, trailSize = 3)
        session.pinBool(0, false)
        phase.captureTargetIfDeeper(session, trailSize = 1)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `managed STABLE mode should dive on the solution phase`() {
        val phase = PhaseSaving(1, 0, BacktrackParams(solutionPhasing = true))
        phase.onSolution(Sample(booleanArrayOf(true), longArrayOf()))
        phase.setManagedMode(PhaseMode.STABLE)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `managed FOCUSED mode should ignore the target phase`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true))
        phase.captureTargetIfDeeper(session, trailSize = 1)
        phase.setManagedMode(PhaseMode.FOCUSED)
        assertEquals(listOf(0L, 1L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `onConflictTick should rotate the polarity source off TARGET after the rephase interval`() {
        val session = boolSession(1)
        session.pinBool(0, true)
        val phase = PhaseSaving(1, 0, BacktrackParams(targetPhasing = true, rephaseInterval = 2))
        phase.captureTargetIfDeeper(session, trailSize = 1)
        assertEquals(listOf(1L, 0L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
        phase.onConflictTick()
        phase.onConflictTick()
        assertEquals(listOf(0L, 1L), phase.applyPhase(VarRef.Bool(0), sequenceOf(0, 1)).toList())
    }

    @Test
    fun `phase saving should keep saved values across a backtrack`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val params = BacktrackParams(
            randomSeed = 0L,
            phaseSaving = true,
            variableSelector = InputOrder,
            valueSelector = IndomainMin,
        )
        val samples = BacktrackSolver(problem.bake()).enumerate(params).take(2).toList()
        assertEquals(2, samples.size)
        assertEquals(listOf(false, false, false), samples[0].bools.toList())
        assertEquals(listOf(false, false, true), samples[1].bools.toList())
    }
}
