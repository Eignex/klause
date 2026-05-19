package com.eignex.klause.solver.propagation

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Xor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Asserts that each non-Clause bool-pinning factor (Cardinality, PseudoBoolean,
 * ReifiedCardinality, ReifiedPseudoBoolean, Xor) produces a sound clause-form learned
 * clause when propagation conflict fires through it. Each scenario forces a single
 * decision to cascade through Clauses into a propagate-failure inside the target
 * factor; the analyzer then walks antecedents back to the decision and learns a
 * clause containing that decision's literal.
 */
class FactorConflictReasonTest {

    /** Wire a Clause `¬trigger ∨ ¬target`. When `trigger` becomes true, propagation
     *  unit-pins `target` to false. Used to force several constraint literals false in
     *  one decision-driven cascade. */
    private fun forceFalseIf(trigger: Int, target: Int): Clause =
        Clause(intArrayOf(Lit.make(trigger, false), Lit.make(target, false)))

    @Test
    fun `Cardinality conflict analyzer learns clause containing decision`() {
        // Cardinality(literals=[a, b, c, d], min=2) with helper Clauses forcing
        // a, b, c all false when decision x=true. Cardinality then can't satisfy
        // min=2 with only d remaining — and during unit-prop it also finds c
        // already pinned false. propagate returns false; analyzer learns a clause
        // containing ¬x (the UIP).
        val problem = Problem(
            numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                forceFalseIf(trigger = 4, target = 0),
                forceFalseIf(trigger = 4, target = 1),
                forceFalseIf(trigger = 4, target = 2),
                Cardinality(
                    literals = intArrayOf(
                        Lit.make(0, true), Lit.make(1, true),
                        Lit.make(2, true), Lit.make(3, true),
                    ),
                    min = 2, max = 4,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(4, true)  // decision x=true triggers cascade
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(Lit.make(4, false) in learned.literals.toSet(),
            "learned clause should contain ¬x (the UIP), got ${learned.literals.toList()}")
    }

    @Test
    fun `Cardinality max-side conflict analyzer learns clause containing decision`() {
        // AtMost(1) of (a, b). Helper Clauses force a and b BOTH true on x=true.
        // Then cardinality propagate sees trueCount=2 > max=1 → conflict.
        val problem = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(2, false), Lit.make(0, true))),  // ¬x ∨ a
                Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),  // ¬x ∨ b
                Cardinality(
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 0, max = 1,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(Lit.make(2, false) in learned.literals.toSet(),
            "learned should contain ¬x, got ${learned.literals.toList()}")
    }

