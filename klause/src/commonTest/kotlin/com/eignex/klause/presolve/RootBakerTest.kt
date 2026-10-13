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
    fun `a materialized contradiction remains infeasible with inert deduction`() {
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "Original finite factors are checked at complete points.") { NoPropagator }
        })
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0)))).bake(providers)

        val result = RootBaker.provenInfeasible(source, BakeConfig.NONE)

        assertIs<PropagationResult.Unsat>(result.baked)
    }

    private fun bake(problem: Problem, config: BakeConfig): PropagationResult = RootBaker.bake(problem.bake(), config)

    @Test
    fun `root construction exhaustion skips every probing tier`() {
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
    fun `failed literal exhaustion keeps the forcing without entering repair`() {
        val source = Problem(2, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
        ))
        val reference = PresolveBudget(Long.MAX_VALUE)
        val root = source.bake(reference.orSpent(Cancellation.Never))
        assertIs<PropagationResult.Unsat>(root.propagate(Assumptions.None.withBool(0, true), root.cancellation))
        val budget = PresolveBudget(reference.spent())
        val problem = source.bake(budget.orSpent(Cancellation.Never))

        val result = assertIs<PropagationResult.Implied>(RootBaker.bake(problem, BakeConfig(
            probeFailedLiterals = true, probeIntBounds = true, probeIntHoles = true,
        )))

        assertEquals(false, result.boolValueOrNull(0))
        assertEquals(null, result.boolValueOrNull(1))
        assertEquals(1L, budget.probeCalls)
        assertEquals(0L, budget.remaining())
        assertTrue(budget.rootPropagationWork > 0L)
    }

    @Test
    fun `bound probe exhaustion keeps the exclusion without entering repair`() {
        for (op in listOf(LinearOp.GE, LinearOp.LE)) {
            val source = Problem(0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), op, if (op == LinearOp.GE) 2 else 3),
            ))
            val reference = PresolveBudget(Long.MAX_VALUE)
            val root = source.bake(reference.orSpent(Cancellation.Never))
            if (op == LinearOp.LE) root.propagate(Assumptions.None.withInt(0, 0), root.cancellation)
            val failedValue = if (op == LinearOp.GE) 0L else 3L
            assertIs<PropagationResult.Unsat>(root.propagate(
                Assumptions.None.withInt(0, failedValue), root.cancellation,
            ))
            val budget = PresolveBudget(reference.spent())
            val problem = source.bake(budget.orSpent(Cancellation.Never))

            val result = assertIs<PropagationResult.Implied>(RootBaker.bake(problem, BakeConfig(
                probeIntBounds = true, probeIntHoles = true,
            )))

            val bounds = (0..1).map {
                if (op == LinearOp.GE) result.intMinOrNullCompat(it) else result.intMaxOrNullCompat(it)
            }
            assertTrue(if (op == LinearOp.GE) 1L in bounds else 2L in bounds)
            assertEquals(if (op == LinearOp.GE) 1L else 2L, budget.probeCalls)
            assertEquals(0L, budget.remaining())
        }
    }

    @Test
    fun `hole probe exhaustion keeps the hole without entering repair`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(
            Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 0, 3, 3)),
        ))
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
    fun `nonconflicting probe exhaustion does not publish conditional deductions`() {
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
    fun `unlimited metering preserves deductions and bills repairs and reseeds`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
        ))
        val config = BakeConfig(probeIntBounds = true, probeIntHoles = true)
        val expected = RootBaker.reseed(source.bake(), config)
        val budget = PresolveBudget(Long.MAX_VALUE)
        val root = source.bake(budget.orSpent(Cancellation.Never))
        val entries = budget.rootConstructionEntries

        val result = RootBaker.reseed(root, config)

        assertEquals(expected.rootIntDomains().toList(), result.rootIntDomains().toList())
        assertTrue(budget.probeCalls > 4L)
        assertEquals(entries * (budget.probeCalls + 2L), budget.rootConstructionEntries)
        assertEquals(53L * (budget.rootConstructionEntries + budget.rootPropagationWork), budget.spent())
    }

    @Test
    fun `zero allowance skips probes while retaining mandatory construction accounting`() {
        val budget = PresolveBudget(0)
        val root = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList())
            .bake(budget.orSpent(Cancellation.Never))

        RootBaker.reseed(root, BakeConfig(probeIntHoles = true))

        assertEquals(0L, budget.probeCalls)
        assertEquals(0L, budget.remaining())
        assertEquals(1L, budget.rootConstructionEntries)
        assertEquals(0L, root.rootIntDomain(0).min)
        assertEquals(3L, root.rootIntDomain(0).max)
    }

    @Test
    fun `root refutation bills propagation before returning`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val root = Problem(1, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Clause(intArrayOf(Lit.make(0, false))),
        )).bake(budget.orSpent(Cancellation.Never))

        assertIs<PropagationResult.Unsat>(root.baked)
        assertTrue(budget.rootPropagationWork > 0L)
        assertEquals(53L * (budget.rootConstructionEntries + budget.rootPropagationWork), budget.spent())
    }

    @Test
    fun `native propagation charges stop a root fixpoint before refutation`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 1_000_000), IntDomain(0, 1_000_000)), listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1),
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
        ))
        val entries = 2L + source.numFactors + source.factors.sumOf { it.structuralKeyWeight.toLong() }
        val budget = PresolveBudget(53L * entries + 1L)

        val result = source.bake(budget.orSpent(Cancellation.Never))

        assertIs<PropagationResult.Implied>(result.baked)
        assertEquals(0L, budget.remaining())
        assertTrue(budget.rootPropagationWork > 0L)
        assertTrue(result.rootIntDomain(0).min < result.rootIntDomain(0).max)
    }

    @Test
    fun `repair exhaustion retains its deductions and stops subsequent probes`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 3)), listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
        ))
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
    fun `zero integer call caps skip bound and hole probes`() {
        for (config in listOf(
            BakeConfig(probeIntHoles = true, probeBudgetPerVar = 0),
            BakeConfig(probeIntHoles = true, probeTotalBudget = 0),
        )) {
            val budget = PresolveBudget(Long.MAX_VALUE)
            val root = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList())
                .bake(budget.orSpent(Cancellation.Never))

            RootBaker.bake(root, config)

            assertEquals(0L, budget.probeCalls)
            assertEquals(1L, budget.rootConstructionEntries)
        }
    }

    @Test
    fun `stopping Boolean probing retains incoming integer bounds`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 3)), listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
        ))
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
    fun `native charges saturate an inherited phase without wrapping`() {
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
    fun `outer cancellation after a failed probe retains the proven exclusion`() {
        val source = Problem(2, 0, emptyArray(), listOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)
        val reference = PresolveBudget(Long.MAX_VALUE)
        val control = source.bake(reference.orSpent(Cancellation.Never))
        control.propagate(Assumptions.None.withBool(0, true), control.cancellation)
        val root = source.bake(budget.orSpent(Cancellation {
            budget.rootPropagationWork >= reference.rootPropagationWork
        }))

        val result = assertIs<PropagationResult.Implied>(RootBaker.bake(root, BakeConfig(probeFailedLiterals = true)))

        assertEquals(false, result.boolValueOrNull(0))
        assertEquals(1L, budget.probeCalls)
        assertTrue(budget.rootPropagationWork > 0L)
    }

    @Test
    fun `failed-literal probing detects Unsat when both polarities fail`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertIs<PropagationResult.Unsat>(bake(p, BakeConfig(probeFailedLiterals = true)))
    }

    @Test
    fun `bound SAC tightens an int min when its lowest value is locally infeasible`() {
        // x = y, x + y ≥ 2 over [0..3]^2. Bound-SAC probing x=0 finds x+y=0 < 2 infeasible → x.min ≥ 1.
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
        assertEquals(1L, baked.intMinOrNullCompat(0), "bound SAC should have lifted x.min to 1")
        assertEquals(1L, baked.intMinOrNullCompat(1), "bound SAC should have lifted y.min to 1")
    }

    @Test
    fun `interior-hole SAC excludes an unreachable middle value`() {
        // Allowed tuples (0,0) and (3,3); interior values 1, 2 are excluded by interior-hole SAC.
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
        assertEquals(setOf(1L, 2L), xHoles, "interior-hole SAC should mark x ≠ 1 and x ≠ 2")
    }

    @Test
    fun `per-var budget caps the probe count`() {
        // x = y, x + y ≤ 3 over [0..3]^2. Only the max probe deduces anything (x=3 and x=2 both fail),
        // so an unbounded budget lands x.max at 1 while a per-var budget of 1 is spent on the min probe.
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
        assertEquals(1L, unbounded.intMaxOrNullCompat(0), "an unbounded budget probes x.max down to 1")
        val capped = assertIs<PropagationResult.Implied>(
            bake(p, BakeConfig(probeIntBounds = true, probeBudgetPerVar = 1)),
        )
        assertEquals(3L, capped.intMaxOrNullCompat(0) ?: 3L, "a budget of 1 leaves no call for the max probe")
    }

    @Test
    fun `cancellation mid-bake yields a sound partial bake`() {
        // A cancellation that has already fired stops probing before it enters any phase, so the bake is
        // just the base bake — sound (probing only ever tightens, so skipping it forgoes tightening only).
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
        assertEquals(null, baked.intMinOrNullCompat(0), "a fired cancellation should skip SAC tightening")
    }

    @Test
    fun `a witnessed refutation keeps the model's open sides open`() {
        // The upper endpoint was invented to close a genuinely open column. A refutation that reboxed it
        // as a declared bound would read as one derived inside a box the model never stated.
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
        assertFalse(refuted.intBounds.hasUpper(0), "the invented endpoint must not become a declared bound")
    }
}
