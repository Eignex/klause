package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RootBakerTest {
    @Test
    fun `a certified refutation should survive spent construction allowance`() {
        val budget = PresolveBudget(0L)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyList())
            .bake(budget.orSpent(Cancellation.Never))

        val result = RootBaker.provenInfeasible(problem, BakeConfig.NONE)

        assertIs<PropagationResult.Unsat>(result.baked)
    }

    @Test
    fun `a materialized contradiction should remain infeasible with inert deduction`() {
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(
                    PropagationCapability.INERT,
                    AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "Original finite factors are checked at complete points.",
                ) { NoPropagator }
            },
        )
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0)))).bake(providers)

        val result = RootBaker.provenInfeasible(source, BakeConfig.NONE)

        assertIs<PropagationResult.Unsat>(result.baked)
    }

    private fun bake(problem: Problem, config: BakeConfig): PropagationResult = RootBaker.bake(problem.bake(), config)

    @Test
    fun `root construction exhaustion should skip every probing tier`() {
        for (config in listOf(
            BakeConfig(probeFailedLiterals = true),
            BakeConfig(probeIntBounds = true),
            BakeConfig(probeIntHoles = true),
            BakeConfig(probeFailedLiterals = true, probeIntBounds = true, probeIntHoles = true),
        )) {
            val budget = PresolveBudget(1)
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 3)), emptyList())
                .bake(budget.orSpent(Cancellation.Never))

            val result = RootBaker.bake(problem, config)

            assertEquals(problem.baked, result)
            assertEquals(0L, budget.probeCalls)
            assertEquals(0L, budget.remaining())
            assertTrue(budget.rootConstructionEntries > 0L)
        }
    }

    @Test
    fun `failed literal exhaustion should keep the forcing without entering repair`() {
        val source = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val reference = PresolveBudget(Long.MAX_VALUE)
        val root = source.bake(reference.orSpent(Cancellation.Never))
        assertIs<PropagationResult.Unsat>(root.propagate(Assumptions.None.withBool(0, true), root.cancellation))
        val budget = PresolveBudget(reference.spent())
        val problem = source.bake(budget.orSpent(Cancellation.Never))

        val result = assertIs<PropagationResult.Implied>(
            RootBaker.bake(
                problem,
                BakeConfig(
                    probeFailedLiterals = true,
                    probeIntBounds = true,
                    probeIntHoles = true,
                ),
            ),
        )

        assertEquals(false, result.boolValueOrNull(0))
        assertEquals(null, result.boolValueOrNull(1))
        assertEquals(1L, budget.probeCalls)
        assertEquals(0L, budget.remaining())
        assertTrue(budget.rootPropagationWork > 0L)
    }

    @Test
    fun `bound probe exhaustion should keep the exclusion without entering repair`() {
        for (op in listOf(LinearOp.GE, LinearOp.LE)) {
            val source = Problem(
                0,
                2,
                arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                listOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), op, if (op == LinearOp.GE) 2 else 3),
                ),
            )
            val reference = PresolveBudget(Long.MAX_VALUE)
            val root = source.bake(reference.orSpent(Cancellation.Never))
            if (op == LinearOp.LE) root.propagate(Assumptions.None.withInt(0, 0), root.cancellation)
            val failedValue = if (op == LinearOp.GE) 0L else 3L
            assertIs<PropagationResult.Unsat>(
                root.propagate(
                    Assumptions.None.withInt(0, failedValue),
                    root.cancellation,
                ),
            )
            val budget = PresolveBudget(reference.spent())
            val problem = source.bake(budget.orSpent(Cancellation.Never))

            val result = assertIs<PropagationResult.Implied>(
                RootBaker.bake(
                    problem,
                    BakeConfig(
                        probeIntBounds = true,
                        probeIntHoles = true,
                    ),
                ),
            )

            val bounds = (0..1).map {
                if (op == LinearOp.GE) result.intMinOrNullCompat(it) else result.intMaxOrNullCompat(it)
            }
            assertTrue(if (op == LinearOp.GE) 1L in bounds else 2L in bounds)
            assertEquals(if (op == LinearOp.GE) 1L else 2L, budget.probeCalls)
            assertEquals(0L, budget.remaining())
        }
    }

    @Test
    fun `hole probe exhaustion should keep the hole without entering repair`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 0, 3, 3)),
            ),
        )
        val reference = PresolveBudget(Long.MAX_VALUE)
        val root = source.bake(reference.orSpent(Cancellation.Never))
        RootBaker.bake(root, BakeConfig(probeIntBounds = true))
        assertIs<PropagationResult.Unsat>(root.propagate(Assumptions.None.withInt(0, 1), root.cancellation))
        val budget = PresolveBudget(reference.spent())
        val problem = source.bake(budget.orSpent(Cancellation.Never))

        val result = assertIs<PropagationResult.Implied>(RootBaker.bake(problem, BakeConfig(probeIntHoles = true)))

        assertEquals(listOf(1L), result.intHoleValues.toList())
        assertEquals(reference.probeCalls + 1L, budget.probeCalls)
        assertEquals(0L, budget.remaining())
    }

    @Test
    fun `nonconflicting probe exhaustion should not publish conditional deductions`() {
        for (config in listOf(BakeConfig(probeFailedLiterals = true), BakeConfig(probeIntBounds = true))) {
            val budget = PresolveBudget(3 * 53L)
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 3)), emptyList())
                .bake(budget.orSpent(Cancellation.Never))

            val result = RootBaker.bake(problem, config)

            assertEquals(problem.baked, result)
            assertEquals(1L, budget.probeCalls)
            assertEquals(0L, budget.remaining())
        }
    }

    @Test
    fun `native propagation charges should stop a root fixpoint before refutation`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1_000_000), IntDomain(0, 1_000_000)),
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1),
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
            ),
        )
        val entries = 2L + source.numFactors + source.factors.sumOf { it.structuralKeyWeight.toLong() }
        val budget = PresolveBudget(53L * entries + 1L)

        val result = source.bake(budget.orSpent(Cancellation.Never))

        assertIs<PropagationResult.Implied>(result.baked)
        assertEquals(0L, budget.remaining())
        assertTrue(budget.rootPropagationWork > 0L)
        assertTrue(result.rootIntDomain(0).min < result.rootIntDomain(0).max)
    }

    @Test
    fun `repair exhaustion should retain its deductions and stop subsequent probes`() {
        val source = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            ),
        )
        val reference = PresolveBudget(Long.MAX_VALUE)
        val root = source.bake(reference.orSpent(Cancellation.Never))
        root.propagate(Assumptions.None.withInt(0, 0), root.cancellation)
        root.propagate(Assumptions.None.withTightenedMin(0, 1), root.cancellation)
        val budget = PresolveBudget(reference.spent())
        val problem = source.bake(budget.orSpent(Cancellation.Never))

        val result = assertIs<PropagationResult.Implied>(RootBaker.bake(problem, BakeConfig(probeIntHoles = true)))

        assertEquals(1L, result.intMinOrNullCompat(0))
        assertEquals(1L, result.intMinOrNullCompat(1))
        assertEquals(2L, budget.probeCalls)
        assertEquals(0L, budget.remaining())
    }

    @Test
    fun `stopping Boolean probing should retain incoming integer bounds`() {
        val source = Problem(
            1,
            1,
            arrayOf(IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
        )
        val reference = PresolveBudget(Long.MAX_VALUE)
        val root = source.bake(reference.orSpent(Cancellation.Never))
        root.propagate(Assumptions.None.withBool(0, true), root.cancellation)
        val budget = PresolveBudget(reference.spent())
        val problem = source.bake(budget.orSpent(Cancellation.Never))

        val result = assertIs<PropagationResult.Implied>(
            RootBaker.bake(problem, BakeConfig(probeFailedLiterals = true)),
        )

        assertEquals(1L, result.intMinOrNullCompat(0))
        assertEquals(null, result.boolValueOrNull(0))
        assertEquals(1L, budget.probeCalls)
    }

    @Test
    fun `native charges should saturate an inherited phase without wrapping`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        budget.charge(Long.MAX_VALUE - 1L)

        val root = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList())
            .bake(budget.orSpent(Cancellation.Never))
        RootBaker.bake(root, BakeConfig(probeIntHoles = true))

        assertEquals(Long.MAX_VALUE, budget.spent())
        assertEquals(0L, budget.remaining())
        assertEquals(0L, budget.probeCalls)
    }

    @Test
    fun `outer cancellation after a failed probe should retain the proven exclusion`() {
        val source = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        val reference = PresolveBudget(Long.MAX_VALUE)
        val control = source.bake(reference.orSpent(Cancellation.Never))
        control.propagate(Assumptions.None.withBool(0, true), control.cancellation)
        val root = source.bake(
            budget.orSpent(
                Cancellation {
                    budget.rootPropagationWork >= reference.rootPropagationWork
                },
            ),
        )

        val result = assertIs<PropagationResult.Implied>(RootBaker.bake(root, BakeConfig(probeFailedLiterals = true)))

        assertEquals(false, result.boolValueOrNull(0))
        assertEquals(1L, budget.probeCalls)
        assertTrue(budget.rootPropagationWork > 0L)
    }

    @Test
    fun `bound SAC should tighten an int min when its lowest value is locally infeasible`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2),
            ),
        )
        val baked = assertIs<PropagationResult.Implied>(bake(p, BakeConfig(probeIntBounds = true)))
        assertEquals(1L, baked.intMinOrNullCompat(0))
        assertEquals(1L, baked.intMinOrNullCompat(1))
    }

    @Test
    fun `interior-hole SAC should exclude an unreachable middle value`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 0, 3, 3)),
            ),
        )
        val baked = assertIs<PropagationResult.Implied>(
            bake(p, BakeConfig(probeIntBounds = true, probeIntHoles = true)),
        )
        val xHoles = mutableSetOf<Long>()
        baked.forEachIntHole { id, v -> if (id == 0) xHoles.add(v) }
        assertEquals(setOf(1L, 2L), xHoles)
    }

    @Test
    fun `a per-var budget should cap the probe count`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = 3),
            ),
        )
        val unbounded = assertIs<PropagationResult.Implied>(bake(p, BakeConfig(probeIntBounds = true)))
        assertEquals(1L, unbounded.intMaxOrNullCompat(0))
        val capped = assertIs<PropagationResult.Implied>(
            bake(p, BakeConfig(probeIntBounds = true, probeBudgetPerVar = 1)),
        )
        assertEquals(3L, capped.intMaxOrNullCompat(0) ?: 3L)
    }

    @Test
    fun `an already-fired cancellation should skip probing tightening`() {
        val cancelled = Cancellation { true }
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, -1), vars = intArrayOf(0, 1), op = LinearOp.EQ, bound = 0),
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 2),
            ),
        ).bake(cancelled)
        val baked = assertIs<PropagationResult.Implied>(bake(p, BakeConfig(probeIntBounds = true)))
        assertEquals(null, baked.intMinOrNullCompat(0))
    }

    @Test
    fun `a witnessed refutation should keep the model open sides open`() {
        val open = Bits(1).also { it.set(0) }
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 100)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)),
            packedOpenIntHi = open,
        ).bake()

        val refuted = RootBaker.provenInfeasible(p, BakeConfig.NONE)

        assertIs<PropagationResult.Unsat>(refuted.baked)
        assertFalse(refuted.intBounds.hasUpper(0))
    }
}
