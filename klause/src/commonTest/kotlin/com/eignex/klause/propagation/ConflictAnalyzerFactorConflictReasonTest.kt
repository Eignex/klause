package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConflictAnalyzerFactorConflictReasonTest {

    @Test
    fun `a linear bound cites the weakest bound that still forces it`() {
        // x0 + 2 * x1 <= 10: deciding x0 >= 4 forces x1 <= 3, which x0 >= 3 already does.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 10)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        check(state.setIntMinAsDecision(0, 4))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val atom = Lit.variable(state.reasonOf(state.intMaxAntecedents[1])!!.single()) - problem.numBoolVars
        assertEquals(0 to 3L, state.atoms.intVar[atom] to state.atoms.threshold[atom])
    }

    @Test
    fun `a wide linear bound cites the bounds as they stood when it was deduced`() {
        // x0 + 2 * x1 + x2 + ... + x32 <= 10: deciding x0 >= 4 forces x1 <= 3, which x0 >= 3 already does,
        // and a later x0 >= 6 must not change what that deduction rested on.
        val n = 33
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 10) },
            factors = arrayOf<Factor>(
                Linear(IntArray(n) { if (it == 1) 2 else 1 }, IntArray(n) { it }, LinearOp.LE, 10),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        check(state.setIntMinAsDecision(0, 4))
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0))
        check(state.setIntMinAsDecision(0, 6))

        val atom = Lit.variable(state.reasonOf(state.intMaxAntecedents[1])!!.single()) - problem.numBoolVars

        assertEquals(0 to 3L, state.atoms.intVar[atom] to state.atoms.threshold[atom])
    }

    @Test
    fun `a linear conflict cites the weakest bound that still forces it`() {
        // x0 + x1 <= 5 with x1 >= 3 at the root: deciding x0 >= 4 overshoots by two, so x0 >= 3 already
        // forces the conflict and the reason cites that rather than the decision's own bound.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(3, 10)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        check(state.setIntMinAsDecision(0, 4))
        state.currentFactor = 0

        assertFalse(state.factorAt(0).propagate(state, 0))

        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val atom = Lit.variable(reason.single()) - problem.numBoolVars
        assertEquals(0 to 3L, state.atoms.intVar[atom] to state.atoms.threshold[atom])
    }

    @Test
    fun `bound atom registry and analyzer resolution end-to-end`() {
        // Construct a scenario where:
        //   - ReifiedLinear A: x ↔ (v0 = 5).
        //   - Linear C: v0 + v1 = 8.
        //   - ReifiedLinear B: y ↔ (v1 ≥ 4).
        //
        // Decide x=true at level 1 → A pins v0=5, C tightens v1.max=v1.min=3, B's
        // alwaysHolds=false / neverHolds=true on (v1 ≥ 4): pins y=false at level 1.
        //
        // To trigger an atom-resolvable learned clause, manually inject an atom-lit
        // antecedent and ensure the analyzer can resolve it via [PropagationState]'s
        // atom registry. This test verifies the atom infrastructure: allocation,
        // truth derivation, level tracking, and analyzer dispatch.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
                Linear(
                    intArrayOf(1, 1),
                    intArrayOf(0, 1),
                    LinearOp.EQ,
                    8,
                ),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        // State: v0=5, v1=3 (forced by Linear after x=true at level 1).
        val state = PropagationState(
            problem,
            Assumptions.None,
        )
        state.pinBoolAsDecision(0, true)
        state.runToFixpoint(allFactors = false)
        // v0 should be [5,5] and v1 should be [3,3], both at level 1.
        assertEquals(5, state.intDomains[0].min)
        assertEquals(5, state.intDomains[0].max)
        assertEquals(3, state.intDomains[1].min)
        assertEquals(3, state.intDomains[1].max)
        // Allocate atom [v1 ≥ 3]: should hold (currently true), level 1 (when v1.min
        // was tightened by Linear), antecedents from intMinAntecedents[v1]. v1's *lower*
        // bound was forced by `v0 + v1 = 8` via the hi side (v1 ≥ 8 − v0.max = 8 − 5 = 3),
        // so it depends only on v0.max — i.e. ¬[v0≤5]. The direction-aware antecedent
        // collection (collectLinearDirAntecedents) correctly omits the irrelevant ¬[v0≥5]
        // (v0's lower bound plays no part in v1's lower bound), yielding a sharper reason.
        val atomVarGE3 = state.atomVarGe(1, 3)
        val atomId = state.atomIdOf(atomVarGE3)
        assertEquals(true, state.atomCurrentTruth(atomId), "atom [v1≥3] should hold (v1.min=3≥3)")
        assertEquals(1, state.atomLevelForConflict(atomId), "atom became known at level 1")
        val ant = state.atomAntecedentsDerived(atomId)
        assertTrue(ant != null, "atom should have antecedents from intMinAntecedents[v1]")
        val ge5 = Lit.make(state.atomVarGe(0, 5), false)
        val le5 = Lit.make(state.atomVarLe(0, 5), false)
        val antSet = requireNotNull(ant).toSet()
        assertTrue(
            le5 in antSet,
            "atom antecedents should contain the driving bound ¬[v0≤5], got ${ant.toList()}",
        )
        assertTrue(
            ge5 !in antSet,
            "direction-aware reason should omit the irrelevant ¬[v0≥5], got ${ant.toList()}",
        )
        // Allocate a second atom [v1 ≥ 10] — should be false (v1.max=3 < 10).
        val atomVarGE10 = state.atomVarGe(1, 10)
        val atomId10 = state.atomIdOf(atomVarGE10)
        assertEquals(
            false,
            state.atomCurrentTruth(atomId10),
            "atom [v1≥10] should not hold (v1.max=3 < 10)",
        )
        // Identity: re-requesting the same atom should return the same id (cached).
        val atomVarGE3Again = state.atomVarGe(1, 3)
        assertEquals(atomVarGE3, atomVarGE3Again, "atom registry should dedupe")
    }

    @Test
    fun `atom-lit clause unit-propagates via state pinLit dispatch`() {
        // End-to-end atom-lit clause: a learned-style Clause whose literals reference
        // atom-var ids dispatches through state.litTrue / pinLit. Pinning the underlying
        // int var to make one atom false forces the other atom to be true → re-derives
        // as a corresponding int tighten on its underlying int var.
        //
        // Setup: int v0 in [0, 9], int v1 in [0, 9]. Allocate atoms `[v0 ≥ 5]` and
        // `[v1 ≥ 7]`. Add Clause `[[v0 ≥ 5], [v1 ≥ 7]]` (positive atom lits). Then
        // tighten v0.max to 4 → atom `[v0 ≥ 5]` becomes false → clause unit-propagates
        // `[v1 ≥ 7]` to true, which re-derives as `tightenIntMin(v1, 7)`.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = emptyArray(),
        )
        val state = PropagationState(
            problem,
            Assumptions.None,
        )
        // Atom truth derives from currentTruth(): [v0 ≥ 5] is true iff v0.min ≥ 5.
        // With dom [0,9], v0.min = 0, so atom is currently false. Similarly [v1 ≥ 7].
        val atomV0Ge5 = state.atomVarGe(0, 5)
        val atomV1Ge7 = state.atomVarGe(1, 7)
        // Add the clause as a learned clause.
        val clause = Clause(
            intArrayOf(
                Lit.make(atomV0Ge5, true),
                Lit.make(atomV1Ge7, true),
            ),
        )
        state.addLearnedClause(clause, lbd = 2)
        // Decide v0 ≤ 4 (which makes atom [v0 ≥ 5] false permanently, since v0.max < 5).
        // We do this by calling tightenIntMax directly with no antecedents (a decision).
        state.currentLevel = 1
        assertTrue(state.tightenIntMax(0, 4))
        // Run propagation — the clause must wake (atom-lit watcher fires) and
        // unit-propagate [v1 ≥ 7] which translates to tightenIntMin(v1, 7).
        val conflict = state.runToFixpoint(allFactors = false)
        assertTrue(conflict == null, "no conflict expected; clause should unit-propagate")
        assertEquals(
            7,
            state.intDomains[1].min,
            "atom-lit clause should have unit-propagated [v1 ≥ 7] → tightenIntMin(v1, 7)",
        )
    }

    @Test
    fun `a conflict takes its level from its own path and not the previous analysis`() {
        // x1 >= 1 with x2 >= 1 forces x3 >= 3 (x3 >= x1 + x2 + 1), which with x0 >= 3 breaks x0 + x1 + x3 <= 6. The
        // first conflict has x0 >= 3 and x1 >= 1 at levels 1 and 2; the second at levels 2 and 3, where x3 >= 3 must
        // be resolved back to x1's decision for the clause to assert.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 3), LinearOp.LE, 6),
                Linear(intArrayOf(1, -1, -1), intArrayOf(3, 1, 2), LinearOp.GE, 1),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        val root = state.mark()
        fun propagateToConflict() {
            state.currentFactor = 1
            check(state.factorAt(1).propagate(state, 1))
            state.currentFactor = 0
            check(!state.factorAt(0).propagate(state, 0))
        }
        check(state.setIntMinAsDecision(2, 1) && state.tightenIntMin(0, 3) && state.setIntMinAsDecision(1, 1))
        propagateToConflict()
        state.conflictAnalyzer.analyze(0)
        state.undoTo(root)
        check(state.setIntMinAsDecision(2, 1) && state.setIntMinAsDecision(0, 3) && state.setIntMinAsDecision(1, 1))
        propagateToConflict()

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(state.conflictAnalyzer.analyze(0))

        assertTrue(learned.asserting)
        assertEquals(2, learned.backjumpLevel)
    }
}
