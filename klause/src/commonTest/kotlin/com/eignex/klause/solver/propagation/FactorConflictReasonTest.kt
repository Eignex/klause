package com.eignex.klause.solver.propagation

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
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
