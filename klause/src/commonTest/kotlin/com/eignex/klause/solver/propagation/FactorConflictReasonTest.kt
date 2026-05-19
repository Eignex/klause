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
import com.eignex.klause.solver.factor.Xor
import kotlin.test.Test
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
