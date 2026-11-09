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
import kotlin.time.Duration

class PresolveSharedTest {
    @Test
    fun `complexity should count factors and endpoint spans exactly when representable`() {
        assertEquals(0L, PresolveShared.problemComplexity(0, emptyArray()))
        for ((domains, expected) in listOf(
            emptyArray<IntDomain>() to 3L,
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE), IntDomain(Long.MAX_VALUE, Long.MAX_VALUE)) to 3L,
            arrayOf(IntDomain(-5, 5).excludeValue(0)) to 13L,
            arrayOf(IntDomain(0, 3_000_000_000_000_000_000L)) to 3_000_000_000_000_000_003L,
            arrayOf(IntDomain(Long.MIN_VALUE, -4)) to Long.MAX_VALUE,
            arrayOf(IntDomain(0, Long.MAX_VALUE - 4), IntDomain(0, 1)) to Long.MAX_VALUE,
        )) {
            val complexity = PresolveShared.problemComplexity(3, domains)

            assertEquals(expected, complexity, domains.contentToString())
        }
    }

    @Test
    fun `complexity should saturate overflowing endpoint differences and aggregate spans`() {
        for (domains in listOf(
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            arrayOf(IntDomain(Long.MIN_VALUE, 0)),
            arrayOf(IntDomain(-1, Long.MAX_VALUE)),
            arrayOf(IntDomain(0, Long.MAX_VALUE)),
            arrayOf(IntDomain(0, Long.MAX_VALUE - 2), IntDomain(0, 1)),
            Array(4) { IntDomain(0, 3_000_000_000_000_000_000L) },
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE), IntDomain(0, 1)),
        )) {
            val complexity = PresolveShared.problemComplexity(3, domains)

            assertEquals(Long.MAX_VALUE, complexity, domains.contentToString())
        }
    }

    @Test
    fun `rebuilding and reseeding should preserve captured resource settings`() {
        val settings = ProblemSettings(DomainStorageSettings(8), 17, 101, 203)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList(), settings = settings).bake()

        val rebuilt = PresolveShared.rebuildProblem(problem, problem.factors.toList())
        val reseeded = RootBaker.reseed(rebuilt, BakeConfig())

        assertEquals(settings, reseeded.settings)
        assertEquals(problem.rootIntDomain(0), reseeded.rootIntDomain(0))
    }

    private fun pos(v: Int) = Lit.make(v, true)

    @Test
    fun `rebuilding with an expired deadline should skip SAC tightening`() {
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
    fun `charges through a rebuilt problem should consume the originating allowance`() {
        val budget = PresolveBudget(1000)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList())
            .bake(budget.orSpent(Cancellation.Never))
        val rebuilt = PresolveShared.rebuildProblem(problem, problem.factors.toList())

        val remaining = budget.remaining()
        rebuilt.cancellation.charge(25)

        assertEquals(remaining - 25L, budget.remaining())
    }

    @Test
    fun `a knapsack should yield a clique only over literal pairs exceeding the bound`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(PseudoBoolean(longArrayOf(5, 4, 1), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 6L)),
        )
        assertEquals(listOf(setOf(pos(0), pos(1))), Presolve.amoCliques(problem))
    }

    @Test
    fun `overlapping binary cliques should merge into one maximal clique`() {
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
    fun `a literal should join a clique only when it conflicts with every member`() {
        val merged = mergeCliques(
            listOf(setOf(0, 1, 2), setOf(1, 3), setOf(2, 3)),
        )
        assertEquals(setOf(setOf(0, 1, 2), setOf(1, 2, 3)), merged.toSet())
    }

    @Test
    fun `a cancelled merge should return the base cliques unextended`() {
        val base = listOf(setOf(0, 1), setOf(1, 2), setOf(0, 2))
        val merged = mergeCliques(base) { true }

        assertEquals(base.toSet(), merged.toSet())
    }

    @Test
    fun `maxIntSpan should saturate an overflowing span`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)), emptyList())
        assertEquals(Long.MAX_VALUE, PresolveShared.maxIntSpan(problem))
    }
}
