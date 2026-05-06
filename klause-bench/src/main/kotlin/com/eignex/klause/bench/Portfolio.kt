package com.eignex.klause.bench

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.IntEq
import com.eignex.klause.solver.factor.IntGeq
import com.eignex.klause.solver.factor.IntLeq
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Xor

/**
 * Hardcoded SAT/UNSAT problems the harness runs by default. Stays small enough that every
 * backend (LS, LogicNG, Z3) decides each entry quickly. Replace iteration over [all] with
 * a wire-format loader once that lands.
 */
object Portfolio {
    data class Entry(val name: String, val problem: Problem, val expectedSat: Boolean)

    val sat: List<Entry> = listOf(
        Entry("threeClauses",
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            )),
            expectedSat = true,
        ),
        Entry("cardXor",
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                    min = 2, max = 3),
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), targetParity = 1),
            )),
            expectedSat = true,
        ),
        Entry("pseudoBoolean",
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                PseudoBoolean(
                    weights = intArrayOf(2, 3, 1, 1),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                    op = PbOp.LE, bound = 3,
                ),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            )),
            expectedSat = true,
        ),
        Entry("linearLE",
            Problem(numBoolVars = 0, numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = listOf(
                    Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = 4),
                    IntGeq(intVar = 0, bound = 1),
                    IntLeq(intVar = 1, bound = 2),
                )),
            expectedSat = true,
        ),
        Entry("permutation3",
            Problem(numBoolVars = 0, numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                factors = listOf(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3))),
            expectedSat = true,
        ),
        Entry("mixedBoolInt",
            Problem(numBoolVars = 2, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)),
                factors = listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    IntLeq(intVar = 0, bound = 2),
                )),
            expectedSat = true,
        ),
        // Approximation of combo TestModels.MODEL6 ("All Cardinality Options except NE"):
        // overlapping cardinality bounds plus an excludes pair, scaled to 8 bool vars.
        Entry("cardinalityStress",
            Problem(numBoolVars = 8, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                // atMost(3) over the first 6 flags
                Cardinality(literals = (0..5).map { Lit.make(it, true) }.toIntArray(),
                    min = 0, max = 3),
                // atLeast(3) over all 8
                Cardinality(literals = (0..7).map { Lit.make(it, true) }.toIntArray(),
                    min = 3, max = 8),
                // exactly(2) on first three
                Cardinality(literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 2, max = 2),
                // excludes(flag2, flag3) — at-most-one of these two
                Cardinality(literals = intArrayOf(Lit.make(2, true), Lit.make(3, true)),
                    min = 0, max = 1),
                // cardinality(2, GT) over all 8 — strictly more than 2 true
                Cardinality(literals = (0..7).map { Lit.make(it, true) }.toIntArray(),
                    min = 3, max = 8),
                // cardinality(6, LT) over all 8 — strictly less than 6 true
                Cardinality(literals = (0..7).map { Lit.make(it, true) }.toIntArray(),
                    min = 0, max = 5),
            )),
            expectedSat = true,
        ),
        // Approximation of combo TestModels.CSP1 ("All kinds of PB constraints"): reified
        // implications, a cardinality bound, and a weighted PB inequality interacting on the
        // same 4 bool vars. Stresses the reified-aux coordination across factor types.
        Entry("pbReifiedMix",
            // 4 base flags (0..3) + 2 aux bools (4, 5) for the reified conjunction/disjunction.
            Problem(numBoolVars = 6, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                // aux4 ↔ (#true(f2,f3) ≥ 2) — i.e. f2 ∧ f3
                ReifiedCardinality(auxBoolVar = 4,
                    literals = intArrayOf(Lit.make(1, true), Lit.make(2, true)),
                    min = 2, max = 2),
                // f1 → aux4 (i.e. f1 reifiedImplies (f2 ∧ f3))
                Clause(intArrayOf(Lit.make(0, false), Lit.make(4, true))),
                // aux5 ↔ (#true(f1,f2,f3) ≥ 1) — disjunction
                ReifiedCardinality(auxBoolVar = 5,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 1, max = 3),
                // f4 → aux5
                Clause(intArrayOf(Lit.make(3, false), Lit.make(5, true))),
                // cardinality(3, LT) over (¬f1, f2, f3, ¬f4)
                Cardinality(literals = intArrayOf(
                    Lit.make(0, false), Lit.make(1, true), Lit.make(2, true), Lit.make(3, false),
                ), min = 0, max = 2),
                // weighted PB: 1*f1 + 2*f2 + 3*f3 + 4*f4 > 2 → ≥ 3
                PseudoBoolean(
                    weights = intArrayOf(1, 2, 3, 4),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                    op = PbOp.GE, bound = 3,
                ),
            )),
            expectedSat = true,
        ),
        // Approximation of combo TestModels.LARGE4 ("Random Disjunctions") shrunk to 12 vars
        // and ~30 hand-crafted 3-SAT clauses. Hits clause-density ratios where local search
        // is still trivially fast but enough structure to exercise watched literals + tabu.
        Entry("smallRandom3sat",
            Problem(numBoolVars = 12, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(3, false), Lit.make(7, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(4, true), Lit.make(8, false))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(5, false), Lit.make(9, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(6, true), Lit.make(10, false))),
                Clause(intArrayOf(Lit.make(3, true), Lit.make(7, false), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(5, true), Lit.make(9, false))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(4, false), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(8, true), Lit.make(10, true))),
                Clause(intArrayOf(Lit.make(6, false), Lit.make(7, true), Lit.make(8, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(4, true), Lit.make(5, true), Lit.make(6, true))),
                Clause(intArrayOf(Lit.make(7, false), Lit.make(9, false), Lit.make(11, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(5, true))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(8, true), Lit.make(10, false))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(6, false), Lit.make(11, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(4, false), Lit.make(9, true))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(7, true), Lit.make(10, false))),
                Clause(intArrayOf(Lit.make(3, true), Lit.make(5, false), Lit.make(8, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(6, true), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(4, true), Lit.make(7, false), Lit.make(9, false))),
                Clause(intArrayOf(Lit.make(5, true), Lit.make(8, true), Lit.make(10, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(3, true), Lit.make(6, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(5, false), Lit.make(7, false))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(8, true), Lit.make(11, false))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(6, true), Lit.make(9, true))),
                Clause(intArrayOf(Lit.make(4, false), Lit.make(10, true), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(8, false))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false), Lit.make(4, true))),
                Clause(intArrayOf(Lit.make(5, true), Lit.make(6, true), Lit.make(7, true))),
            )),
            expectedSat = true,
        ),
        // Realistic small "budget" problem: a nominal type drives a unit cost; choice must
        // stay under a budget. 3 nominal one-hot bools + 1 int cost. Each type pegs the cost
        // via a reified equality; exactlyOne on the indicators; cost ≤ budget caps the
        // feasible types.
        Entry("budgetCampaign",
            Problem(numBoolVars = 3, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 100)),
                factors = listOf(
                    Cardinality.exactlyOne(intArrayOf(
                        Lit.make(0, true), Lit.make(1, true), Lit.make(2, true),
                    )),
                    // type=a (bool 0) ↔ cost = 30
                    ReifiedLinear(auxBoolVar = 0,
                        coeffs = intArrayOf(1), vars = intArrayOf(0),
                        op = LinearOp.EQ, bound = 30),
                    // type=b (bool 1) ↔ cost = 50
                    ReifiedLinear(auxBoolVar = 1,
                        coeffs = intArrayOf(1), vars = intArrayOf(0),
                        op = LinearOp.EQ, bound = 50),
                    // type=c (bool 2) ↔ cost = 80
                    ReifiedLinear(auxBoolVar = 2,
                        coeffs = intArrayOf(1), vars = intArrayOf(0),
                        op = LinearOp.EQ, bound = 80),
                    // budget cap: only types a, b survive.
                    IntLeq(intVar = 0, bound = 60),
                )),
            expectedSat = true,
        ),
    )

    val unsat: List<Entry> = listOf(
        Entry("clauseContradiction",
            Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            )),
            expectedSat = false,
        ),
        Entry("intEqContradiction",
            Problem(numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)),
                factors = listOf(
                    IntEq(intVar = 0, value = 1),
                    IntEq(intVar = 0, value = 3),
                )),
            expectedSat = false,
        ),
        Entry("pigeonhole",
            Problem(numBoolVars = 0, numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                factors = listOf(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2))),
            expectedSat = false,
        ),
    )

    val all: List<Entry> = sat + unsat
}
