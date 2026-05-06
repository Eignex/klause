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
