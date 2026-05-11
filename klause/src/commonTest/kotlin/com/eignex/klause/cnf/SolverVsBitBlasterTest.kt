package com.eignex.klause.cnf

import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.LocalSearchSolver
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
import kotlin.test.Test
import kotlin.test.assertTrue

/** Cross-check: every solver-found sample must be SAT under the bit-blasted CNF, and known-
 *  UNSAT problems must yield no samples and an UNSAT CNF. */
class SolverVsBitBlasterTest {

    private data class SatCase(val name: String, val problem: Problem)

    @Test
    fun `solver satisfying assignments are sat under bit blast`() {
        for (case in satPortfolio()) {
            val cnf = BitBlaster.compile(case.problem)
            val solver = LocalSearchSolver(case.problem)
            val samples = solver.samples(LocalSearchParams(maxFlips = 200_000L, randomSeed = 0L,
                minHammingDistance = 0, recentWindow = 0)).take(1).toList()
            assertTrue(samples.isNotEmpty(), "${case.name}: solver found no sample within budget")
            val sample = samples.first()
            val pins = pinSampleIntoCnf(cnf, case.problem, sample)
            assertTrue(SatCheck.isSat(cnf.numVars, cnf.clauses, pins),
                "${case.name}: solver sample is UNSAT under bit-blasted CNF")
        }
    }

    @Test
    fun `unsat problems yield no samples and cnf is unsat`() {
        for (case in unsatPortfolio()) {
            val cnf = BitBlaster.compile(case.problem)
            assertTrue(!SatCheck.isSat(cnf.numVars, cnf.clauses, IntArray(0)),
                "${case.name}: expected UNSAT under bit-blast but oracle says SAT")
            val solver = LocalSearchSolver(case.problem)
            // Tight budget: enough that a SAT problem would converge, small enough that an
            // UNSAT one terminates fast.
            val samples = solver.samples(LocalSearchParams(maxFlips = 5_000L, randomSeed = 0L,
                minHammingDistance = 0, recentWindow = 0)).take(1).toList()
            assertTrue(samples.isEmpty(), "${case.name}: UNSAT problem yielded a sample")
        }
    }

    // ---------------------- Portfolio ----------------------

    private fun satPortfolio(): List<SatCase> = listOf(
        // Pure-bool 3-SAT-like: 4 vars, three clauses.
        SatCase("threeClauses",
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            )),
        ),
        // Pure-bool with cardinality + xor (mixed structural factors).
        SatCase("cardXor",
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)), min = 2, max = 3),
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), targetParity = 1),
            )),
        ),
        // Pseudo-boolean weighted constraint.
        SatCase("pseudoBoolean",
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                PseudoBoolean(
                    weights = intArrayOf(2, 3, 1, 1),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                    op = PbOp.LE, bound = 3,
                ),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            )),
        ),
        // Pure int Linear sum constraint.
        SatCase("linearLE",
            Problem(numBoolVars = 0, numIntVars = 2, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)), factors = listOf(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = 4),
                IntGeq(intVar = 0, bound = 1),
                IntLeq(intVar = 1, bound = 2),
            )),
        ),
        // Permutation via AllDifferent over 3 ints.
        SatCase("permutation3",
            Problem(numBoolVars = 0, numIntVars = 3, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)), factors = listOf(
                AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3),
            )),
        ),
        // Mixed bool + int with a clause forcing a value.
        SatCase("mixedBoolInt",
            Problem(numBoolVars = 2, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)), factors = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                IntLeq(intVar = 0, bound = 2),
            )),
        ),
    )

    private fun unsatPortfolio(): List<SatCase> = listOf(
        // Direct contradiction on a clause and its negation.
        SatCase("clauseContradiction",
            Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            )),
        ),
        // x = 1 AND x = 3 simultaneously.
        SatCase("intEqContradiction",
            Problem(numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)), factors = listOf(
                IntEq(intVar = 0, value = 1),
                IntEq(intVar = 0, value = 3),
            )),
        ),
        // Pigeonhole-3-into-2: AllDifferent over 3 ints with domain size 2.
        SatCase("pigeonhole",
            Problem(numBoolVars = 0, numIntVars = 3, intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)), factors = listOf(
                AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2),
            )),
        ),
    )

    // ---------------------- Pin helpers ----------------------

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