    @Test
    fun `PseudoBoolean LE conflict analyzer learns clause containing decision`() {
        // 3a + 4b ≤ 5 ; force a, b true on x=true via Clauses → sum=7 > 5, conflict.
        val problem = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(2, false), Lit.make(0, true))),  // ¬x ∨ a
                Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),  // ¬x ∨ b
                PseudoBoolean(
                    weights = intArrayOf(3, 4),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.LE, bound = 5,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(Lit.make(2, false) in learned.literals.toSet(),
            "PB LE learned should contain ¬x, got ${learned.literals.toList()}")
    }

    @Test
    fun `ReifiedPseudoBoolean direct-side conflict analyzer learns clause`() {
        // r ↔ (3a + 4b ≤ 5). Pin r=true at level 1, then decision x=true forces
        // a=true, b=true → body violates → conflict inside ReifiedPseudoBoolean.
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(3, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(1, true))),
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = intArrayOf(3, 4),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.LE, bound = 5,
                ),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(2, true))  // r=true
        val r = session.pinBool(3, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(learned.literals.isNotEmpty(),
            "ReifiedPB conflict should produce non-empty learned clause")
        assertTrue(Lit.make(3, false) in learned.literals.toSet(),
            "ReifiedPB learned should contain ¬x (UIP), got ${learned.literals.toList()}")
    }

    @Test
    fun `ReifiedCardinality direct-side conflict analyzer learns clause`() {
        // r ↔ AtLeast(2) of (a, b, c). With r=true, propagation forces body. If a, b
        // are both false, count + unassigned = 1 < 2 → body violates ⇒ propagate fails.
        val problem = Problem(
            numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                forceFalseIf(trigger = 4, target = 0),
                forceFalseIf(trigger = 4, target = 1),
                forceFalseIf(trigger = 4, target = 2),
                ReifiedCardinality(
                    auxBoolVar = 3,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 2, max = 3,
                ),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(3, true))  // r=true
        val r = session.pinBool(4, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(learned.literals.isNotEmpty(),
            "ReifiedCardinality conflict should produce non-empty learned clause")
    }

    @Test
    fun `Linear int-domain conflict produces learned clause via coarse default`() {
        // Two ReifiedLinears share aux x:
        //   x ↔ (var0 = 5) and x ↔ (var1 = 5).
        // Linear: var0 + var1 = 5 (each [0, 9], tightens to [0, 5] at bake).
        // At bake, neither ReifiedLinear's propagate fires (sumLo/sumHi span the bound),
        // so x stays unassigned. Decision x=true pins both var0=5 and var1=5 → Linear
        // sees sum=10 ≠ 5 → return false. Default conflictReason emits [¬x].
        val problem = Problem(
            numBoolVars = 1, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = listOf(
                com.eignex.klause.solver.factor.ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1), vars = intArrayOf(0),
                    op = LinearOp.EQ, bound = 5,
                ),
                com.eignex.klause.solver.factor.ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1), vars = intArrayOf(1),
                    op = LinearOp.EQ, bound = 5,
                ),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 5),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(0, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(Lit.make(0, false) in learned.literals.toSet(),
            "Linear int-domain conflict should learn [¬x], got ${learned.literals.toList()}")
    }

    @Test
    fun `AllDifferent int-domain conflict produces learned clause via coarse default`() {
        // Two ReifiedLinears share aux x, forcing var0 = var1 = 0 when x=true.
        // AllDifferent then sees a singleton-conflict at value 0.
        val problem = Problem(
            numBoolVars = 1, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(
                com.eignex.klause.solver.factor.ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1), vars = intArrayOf(0),
                    op = LinearOp.EQ, bound = 0,
                ),
                com.eignex.klause.solver.factor.ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1), vars = intArrayOf(1),
                    op = LinearOp.EQ, bound = 0,
                ),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(0, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(Lit.make(0, false) in learned.literals.toSet(),
            "AllDifferent conflict should learn [¬x], got ${learned.literals.toList()}")
    }

    @Test
    fun `GlobalCardinality int-domain conflict produces learned clause via coarse default`() {
        // 4 ints [0, 2], GCC requires value=1 exactly twice. Three ReifiedLinears share
        // aux x: forcing var0=var1=var2=1 when x=true. GCC sees definite=3 > hi=2 → fail.
        val problem = Problem(
            numBoolVars = 1, numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2),
            ),
            factors = listOf(
                com.eignex.klause.solver.factor.ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1), vars = intArrayOf(0),
                    op = LinearOp.EQ, bound = 1,
                ),
                com.eignex.klause.solver.factor.ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1), vars = intArrayOf(1),
                    op = LinearOp.EQ, bound = 1,
                ),
                com.eignex.klause.solver.factor.ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1), vars = intArrayOf(2),
                    op = LinearOp.EQ, bound = 1,
                ),
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2, 3),
                    cover = intArrayOf(1),
                    countLow = intArrayOf(2),
                    countHigh = intArrayOf(2),
                    closed = false,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(0, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(Lit.make(0, false) in learned.literals.toSet(),
            "GCC conflict should learn [¬x], got ${learned.literals.toList()}")
    }

    @Test
    fun `LCG plumbing records int antecedents through reified linear chain`() {
        // Unit test for the LCG foundation: verify that bool decisions chain into
        // int antecedents via ReifiedLinear's body propagation and aux pin.
        //
        //   A: x ↔ (v0 = 5). Decision x=true at level 1.
        //   - ReifiedLinear A.propagate at aux=true calls propagateLinearBounds with
        //     extraLit=¬x → tightenIntMin/Max(v0, 5, [¬x]).
        //   - PropagationState.intMinAntecedents[0] and .intMaxAntecedents[0] = [¬x].
        //
        //   C: z ↔ (v0 ≥ 4). Wakes when v0 tightens.
        //   - alwaysHolds (sumLo=5≥4) → pin z=true with composeAuxAntecedents reading
        //     v0's int antecedents = [¬x].
        //   - PropagationState.boolAntecedents[z] = [¬x].
        //
        // Demonstrates the int-trail chain back to the bool decision. (Whether the
        // analyzer can actually exploit this chain in `minimize()` requires extending
        // `isRedundant` to substitute via int trail — separate from this plumbing layer.)
        val problem = Problem(
            numBoolVars = 2, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.GE, 4),
            ),
        )
        val session = PropagationSession(problem)
        // Use PropagationState directly for low-level assertions.
        val r = session.pinBool(0, true)
        assertIs<PropagationResult.Implied>(r)
        // Reach into PropagationState — the session's snapshot has the post-pin facts.
        val ant = problem.factors.let {
            // Build a fresh state and replay the pin to inspect antecedents.
            val s = com.eignex.klause.solver.propagation.PropagationState(
                problem, com.eignex.klause.solver.Assumptions.None,
            )
            s.pinBoolAsDecision(0, true)
            // Run propagation to fixpoint.
            val confl = s.runToFixpoint(allFactors = false)
            assertTrue(confl == null, "unexpected conflict at level 1")
            s
        }
        // v0 should be pinned to 5 with antecedents [¬x] on both bounds.
        assertTrue(ant.intMinAntecedents[0] != null,
            "v0.min antecedents should be set by ReifiedLinear A's body propagation")
        val xLit = Lit.make(0, false)  // ¬x = the false-form of the pinned x=true.
        assertTrue(xLit in ant.intMinAntecedents[0]!!.toSet(),
            "v0.min antecedents should contain ¬x, got ${ant.intMinAntecedents[0]!!.toList()}")
        // z (bool var 1) implied true; its boolAntecedents should trace through to ¬x.
        val zAnt = ant.boolAntecedents[1]
        assertTrue(zAnt != null, "z's antecedents should be set by ReifiedLinear C's aux pin")
        assertTrue(xLit in zAnt!!.toSet(),
            "z's antecedents should contain ¬x (via composed int trail), got ${zAnt.toList()}")
    }

    @Test
    fun `LCG end-to-end decision-vs-pin conflict learns clause through int trail`() {
        // 3 bools x (var 0), y (var 1), z (var 2). 1 int v0 in [0, 9].
        //   A: x ↔ (v0 = 5).
        //   B: y ↔ (v0 = 3).
        //   C: z ↔ (v0 ≥ 4).
        //
        // x=true at level 1:
        //   - A pins v0 = 5 with int antecedents [¬x].
        //   - B sees v0=[5,5], EQ 3, neverHolds → pins y=false at level 1 with bool
        //     antecedents composed from v0's int antecedents = [¬x].
        //   - C sees v0=[5,5], GE 4, alwaysHolds → pins z=true at level 1 with antecedents [¬x].
        //
        // Now decide y=true at level 2 → decision-level conflict (y already pinned false).
        // The new `analyzeDecisionConflict` path seeds from y's prior antecedents [¬x]
        // plus the just-decided lit y, runs 1UIP and minimization.
        //
        // Without LCG plumbing: y's antecedents would be null (no factor records the
        // int trail). Analyzer would return NotApplicable, engine falls back to
        // chronological backtrack.
        //
        // With LCG: y's antecedents are [¬x] (composed from v0's int trail). The
        // minimizer resolves y against its antecedent (¬x is in the clause), drops y.
        // Learned clause = [¬x] — strictly stronger than [¬x, ¬y] which a vanilla
        // CDCL with leaf antecedents would learn.
        val problem = Problem(
            numBoolVars = 3, numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.GE, 4),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        val r = session.pinBool(1, true)  // y=true; conflicts with implied y=false.
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        val lits = learned.literals.toSet()
        assertTrue(Lit.make(0, false) in lits,
            "learned should contain ¬x, got ${learned.literals.toList()}")
        // LCG win: y resolved away by minimization (its antecedent ¬x already in clause).
        assertFalse(Lit.make(1, true) in lits,
            "LCG should resolve y away; got ${learned.literals.toList()}")
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
            numBoolVars = 2, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = listOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
                com.eignex.klause.solver.factor.Linear(
                    intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 8,
                ),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        // State: v0=5, v1=3 (forced by Linear after x=true at level 1).
        val state = com.eignex.klause.solver.propagation.PropagationState(
            problem, com.eignex.klause.solver.Assumptions.None,
        )
        state.pinBoolAsDecision(0, true)
        state.runToFixpoint(allFactors = false)
        // v0 should be [5,5] and v1 should be [3,3], both at level 1.
        assertEquals(5, state.intDomains[0].min)
        assertEquals(5, state.intDomains[0].max)
        assertEquals(3, state.intDomains[1].min)
        assertEquals(3, state.intDomains[1].max)
        // Allocate atom [v1 ≥ 3]: should hold (currently true), level 1 (when v1.min
        // was tightened by Linear), antecedents from intMinAntecedents[v1] = [¬x].
        val atomVarGE3 = state.atomVarGe(1, 3)
        val atomId = state.atomIdOf(atomVarGE3)
        assertEquals(1, state.atomValue[atomId], "atom [v1≥3] should hold (v1.min=3≥3)")
        assertEquals(1, state.atomLevel[atomId], "atom became known at level 1")
        val ant = state.atomAntecedents[atomId]
        assertTrue(ant != null, "atom should have antecedents from intMinAntecedents[v1]")
        val xLit = Lit.make(0, false)  // ¬x — the false-form when x is true.
        assertTrue(xLit in ant!!.toSet(),
            "atom antecedents should contain ¬x, got ${ant.toList()}")
        // Allocate a second atom [v1 ≥ 10] — should be false (v1.max=3 < 10).
        val atomVarGE10 = state.atomVarGe(1, 10)
        val atomId10 = state.atomIdOf(atomVarGE10)
        assertEquals(0, state.atomValue[atomId10],
            "atom [v1≥10] should not hold (v1.max=3 < 10)")
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
            numBoolVars = 0, numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = emptyList(),
        )
        val state = com.eignex.klause.solver.propagation.PropagationState(
            problem, com.eignex.klause.solver.Assumptions.None,
        )
        // Allocate atoms. Both currently undetermined (5 ∈ [0,9], 7 ∈ [0,9]).
        // Wait — atom truth derives from currentTruth(): [v0 ≥ 5] is true iff v0.min ≥ 5.
        // With dom [0,9], v0.min = 0, so atom is currently false. Similarly [v1 ≥ 7].
        val atomV0Ge5 = state.atomVarGe(0, 5)
        val atomV1Ge7 = state.atomVarGe(1, 7)
        // Add the clause as a learned clause.
        val clause = com.eignex.klause.solver.factor.Clause(intArrayOf(
            Lit.make(atomV0Ge5, true),
            Lit.make(atomV1Ge7, true),
        ))
        state.addLearnedClause(clause, lbd = 2)
        // Decide v0 ≤ 4 (which makes atom [v0 ≥ 5] false permanently, since v0.max < 5).
        // We do this by calling tightenIntMax directly with no antecedents (a decision).
        state.currentLevel = 1
        assertTrue(state.tightenIntMax(0, 4))
        // Run propagation — the clause must wake (atom-lit watcher fires) and
        // unit-propagate [v1 ≥ 7] which translates to tightenIntMin(v1, 7).
        val conflict = state.runToFixpoint(allFactors = false)
        assertTrue(conflict == null, "no conflict expected; clause should unit-propagate")
        assertEquals(7, state.intDomains[1].min,
            "atom-lit clause should have unit-propagated [v1 ≥ 7] → tightenIntMin(v1, 7)")
    }

    @Test
    fun `Xor conflict analyzer learns clause containing decision`() {
        // a ⊕ b = 1. With Clauses forcing a, b both true on x=true → parity 0, conflict.
        val problem = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(2, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),
                Xor(literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)), targetParity = 1),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(Lit.make(2, false) in learned.literals.toSet(),
            "Xor learned should contain ¬x, got ${learned.literals.toList()}")
    }
}
