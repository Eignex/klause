package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DominatedVariablesTest {
    @Test
    fun `a declared Boolean inequality should permit a safe pure-literal fixing`() {
        val source = PseudoBoolean(longArrayOf(2, 3), intArrayOf(Lit.make(0, true), Lit.make(1, false)), PbOp.LE, 3)
        val factor = object : Factor by source, Propagator by source.propagatorProjection() {}
        val model = Problem(2, 0, emptyArray(), listOf(factor)).bake()

        val delta = Presolve.fixDominatedVariables(model, emptyMap())

        val units = delta.addedFactors.map { (it as Clause).literals.single() }.toSet()
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, true)), units)
    }

    private fun isFeasible(problem: Problem, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun minObjective(problem: Problem, coeffs: Map<Int, Long>): Long? {
        val n = problem.numIntVars
        val ints = LongArray(n) { problem.finiteIntDomain(it).min }
        var best: Long? = null
        while (true) {
            if (isFeasible(problem, ints.copyOf())) {
                var obj = 0L
                for (v in 0 until n) obj += (coeffs[v] ?: 0L) * ints[v]
                if (best == null || obj < best) best = obj
            }
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.finiteIntDomain(i).max) break
                ints[i] = problem.finiteIntDomain(i).min
                i++
            }
            if (i == n) break
        }
        return best
    }

    private fun fixed(problem: Problem, intCoeffs: Map<Int, Long>, boolCoeffs: Map<Int, Long> = emptyMap()): Problem {
        val baked = problem.bake()
        return baked.withPassDelta(Presolve.fixDominatedVariables(baked, intCoeffs, boolCoeffs), BakeConfig.NONE)
    }

    private fun checkDualFix(name: String, problem: Problem, coeffs: Map<Int, Long>, expectFixed: Set<Int>) {
        val out = fixed(problem, coeffs)
        assertEquals(minObjective(problem, coeffs), minObjective(out, coeffs), "$name: optimum changed")
        for (v in expectFixed) {
            assertTrue(
                out.finiteIntDomain(v).min == out.finiteIntDomain(v).max,
                "$name: var $v should be pinned",
            )
        }
        if (expectFixed.isEmpty()) {
            assertTrue(Presolve.fixDominatedVariables(problem.bake(), coeffs).isEmpty, "$name: expected no fixing")
        }
    }

    @Test
    fun `a variable that is neither up- nor down-safe should be left free`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 4) },
            listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
            ),
        )
        val out = fixed(problem, emptyMap())
        assertEquals(minObjective(problem, emptyMap()), minObjective(out, emptyMap()))
        assertTrue(out.finiteIntDomain(0).min != out.finiteIntDomain(0).max)
    }

    @Test
    fun `variables in a global constraint should not be fixed`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        checkDualFix("global-excluded", problem, emptyMap(), emptySet())
    }

    private fun pos(v: Int) = Lit.make(v, true)

    private fun minObjectiveBools(problem: Problem, weights: Map<Int, Long>): Long? {
        val nb = problem.numBoolVars
        var best: Long? = null
        for (mask in 0 until (1 shl nb)) {
            var a = Assumptions.None
            val bits = BooleanArray(nb) { (mask shr it) and 1 == 1 }
            for (v in 0 until nb) a = a.withBool(v, bits[v])
            if (problem.propagate(a) is PropagationResult.Unsat) continue
            var obj = 0L
            for (v in 0 until nb) if (bits[v]) obj += weights[v] ?: 0L
            if (best == null || obj < best) best = obj
        }
        return best
    }

    private fun hasUnit(problem: Problem, lit: Int) =
        problem.factors.any { it is Clause && it.literals.size == 1 && it.literals[0] == lit }

    @Test
    fun `booleans in a two-sided cardinality should not be fixed`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 1, max = 1)),
        )
        assertTrue(Presolve.fixDominatedVariables(problem.bake(), emptyMap(), emptyMap()).isEmpty)
    }

    @Test
    fun `a negative-weight pseudo-boolean LE should flip the safe direction`() {
        val problem = Problem(
            1,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(-1), intArrayOf(pos(0)), PbOp.LE, 0L)),
        )
        val out = fixed(problem, emptyMap(), emptyMap())
        assertEquals(minObjectiveBools(problem, emptyMap()), minObjectiveBools(out, emptyMap()))
        assertTrue(hasUnit(out, Lit.make(0, true)))
    }

    private fun openAbove(lo: Long, vararg factors: Factor): Problem = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(
            longArrayOf(lo),
            longArrayOf(0L),
            null,
            Bits(1).also { it.set(0) },
        ),
        factors = arrayOf(*factors),
    )

    private fun openBelow(hi: Long, vararg factors: Factor): Problem = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(
            longArrayOf(0L),
            longArrayOf(hi),
            Bits(1).also { it.set(0) },
            null,
        ),
        factors = arrayOf(*factors),
    )

    @Test
    fun `a down-safe column open above should be pinned to its lower bound`() {
        val problem = openAbove(0L, Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7))

        val delta = Presolve.fixDominatedSourceVariables(problem, emptyMap())

        val out = assertNotNull(problem.withSourcePassDelta(delta))
        assertEquals(0L, out.intBounds.lower(0))
        assertEquals(0L, out.intBounds.upper(0))
    }

    @Test
    fun `a down-safe column open below should be left free`() {
        val problem = openBelow(7L, Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7))

        val delta = Presolve.fixDominatedSourceVariables(problem, emptyMap())

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `an up-safe column open above should stay free while its bounded partner pins`() {
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0L, 0L),
                longArrayOf(0L, 5L),
                null,
                Bits(2).also { it.set(0) },
            ),
            factors = arrayOf(Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.LE, 4)),
        )

        val delta = Presolve.fixDominatedSourceVariables(problem, mapOf(0 to -1L))

        val out = assertNotNull(problem.withSourcePassDelta(delta))
        assertFalse(out.intBounds.hasUpper(0))
        assertEquals(0L, out.intBounds.lower(1))
        assertEquals(0L, out.intBounds.upper(1))
    }

    @Test
    fun `a pin should land on the declared value set rather than the wider model range`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1000)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)),
            modelBounds = IntBounds.fromModelBounds(longArrayOf(0L), longArrayOf(1_000_000L), null, null),
        )

        val delta = Presolve.fixDominatedSourceVariables(problem, emptyMap())

        val out = assertNotNull(problem.withSourcePassDelta(delta))
        assertEquals(1000L, out.intBounds.lower(0))
        assertEquals(1000L, out.intBounds.upper(0))
    }
}
