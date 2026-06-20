package com.eignex.klause.solver.propagation

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.solver.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import com.eignex.klause.solver.factor.bool.Xor
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.global.GlobalCardinality
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
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                forceFalseIf(trigger = 4, target = 0),
                forceFalseIf(trigger = 4, target = 1),
                forceFalseIf(trigger = 4, target = 2),
                Cardinality(
                    literals = intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                        Lit.make(3, true),
                    ),
                    min = 2,
                    max = 4,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(4, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(
            Lit.make(4, false) in learned.literals.toSet(),
            "learned clause should contain ¬x (the UIP), got ${learned.literals.toList()}",
        )
    }

    @Test
    fun `Cardinality max-side conflict analyzer learns clause containing decision`() {
        // AtMost(1) of (a, b). Helper Clauses force a and b BOTH true on x=true.
        // Then cardinality propagate sees trueCount=2 > max=1 → conflict.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(2, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),
                Cardinality(
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 0,
                    max = 1,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(
            Lit.make(2, false) in learned.literals.toSet(),
            "learned should contain ¬x, got ${learned.literals.toList()}",
        )
    }

    @Test
    fun `PseudoBoolean LE conflict analyzer learns clause containing decision`() {
        // 3a + 4b ≤ 5 ; force a, b true on x=true via Clauses → sum=7 > 5, conflict.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(2, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),
                PseudoBoolean(
                    weights = intArrayOf(3, 4),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.LE,
                    bound = 5,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(
            Lit.make(2, false) in learned.literals.toSet(),
            "PB LE learned should contain ¬x, got ${learned.literals.toList()}",
        )
    }

    @Test
    fun `ReifiedPseudoBoolean direct-side conflict analyzer learns clause`() {
        // r ↔ (3a + 4b ≤ 5). Pin r=true at level 1, then decision x=true forces
        // a=true, b=true → body violates → conflict inside ReifiedPseudoBoolean.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(3, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(1, true))),
                ReifiedPseudoBoolean(
                    auxBoolVar = 2,
                    weights = intArrayOf(3, 4),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    op = PbOp.LE,
                    bound = 5,
                ),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(2, true))
        val r = session.pinBool(3, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(
            learned.literals.isNotEmpty(),
            "ReifiedPB conflict should produce non-empty learned clause",
        )
        assertTrue(
            Lit.make(3, false) in learned.literals.toSet(),
            "ReifiedPB learned should contain ¬x (UIP), got ${learned.literals.toList()}",
        )
    }

    @Test
    fun `ReifiedCardinality direct-side conflict analyzer learns clause`() {
        // r ↔ AtLeast(2) of (a, b, c). With r=true, propagation forces body. If a, b
        // are both false, count + unassigned = 1 < 2 → body violates ⇒ propagate fails.
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                forceFalseIf(trigger = 4, target = 0),
                forceFalseIf(trigger = 4, target = 1),
                forceFalseIf(trigger = 4, target = 2),
                ReifiedCardinality(
                    auxBoolVar = 3,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 2,
                    max = 3,
                ),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(3, true))
        val r = session.pinBool(4, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(
            learned.literals.isNotEmpty(),
            "ReifiedCardinality conflict should produce non-empty learned clause",
        )
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
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = 5,
                ),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(1),
                    op = LinearOp.EQ,
                    bound = 5,
                ),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 5),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(0, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(
            Lit.make(0, false) in learned.literals.toSet(),
            "Linear int-domain conflict should learn [¬x], got ${learned.literals.toList()}",
        )
    }

    @Test
    fun `AllDifferent int-domain conflict produces learned clause via coarse default`() {
        // Two ReifiedLinears share aux x, forcing var0 = var1 = 0 when x=true.
        // AllDifferent then sees a singleton-conflict at value 0.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = 0,
                ),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(1),
                    op = LinearOp.EQ,
                    bound = 0,
                ),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(0, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(
            Lit.make(0, false) in learned.literals.toSet(),
            "AllDifferent conflict should learn [¬x], got ${learned.literals.toList()}",
        )
    }

    @Test
    fun `GlobalCardinality int-domain conflict produces learned clause via coarse default`() {
        // 4 ints [0, 2], GCC requires value=1 exactly twice. Three ReifiedLinears share
        // aux x: forcing var0=var1=var2=1 when x=true. GCC sees definite=3 > hi=2 → fail.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
            factors = arrayOf<Factor>(
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(0),
                    op = LinearOp.EQ,
                    bound = 1,
                ),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(1),
                    op = LinearOp.EQ,
                    bound = 1,
                ),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(2),
                    op = LinearOp.EQ,
                    bound = 1,
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
        assertTrue(
            Lit.make(0, false) in learned.literals.toSet(),
            "GCC conflict should learn [¬x], got ${learned.literals.toList()}",
        )
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
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.GE, 4),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(0, true)
        assertIs<PropagationResult.Implied>(r)
        val ant = problem.factors.let {
            val s = PropagationState(
                problem,
                Assumptions.None,
            )
            s.pinBoolAsDecision(0, true)
            val confl = s.runToFixpoint(allFactors = false)
            assertTrue(confl == null, "unexpected conflict at level 1")
            s
        }
        assertTrue(
            ant.intMinAntecedents[0] != null,
            "v0.min antecedents should be set by ReifiedLinear A's body propagation",
        )
        val xLit = Lit.make(0, false)
        assertTrue(
            xLit in ant.intMinAntecedents[0]!!.toSet(),
            "v0.min antecedents should contain ¬x, got ${ant.intMinAntecedents[0]!!.toList()}",
        )
        // z (bool var 1) implied true; its boolAntecedents now contain the *atom-lit*
        // form ¬[v0≥5] and ¬[v0≤5] — the per-bound premise atoms — rather than the
        // coarser ¬x union. Resolution through these atoms still traces back to ¬x via
        // their own antecedents = intMin/MaxAntecedents[v0] = [¬x].
        val zAnt = ant.boolAntecedents[1]
        assertTrue(zAnt != null, "z's antecedents should be set by ReifiedLinear C's aux pin")
        val ge5 = Lit.make(ant.atomVarGe(0, 5), false)
        val le5 = Lit.make(ant.atomVarLe(0, 5), false)
        val zAntSet = requireNotNull(zAnt).toSet()
        assertTrue(
            ge5 in zAntSet && le5 in zAntSet,
            "z's antecedents should contain ¬[v0≥5] and ¬[v0≤5], got ${zAnt.toList()}",
        )
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
            numBoolVars = 3,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.GE, 4),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        val r = session.pinBool(1, true) // y=true; conflicts with implied y=false.
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        val lits = learned.literals.toSet()
        // With atom-lit antecedents, the learned clause is the per-bound nogood
        // ¬[v0≥5] ∨ ¬[v0≤5] (i.e. v0 ≠ 5) — strictly stronger than [¬x] over the int
        // semantics: it rules out every assignment that forces v0=5 from any source, not
        // just x=true. Minimization still resolves y away (y's atom-lit antecedents are
        // already in the clause).
        assertFalse(
            Lit.make(1, true) in lits,
            "LCG should resolve y away; got ${learned.literals.toList()}",
        )
        // We can't easily look up the atom-var ids without a state handle, but we can
        // assert the shape: every literal points at an atom var (id ≥ numBoolVars=3).
        for (l in learned.literals) {
            assertTrue(
                Lit.variable(l) >= 3,
                "expected only atom-lit literals (var ≥ 3), got var ${Lit.variable(l)} in ${learned.literals.toList()}",
            )
        }
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
    fun `Xor conflict analyzer learns clause containing decision`() {
        // a ⊕ b = 1. With Clauses forcing a, b both true on x=true → parity 0, conflict.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(2, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(1, true))),
                Xor(literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)), targetParity = 1),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, true)
        val unsat = assertIs<PropagationResult.Unsat>(r)
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.Learned>(unsat.learnedClause)
        assertTrue(
            Lit.make(2, false) in learned.literals.toSet(),
            "Xor learned should contain ¬x, got ${learned.literals.toList()}",
        )
    }
}
