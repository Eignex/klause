package com.eignex.klause.cnf

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Xor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the bit-blast → CDCL → decode path. A decoded model is validated by
 * pinning every original variable's bits into the (simplified) CNF and asserting the residual
 * is SAT: because bit-blasting is faithful, a fully-pinned-feasible CNF witnesses that the
 * decoded assignment satisfies the original problem.
 */
class BitblastSolverTest {

    private data class Case(val name: String, val problem: Problem)

    @Test
    fun `sat problems are solved and decoded models are feasible`() {
        for (case in satPortfolio()) {
            for (simplify in listOf(true, false)) {
                val solver = BitblastSolver(case.problem, simplify = simplify)
                val result = solver.solve(BacktrackParams())
                assertTrue(
                    result is SolveResult.Sat,
                    "${case.name} (simplify=$simplify): expected Sat, got $result",
                )
                // Validate against the RAW encoding: BVE leaves eliminated aux vars free in
                // solver.cnf, but the raw Tseitin clauses fully constrain them, so pinning the
                // decoded original vars unit-propagates the rest. Ground truth, not self-check.
                val raw = BitBlaster.compile(case.problem)
                val pins = pinSampleIntoCnf(raw, case.problem, result.assignment)
                assertTrue(
                    SatCheck.isSat(raw.numVars, raw.clauses, pins),
                    "${case.name} (simplify=$simplify): decoded model is infeasible under the CNF",
                )
            }
        }
    }

    @Test
    fun `unsat problems are proven infeasible`() {
        for (case in unsatPortfolio()) {
            for (simplify in listOf(true, false)) {
                val result = BitblastSolver(case.problem, simplify = simplify).solve(BacktrackParams())
                assertTrue(
                    result is SolveResult.Unsat,
                    "${case.name} (simplify=$simplify): expected Unsat, got $result",
                )
            }
        }
    }

    @Test
    fun `simplification does not change the verdict`() {
        for (case in satPortfolio() + unsatPortfolio()) {
            val withSimp = BitblastSolver(case.problem, simplify = true).solve(BacktrackParams())
            val without = BitblastSolver(case.problem, simplify = false).solve(BacktrackParams())
            assertTrue(
                (withSimp is SolveResult.Sat) == (without is SolveResult.Sat) &&
                    (withSimp is SolveResult.Unsat) == (without is SolveResult.Unsat),
                "${case.name}: simplify flipped the verdict ($without vs $withSimp)",
            )
        }
    }

    private fun satPortfolio(): List<Case> = listOf(
        Case(
            "threeClauses",
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
                ),
            ),
        ),
        Case(
            "cardXor",
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Cardinality(
                        intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                        min = 2,
                        max = 3,
                    ),
                    Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), targetParity = 1),
                ),
            ),
        ),
        Case(
            "pseudoBoolean",
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    PseudoBoolean(
                        weights = intArrayOf(2, 3, 1, 1),
                        literals = intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, true),
                            Lit.make(2, true),
                            Lit.make(3, true),
                        ),
                        op = PbOp.LE,
                        bound = 3,
                    ),
                    Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                ),
            ),
        ),
        Case(
            "linearLE",
            Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, 4),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 2),
                ),
            ),
        ),
        Case(
            "permutation3",
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
            ),
        ),
        Case(
            "mixedBoolInt",
            Problem(
                numBoolVars = 2,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
                ),
            ),
        ),
    )

    private fun unsatPortfolio(): List<Case> = listOf(
        Case(
            "clauseContradiction",
            Problem(
                numBoolVars = 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true))),
                    Clause(intArrayOf(Lit.make(0, false))),
                ),
            ),
        ),
        Case(
            "intEqContradiction",
            Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ),
            ),
        ),
        Case(
            "pigeonhole",
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)),
            ),
        ),
    )

    private fun pinSampleIntoCnf(cnf: CnfProblem, problem: Problem, sample: Sample): IntArray {
        val out = ArrayList<Int>(2 * (problem.numBoolVars + problem.numIntVars * 8))
        for (b in 0 until problem.numBoolVars) {
            out += cnf.boolVarToCnfVar[b]
            out += if (sample.bools[b]) 1 else 0
        }
        for (i in 0 until problem.numIntVars) {
            val bits = cnf.intVarBits[i]
            val offset = sample.ints[i] - cnf.intVarMin[i]
            for (k in bits.indices) {
                out += bits[k]
                out += (offset ushr k) and 1
            }
        }
        return out.toIntArray()
    }
}
