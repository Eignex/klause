package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CoefficientStrengtheningTest {
    @Test
    fun `a declared Boolean row should be strengthened without changing its solutions`() {
        val source = PseudoBoolean(longArrayOf(2, 4), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 5)
        val model = Problem(2, 0, emptyArray(), listOf(object : Factor by source {}))

        val delta = CoefficientStrengthening.strengthenCoefficients(model)

        assertEquals(listOf(0), delta.droppedIndices.toList())
        val rewritten = assertIs<PseudoBoolean>(delta.addedFactors.single())
        for (mask in 0..3) {
            val assignment = BooleanArray(2) { (mask and (1 shl it)) != 0 }
            assertEquals(evalPb(source, assignment), evalPb(rewritten, assignment))
        }
    }

    @Test
    fun `declared integer rows should be strengthened with finite and open source bounds`() {
        val source = Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.LE, 5)
        val factor = object : Factor by source {}
        for (open in listOf(false, true)) {
            val flags = if (open) Bits(2).also { bits -> repeat(2, bits::set) } else null
            val model = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(5, 5), flags, flags),
                factors = arrayOf(factor),
            )

            val delta = CoefficientStrengthening.strengthenCoefficients(model)

            assertEquals(listOf(0), delta.droppedIndices.toList())
            val reduced = assertIs<Linear>(delta.addedFactors.single())
            assertEquals(listOf(1L, 2L), checkNotNull(reduced.integerConstants).coeffs.toList())
            assertEquals(2L, reduced.integerConstants?.bound)
        }
    }

    @Test
    fun `strengthening an implied row should not replace its enclosing factor`() {
        val source = Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.LE, 5)
        for (form in listOf(LinearForm.Relaxation(source.linearRows), LinearForm.Disjunction(source.linearRows))) {
            val factor = object : Factor by source {
                override val linearForm: LinearForm = form
            }
            val model = Problem(0, 2, Array(2) { IntDomain(0, 5) }, listOf(factor))

            val delta = CoefficientStrengthening.strengthenCoefficients(model)

            assertTrue(delta.isEmpty)
        }
    }

    @Test
    fun `an indivisible implied equality should refute its enclosing factor`() {
        val source = Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3)
        val factor = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Relaxation(source.linearRows)
        }
        val model = Problem(0, 2, Array(2) { IntDomain(0, 5) }, listOf(factor))

        val delta = CoefficientStrengthening.strengthenCoefficients(model)

        assertTrue(delta.infeasible)
    }

    private fun strengthened(problem: BakedProblem): Problem =
        problem.withPassDelta(Presolve.strengthenCoefficients(problem), BakeConfig.NONE)

    private fun evalLinear(f: Linear, assign: IntArray): Boolean {
        val row = checkNotNull(f.integerConstants)
        var sum = BIG_ZERO
        for (i in f.vars.indices) sum += bigIntOf(row.coeff(i)) * bigIntOf(assign[f.vars[i]])
        val bound = bigIntOf(row.bound)
        return when (f.op) {
            LinearOp.LE -> sum <= bound
            LinearOp.GE -> sum >= bound
            LinearOp.EQ -> sum == bound
            LinearOp.NE -> sum != bound
        }
    }

    private fun evalPb(f: PseudoBoolean, bools: BooleanArray): Boolean {
        var sum = 0L
        for (i in f.literals.indices) {
            if (Lit.evaluate(f.literals[i], bools[Lit.variable(f.literals[i])])) sum += f.weights[i]
        }
        return when (f.op) {
            PbOp.LE -> sum <= f.bound
            PbOp.GE -> sum >= f.bound
            PbOp.EQ -> sum == f.bound
        }
    }

    private fun assertLinearEquivalent(numVars: Int, domain: Int, original: Linear) =
        assertLinearEquivalent(Array(numVars) { 0..domain }, original)

    private fun assertLinearEquivalent(domains: Array<IntRange>, original: Linear) {
        val numVars = domains.size
        val problem =
            Problem(
                0,
                numVars,
                Array(numVars) { IntDomain(domains[it].first.toLong(), domains[it].last.toLong()) },
                listOf(original),
            )
        val delta = Presolve.strengthenCoefficients(problem, Cancellation.Never, problem.finiteIntDomains())
        val rewritten =
            problem.factors.filterIndexed { index, _ -> index !in delta.droppedIndices } + delta.addedFactors
        val values = Array(numVars) { v ->
            domains[v].toList().toIntArray()
        }
        val assign = IntArray(numVars)
        enumerateMixed(values) { idx ->
            for (v in 0 until numVars) assign[v] = values[v][idx[v]]
            val origSat = evalLinear(original, assign)
            val newSat = !delta.infeasible && rewritten.all { evalLinear(assertIs<Linear>(it), assign) }
            assertEquals(origSat, newSat, "linear disagrees at ${assign.toList()}: $original -> $rewritten")
        }
    }

    private fun enumerateMixed(values: Array<IntArray>, body: (IntArray) -> Unit) {
        val idx = IntArray(values.size)
        while (true) {
            body(idx)
            var i = 0
            while (i < values.size) {
                if (idx[i] < values[i].size - 1) {
                    idx[i]++
                    break
                }
                idx[i] = 0
                i++
            }
            if (i == values.size) return
        }
    }

    @Test
    fun `gcd reduction should preserve each linear operator`() {
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.LE, 5))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.GE, 5))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 6))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 5))
        assertLinearEquivalent(2, 4, Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 6))
    }

    private fun assertInfeasibleAfterStrengthen(problem: Problem) {
        val out = strengthened(problem.bake())
        assertTrue(
            out.propagate(Assumptions.None) is PropagationResult.Unsat,
            "strengthened problem should be infeasible: ${out.factors}",
        )
    }

    @Test
    fun `an indivisible linear equality should be rewritten to an infeasible problem`() {
        assertInfeasibleAfterStrengthen(
            Problem(
                0,
                2,
                arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
                listOf(Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 5)),
            ),
        )
    }

    @Test
    fun `bounded integer eq and ne rows should keep their feasible set`() {
        assertLinearEquivalent(arrayOf(0..3), Linear(intArrayOf(5), intArrayOf(0), LinearOp.EQ, 10))
        assertLinearEquivalent(arrayOf(0..3, 0..2), Linear(intArrayOf(5, 2), intArrayOf(0, 1), LinearOp.NE, 6))
    }

    @Test
    fun `a row the source bounds on both sides should be lifted without a finite projection`() {
        // Amax 10 and d 2 clamp both coefficients and the bound to 2.
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = arrayOf(Linear(intArrayOf(7, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )

        val delta = Presolve.strengthenSourceCoefficients(problem, Cancellation.Never)

        val lifted = assertIs<Linear>(delta.addedFactors.single())
        assertEquals(listOf(2L, 2L), lifted.vars.indices.map { lifted.integerConstants!!.coeff(it) })
        assertEquals(2L, lifted.integerConstants!!.bound)
    }

    @Test
    fun `a row mentioning an open column should not be lifted`() {
        val openHi = Bits(2).also { it.set(1) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 0), null, openHi),
            factors = arrayOf(Linear(intArrayOf(7, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )

        assertTrue(Presolve.strengthenSourceCoefficients(problem, Cancellation.Never).isEmpty)
    }

    @Test
    fun `a row should be kept when its lift arithmetic would overflow`() {
        // The maximal activity over three 2^62 columns leaves Long.
        val open = IntDomain(0, 1L shl 62)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(open, open, open),
            factors = listOf(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 10)),
        )
        assertTrue(Presolve.strengthenCoefficients(problem, Cancellation.Never, problem.finiteIntDomains()).isEmpty)
    }

    @Test
    fun `a minimum long coefficient should preserve every original assignment`() {
        for ((domain, bound) in listOf((-1..0) to -1L, (0..1) to Long.MIN_VALUE)) {
            val row = Linear(longArrayOf(Long.MIN_VALUE, 1L), intArrayOf(0, 1), LinearOp.LE, bound)

            assertLinearEquivalent(arrayOf(domain, 0..0), row)
        }
    }

    @Test
    fun `signed lifting should preserve shifted and fixed column assignments`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE)) {
            val bound = if (op == LinearOp.LE) 15 else 9
            val row = Linear(intArrayOf(-7, 3, 1), intArrayOf(0, 1, 2), op, bound)

            assertLinearEquivalent(arrayOf(-1..0, 2..3, 1..1), row)
        }
    }

    @Test
    fun `overflow declines should preserve every original assignment`() {
        for (coeffs in listOf(longArrayOf(Long.MAX_VALUE, 1), longArrayOf(Long.MIN_VALUE + 1, 1))) {
            val row = Linear(coeffs, intArrayOf(0, 1), LinearOp.LE, -1)

            assertLinearEquivalent(arrayOf(-1..1, 0..1), row)
        }
    }
}
