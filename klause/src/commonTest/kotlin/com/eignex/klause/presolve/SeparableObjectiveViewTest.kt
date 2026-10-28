package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.booleanObjectiveCutoff
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.meta.coreguided.CoreGuidedSearch
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SeparableObjectiveViewTest {
    @Test
    fun `shared unit definitions should retain one orientation and combine leaf costs`() {
        val problem = Problem(0, 6, arrayOf(
            IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1),
            IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 4),
        ), listOf(
            Linear(longArrayOf(1, -1, -1), intArrayOf(5, 3, 4), LinearOp.EQ, 0),
            Linear(longArrayOf(1, -1, -1), intArrayOf(3, 0, 1), LinearOp.EQ, 0),
            Linear(longArrayOf(1, -1, -1), intArrayOf(4, 0, 2), LinearOp.EQ, 0),
        )).bake()
        val source = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 0, 0, 1))

        val view = checkNotNull(SeparableObjectiveView.create(problem, source))

        assertEquals(listOf(2L, 1L, 1L, 0L, 0L, 0L), view.objective.intCoefficients.toList())
        for (x in 0L..1L) for (y in 0L..1L) for (z in 0L..1L) {
            val point = Sample(booleanArrayOf(), longArrayOf(x, y, z, x + y, x + z, 2 * x + y + z))
            assertEquals(source.evaluateExact(point), view.objective.evaluateExact(point))
        }
    }

    @Test
    fun `nested signed definitions should combine costs and offsets on every source point`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(-1, 1), IntDomain(-1, 3), IntDomain(-1, 5), IntDomain(-2, 8)),
            listOf(
                Linear(longArrayOf(1, -1, -1), intArrayOf(3, 1, 2), LinearOp.EQ, 0),
                Linear(longArrayOf(1, -2), intArrayOf(1, 0), LinearOp.EQ, 1),
                Linear(longArrayOf(-1, 3), intArrayOf(2, 0), LinearOp.EQ, -2),
            ),
        ).bake()
        for (scale in listOf(-3L, 2L)) {
            val source = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, scale), constant = 7)

            val view = checkNotNull(SeparableObjectiveView.create(problem, source))

            assertSame(problem, view.problem)
            assertSame(source, view.sourceObjective)
            assertEquals(LinearObjective(intCoefficients = longArrayOf(5 * scale, 0, 0, 0),
                constant = 7 + 3 * scale), view.objective)
            for (x in -1L..1L) {
                val point = Sample(booleanArrayOf(), longArrayOf(x, 2 * x + 1, 3 * x + 2, 5 * x + 3))
                assertEquals(source.evaluateExact(point), view.objective.evaluateExact(point))
            }
        }
    }

    @Test
    fun `normal preparation should retain a hidden binary sum for the existing core consumer`() {
        val source = Problem(
            3, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
            listOf(
                Linear(longArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
                Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(longArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
                Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            ) + List(3) { ReifiedLinear(it, longArrayOf(1), intArrayOf(it), LinearOp.EQ, 1) },
        )
        val original = LinearObjective(boolWeights = LongArray(3), intCoefficients = longArrayOf(0, 0, 0, 1))
        val prepared = PresolvePipeline.run(source, original, PresolveConfig.DEFAULT, false)
        val authority = checkNotNull(prepared.objective)
        val problem = prepared.problem.bake()
        val view = checkNotNull(SeparableObjectiveView.create(problem, authority))
        val floors = mutableListOf<Double>()
        val search = CoreGuidedSearch(problem, authority, BacktrackParams(objectiveLowerBoundSink = { floors += it }))

        val result = search.use {
            assertIs<MinimizeResult.Optimal>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})
        }

        assertEquals(original, authority)
        assertEquals(3, authority.singleIntObjective()?.varId)
        assertTrue(view.objective.intCoefficients.take(3).all { it == 1L })
        assertFalse(CoreGuidedSearch.eligible(problem, authority))
        assertEquals(2L, result.stats.coreGuided.cores)
        assertEquals(2, result.stats.coreGuided.largestCore)
        assertEquals(2.0, floors.last())
        assertEquals(2L, original.evaluateLong(prepared.reconstruct(result.sample)))
        assertEquals(problem.numBoolVars, result.sample.numBoolVars)
        assertEquals(4, result.sample.numIntVars)
    }

    @Test
    fun `signed floors should use the original objective units and caller scope`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(2, 4)),
            listOf(Linear(longArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, -2)),
        ).bake()
        for (scale in listOf(-3L, 2L)) {
            val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, scale), constant = 7)
            val search = CoreGuidedSearch(problem, objective, BacktrackParams(
                assumptions = Assumptions(ints = mapOf(0 to 1L)),
            ))

            val result = search.use {
                assertIs<MinimizeResult.Optimal>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})
            }

            assertEquals(if (scale > 0) 13L else -5L, objective.evaluateLong(result.sample))
            assertEquals(objective.evaluateExact(result.sample), search.lowerBound)
            assertEquals(1L, result.sample.intValue(0))
        }
    }

    @Test
    fun `finite nonbinary leaves should expose exact signed costs`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(-3, 8), IntDomain(2, 10), IntDomain(-1, 18)),
            listOf(Linear(longArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, -2), constant = 5)

        val view = checkNotNull(SeparableObjectiveView.create(problem, objective))

        assertEquals(LinearObjective(intCoefficients = longArrayOf(-2, -2, 0), constant = 5), view.objective)
    }

    @Test
    fun `retained finite sum aliases should consume overlapping cheap term cores`() {
        val source = Problem(0, 4, arrayOf(
            intDomainFromSurvivors(longArrayOf(0, 3, 8)),
            intDomainFromSurvivors(longArrayOf(0, 3, 10)),
            intDomainFromSurvivors(longArrayOf(0, 3, 11)), IntDomain(0, 29),
        ), listOf(
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(longArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 3),
            Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 3),
            Linear(longArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
        ))
        val original = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val prepared = PresolvePipeline.run(source, original, PresolveConfig.DEFAULT, false)
        val objective = prepared.objective ?: original
        val floors = mutableListOf<Double>()
        val search = CoreGuidedSearch(prepared.problem.bake(), objective,
            BacktrackParams(objectiveLowerBoundSink = { floors += it }))

        val result = search.use {
            assertIs<MinimizeResult.Optimal>(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})
        }

        assertEquals(original, objective)
        assertEquals(listOf(3.0, 6.0), floors)
        assertEquals(2L, result.stats.coreGuided.cores)
        assertEquals(6L, original.evaluateLong(prepared.reconstruct(result.sample)))
    }

    @Test
    fun `existing Boolean cutoffs should retain strict source costs in either direction`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(2, 4)),
            listOf(Linear(longArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, -2)),
        ).bake()
        for (scale in listOf(-3L, 2L)) {
            val source = LinearObjective(intCoefficients = longArrayOf(0, 0, scale), constant = 7)
            val costs = checkNotNull(SeparableObjectiveView.create(problem, source)).objective
            val binary = checkNotNull(BinaryObjectiveView.create(problem, costs))
            val model = Problem(binary.numBoolVars, 3, problem.rootIntDomains(), binary.factors)
            val incumbent = source.evaluateExact(Sample(booleanArrayOf(), longArrayOf(1, 0, 3)))
            val cutoff = checkNotNull(booleanObjectiveCutoff(model, binary.objective, incumbent))
            val bounded = Problem(model.numBoolVars, 3, problem.rootIntDomains(),
                binary.factors + PseudoBoolean(cutoff.weights, cutoff.literals, PbOp.GE, cutoff.degree))

            val accepted = BacktrackSolver(bounded.bake()).enumerate().toList()

            assertEquals(1, accepted.size)
            assertTrue(source.evaluateExact(accepted.single()) < incumbent)
            assertEquals(source.evaluateExact(accepted.single()), binary.objective.evaluateExact(accepted.single()))
            assertEquals(if (scale > 0) 2L else 4L, accepted.single().intValue(2))
        }
    }

    @Test
    fun `inequalities conditional definitions and nonunit pivots should have no authority`() {
        for (factor in listOf(
            Linear(longArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
            Linear(longArrayOf(1, 1, -2), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ReifiedLinear(0, longArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
        )) {
            val problem = Problem(1, 3, Array(3) { IntDomain(0, 2) }, listOf(factor)).bake()

            assertNull(SeparableObjectiveView.create(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 1))))
        }
    }

    @Test
    fun `cyclic definitions should decline the complete view`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
                Linear(longArrayOf(1, -1), intArrayOf(2, 0), LinearOp.EQ, 0),
            ),
        ).bake()

        assertNull(SeparableObjectiveView.create(problem, LinearObjective(intCoefficients = longArrayOf(1))))
    }

    @Test
    fun `coefficient overflow should decline without changing the source objective`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, -2), intArrayOf(1, 0), LinearOp.EQ, 0)),
        ).bake()
        val source = LinearObjective(intCoefficients = longArrayOf(0, Long.MAX_VALUE))

        assertNull(SeparableObjectiveView.create(problem, source))
        assertEquals(Long.MAX_VALUE, source.intCoefficients[1])
    }

    @Test
    fun `depth and surviving term limits should decline complete expansion`() {
        val chain = Problem(0, 18, Array(18) { IntDomain(0, 1) }, List(17) {
            Linear(longArrayOf(1, -2), intArrayOf(it + 1, it), LinearOp.EQ, 0)
        }).bake()
        val wide = Problem(0, 258, Array(258) { IntDomain(0, 514) }, listOf(
            Linear(LongArray(258) { if (it == 257) -1 else 2 }, IntArray(258) { it }, LinearOp.EQ, 0),
        )).bake()
        for (problem in listOf(chain, wide)) {
            val objective = LinearObjective(intCoefficients = LongArray(problem.numIntVars) {
                if (it == problem.numIntVars - 1) 1 else 0
            })

            assertNull(SeparableObjectiveView.create(problem, objective))
        }
    }

    @Test
    fun `approximation and missing finite bounds should decline`() {
        val definition = Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)
        val approximate = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(definition))
            .withSourceSemantics(SourceSemantics(SourceFormat.IR,
                feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ofLong(1)),
            ).resolve()).bake()
        val open = BakedProblem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(definition),
            openIntHi = booleanArrayOf(false, true))
        for (problem in listOf(approximate, open)) {
            assertNull(SeparableObjectiveView.create(problem, LinearObjective(intCoefficients = longArrayOf(1))))
        }
    }

    @Test
    fun `stopped expansion should retain charged work and decline`() {
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2
            override fun workMeter(): WorkMeter = object : WorkMeter {
                override fun charge(units: Long) { charged += units }
            }
        }
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        ).bake()

        assertNull(SeparableObjectiveView.create(
            problem, LinearObjective(intCoefficients = longArrayOf(1)), cancellation,
        ))
        assertTrue(charged >= 2)
    }
}
