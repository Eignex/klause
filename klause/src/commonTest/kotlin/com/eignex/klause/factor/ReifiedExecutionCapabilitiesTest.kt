package com.eignex.klause.factor

import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Sample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReifiedExecutionCapabilitiesTest {
    @Test
    fun `reified filtering retains every independently satisfying assignment`() {
        for ((family, variants) in cases()) {
            for ((index, variant) in variants.withIndex()) {
                val (problem, degree) = variant
                FactorPropagationOracle.assertSound(problem, "$family/$index") { degree(it) == 0 }
            }
        }
    }

    @Test
    fun `complete reified CP checks agree with both truth directions`() {
        for ((family, variants) in cases()) {
            for ((index, variant) in variants.withIndex()) {
                val (problem, degree) = variant
                FactorPropagationOracle.assertCompleteChecks(problem, "$family/$index") { degree(it) == 0 }
            }
        }
    }

    @Test
    fun `reified invariant checks and scores agree with independent source degrees`() {
        for ((family, variants) in cases()) {
            for ((index, variant) in variants.withIndex()) {
                val (problem, degree) = variant
                FactorPropagationOracle.assertScoring(problem, "$family/$index", degree)
            }
        }
    }

    @Test
    fun `reified cardinality move scores agree with independent source degrees`() {
        for ((index, variant) in cardinalityCases().withIndex()) {
            FactorPropagationOracle.assertMoveScoring(variant.first, "cardinality/$index", variant.second)
        }
    }

    @Test
    fun `reified pseudo Boolean move scores agree with independent source degrees`() {
        for ((index, variant) in pseudoBooleanCases().withIndex()) {
            FactorPropagationOracle.assertMoveScoring(variant.first, "pseudoBoolean/$index", variant.second)
        }
    }

    @Test
    fun `reified linear move scores agree with independent source degrees`() {
        for ((index, variant) in linearCases().withIndex()) {
            FactorPropagationOracle.assertMoveScoring(variant.first, "linear/$index", variant.second)
        }
    }

    @Test
    fun `reified LP rows retain every independently satisfying assignment`() {
        for ((family, variants) in cases()) {
            for ((index, variant) in variants.withIndex()) {
                val (problem, degree) = variant
                val relaxation = CpToLpRelaxation(problem, null).build(RootDomains(problem))
                val model = relaxation.model
                assertTrue(model.m > 0, "$family/$index: reification rows must be exercised")
                assertTrue(relaxation.colVarId.all { it >= 0 }, "$family/$index: source-backed columns")
                val witnesses = FactorPropagationOracle.sourceAssignments(problem).filter { degree(it) == 0 }.toList()
                val aux = if (problem.numBoolVars == 1) 0 else 2
                assertTrue(witnesses.any { it.bools[aux] }, "$family/$index: true witnesses")
                assertTrue(witnesses.any { !it.bools[aux] }, "$family/$index: false witnesses")
                for (sample in witnesses) {
                    val point = LongArray(model.n) { col ->
                        val v = relaxation.colVarId[col]
                        val value = if (relaxation.colIsBool[col]) {
                            if (sample.bools[v]) 1L else 0L
                        } else {
                            sample.ints[v]
                        }
                        value - model.loShift[col]
                    }
                    val sums = LongArray(model.m)
                    for (col in point.indices) {
                        assertTrue(point[col] >= 0, "$family/$index: column lower bound")
                        if (model.hasUpper[col]) assertTrue(point[col] <= model.upper[col])
                        model.forEachInColumn(col) { row, coefficient -> sums[row] += coefficient * point[col] }
                    }
                    for (row in sums.indices) {
                        val slack = model.rhs[row] - sums[row]
                        assertTrue(slack >= 0, "$family/$index: row $row lower slack")
                        if (model.hasUpper[model.n + row]) {
                            assertTrue(slack <= model.upper[model.n + row], "$family/$index: row $row upper slack")
                        }
                    }
                }
            }
        }
    }

    private fun cases(): Map<String, List<Pair<Problem, (Sample) -> Int>>> = linkedMapOf(
        "cardinality" to cardinalityCases(),
        "pseudoBoolean" to pseudoBooleanCases(),
        "linear" to linearCases(),
    )

    private fun cardinalityCases(): List<Pair<Problem, (Sample) -> Int>> =
        listOf(0 to 1, 1 to 2, 2 to 2).map { (lo, hi) ->
            val factor = ReifiedCardinality(2, signedLiterals(), lo, hi)
            Problem(3, 0, emptyArray(), arrayOf<Factor>(factor)) to { s: Sample ->
                val count = (if (s.bools[0]) 2 else 0) + if (!s.bools[1]) 1 else 0
                val residual = maxOf(0, lo - count, count - hi)
                if (s.bools[2]) residual else if (residual == 0) 1 else 0
            }
        }

    private fun pseudoBooleanCases(): List<Pair<Problem, (Sample) -> Int>> = PbOp.entries.map { op ->
        val factor = ReifiedPseudoBoolean(2, longArrayOf(2, 3, 1), signedLiterals(), op, 3)
        Problem(3, 0, emptyArray(), arrayOf<Factor>(factor)) to { s: Sample ->
            val sum = (if (s.bools[0]) 3 else 0) + if (!s.bools[1]) 3 else 0
            val residual = when (op) {
                PbOp.LE -> maxOf(0, sum - 3)
                PbOp.GE -> maxOf(0, 3 - sum)
                PbOp.EQ -> abs(sum - 3)
            }
            if (s.bools[2]) residual else if (residual == 0) 1 else 0
        }
    }

    private fun linearCases(): List<Pair<Problem, (Sample) -> Int>> = LinearOp.entries.map { op ->
        val factor = ReifiedLinear(0, intArrayOf(2, -1, 1), intArrayOf(0, 1, 0), op, 1)
        Problem(1, 2, Array(2) { IntDomain(-1, 1) }, arrayOf<Factor>(factor)) to { s: Sample ->
            val sum = 3 * s.ints[0].toInt() - s.ints[1].toInt()
            val residual = when (op) {
                LinearOp.LE -> maxOf(0, sum - 1)
                LinearOp.GE -> maxOf(0, 1 - sum)
                LinearOp.EQ -> abs(sum - 1)
                LinearOp.NE -> if (sum == 1) 1 else 0
            }
            if (s.bools[0]) residual else if (residual == 0) 1 else 0
        }
    }

    private fun signedLiterals(): IntArray = intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(0, true))
}
