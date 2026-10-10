package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.internals.mergeCliques
import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class PresolveSharedTest {

    @Test
    fun `rebuilding and reseeding preserve captured resource settings`() {
        val settings = ProblemSettings(DomainStorageSettings(8), 17, 101, 203)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList(), settings = settings).bake()

        val rebuilt = PresolveShared.rebuildProblem(problem, problem.factors.toList())
        val reseeded = RootBaker.reseed(rebuilt, BakeConfig())

        assertEquals(settings, reseeded.settings)
        assertEquals(problem.rootIntDomain(0), reseeded.rootIntDomain(0))
    }


    private fun pos(v: Int) = Lit.make(v, true)

    @Test
    fun `rebuilding with an expired deadline skips SAC tightening`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            ),
        ).bake(Cancellation.after(Duration.ZERO))

        val rebuilt = PresolveShared.rebuildProblem(
            problem,
            problem.factors.toList(),
            bakeConfig = BakeConfig(probeIntBounds = true),
        )

        assertEquals(0L, rebuilt.rootIntDomain(0).min)
        assertEquals(0L, rebuilt.rootIntDomain(1).min)
    }

    @Test
    fun `successive rebakes record probes in the same phase budget`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            ),
        ).bake(budget.orSpent(Cancellation.Never))
        val config = BakeConfig(probeIntBounds = true)
        val rebuilt = PresolveShared.rebuildProblem(problem, problem.factors.toList(), bakeConfig = config)
        val probes = budget.probeCalls

        PresolveShared.rebuildProblem(rebuilt, rebuilt.factors.toList(), bakeConfig = config)

        assertTrue(budget.probeCalls > probes)
    }

    @Test
    fun `charges through a rebuilt problem consume the originating allowance`() {
        val budget = PresolveBudget(100)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList())
            .bake(budget.orSpent(Cancellation.Never))
        val rebuilt = PresolveShared.rebuildProblem(problem, problem.factors.toList())

        rebuilt.cancellation.charge(25)

        assertEquals(75L, budget.remaining())
    }

    @Test
    fun `a knapsack yields a clique only over the large-weight literals whose pairs exceed the bound`() {
        // 5*x0 + 4*x1 + 1*x2 <= 6: x0+x1 = 9 > 6 exclude, but x2 pairs (6, 5) do not exceed 6.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(5, 4, 1), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 6L)),
        )
        assertEquals(listOf(setOf(pos(0), pos(1))), Presolve.amoCliques(problem))
    }

    @Test
    fun `overlapping binary cliques merge into one maximal clique`() {
        // The three clauses pin at-most-one over each pair {x0,x1}, {x1,x2}, {x0,x2} — a triangle that
        // collapses to a single at-most-one over all three.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            ),
        )
        assertEquals(listOf(setOf(pos(0), pos(1), pos(2))), Presolve.amoCliques(problem))
    }

    @Test
    fun `a literal joins a clique only when it conflicts with every member`() {
        // 3 is adjacent to 1 and 2 but not to 0, so it may not extend the base clique {0,1,2}; the pair
        // it does form survives on its own.
        val merged = mergeCliques(
            listOf(setOf(0, 1, 2), setOf(1, 3), setOf(2, 3)),
        )
        assertEquals(setOf(setOf(0, 1, 2), setOf(1, 2, 3)), merged.toSet())
    }

    @Test
    fun `a cancelled merge returns the base cliques unextended`() {
        // The triangle would collapse to one size-3 clique; cancelling forgoes the growth but every
        // returned clique is still a valid at-most-one.
        val base = listOf(setOf(0, 1), setOf(1, 2), setOf(0, 2))
        val merged = mergeCliques(base) { true }

        assertEquals(base.toSet(), merged.toSet())
    }

    @Test
    fun `maxIntSpan saturates an overflowing span rather than wrapping negative`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)), emptyList())
        assertEquals(Long.MAX_VALUE, PresolveShared.maxIntSpan(problem))
    }
}
