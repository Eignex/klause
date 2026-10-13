package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Publication
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SharedPoolsTest {
    @Test
    fun `a checked restricted witness can supply a containing query cutoff`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val plan = source.prepareExecutionPlan(objective)
        val consumer = EngineInvocation(plan, model, objective)
        val producer = EngineInvocation(plan, model, objective,
            sourceAssumptions = Assumptions(ints = mapOf(0 to 3L)))
        val sample = Sample(booleanArrayOf(), longArrayOf(3))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(producer, sample, 3.0))
        val exchange = IncumbentExchange.minimizing<Sample>()
        val pools = SharedPools(null, null, solutions = exchange, identity = model)

        val published = pools.offerSolution(model, sample, 3.0, certificate, consumer)

        assertIs<Publication.Installed<*, *>>(published)
        assertEquals(3.0, exchange.current()?.objective)
    }

    @Test
    fun `a native witness violating the containing query cannot supply a cutoff`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val plan = source.prepareExecutionPlan(objective)
        val producer = EngineInvocation(plan, model, objective)
        val consumer = EngineInvocation(plan, model, objective,
            sourceAssumptions = Assumptions(ints = mapOf(0 to 2L)))
        val sample = Sample(booleanArrayOf(), longArrayOf(3))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(producer, sample, 3.0))
        val exchange = IncumbentExchange.minimizing<Sample>()
        val pools = SharedPools(null, null, solutions = exchange, identity = model)

        val published = pools.offerSolution(model, sample, 3.0, certificate, consumer)

        assertIs<Publication.Rejected>(published)
        assertNull(exchange.current())
    }

    @Test
    fun `actual backtrack incumbents use the containing runtime admission`() {
        val problem = Problem(1, 0, emptyArray(),
            arrayOf(Cardinality(intArrayOf(Lit.make(0, true)), min = 1, max = 1))).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(7))
        val model = ModelIdentity.of(problem, objective)
        val invocation = EngineInvocation(problem.prepareExecutionPlan(objective), model, objective)
        val worker = PortfolioBuilder.build(problem,
            PortfolioScenario(cores = 1, arms = 1, kind = Kind.COP, engine = EngineMix.BACKTRACK),
            objective = objective).single().bindInvocation(invocation).bindSharingInvocation(invocation)
        val exchange = requireNotNull(worker.sharedPools?.solutions)

        worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        assertEquals(7.0, exchange.current()?.objective)
    }

    @Test
    fun `an unverified or foreign incumbent cannot supply a pruning cutoff`() {
        val model = ModelIdentity.of(Any(), Any())
        val other = ModelIdentity.of(Any(), Any())
        val exchange = IncumbentExchange.minimizing<Sample>()
        val pools = SharedPools(null, null, solutions = exchange, identity = model)
        val sample = Sample(BooleanArray(0), longArrayOf(7))

        assertIs<Publication.Indeterminate>(pools.offerSolution(model, sample, 7.0, null))
        assertIs<Publication.Rejected>(pools.offerSolution(
            other, sample, 7.0, EvidenceCertificate.verified(other, EvidenceKind.Witness),
        ))
        assertNull(exchange.current())
        pools.offerSolution(model, sample, 7.0, EvidenceCertificate.verified(model, EvidenceKind.Witness))

        assertEquals(7.0, exchange.current()?.objective)
    }
    @Test
    fun `direct engine publications pass the built-in pool source checker`() {
        val problem = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(Cardinality(intArrayOf(Lit.make(0, true)), min = 1, max = 1)),
        ).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(7))
        val worker = PortfolioBuilder.build(
            problem, PortfolioScenario(cores = 1, arms = 1, kind = Kind.COP, engine = EngineMix.BACKTRACK),
            objective = objective,
        ).single()

        worker.use {
            val pools = requireNotNull(it.sharedPools)
            val exchange = requireNotNull(pools.solutions)
            val model = ModelIdentity.of(problem, objective)
            val invocation = EngineInvocation(problem.prepareExecutionPlan(objective), model, objective)
            val falsePoint = Sample(booleanArrayOf(false), LongArray(0))
            val certificate = EvidenceCertificate.published(
                CompletedEnginePublication.Witness(invocation, falsePoint, 0.0))
            val rejected = pools.offerSolution(model, falsePoint, 0.0, certificate, invocation)
            assertIs<Publication.Rejected>(rejected)
            assertNull(exchange.current())
            exchange.offer(Sample(booleanArrayOf(true), LongArray(0)), 7.0)

            assertEquals(7.0, exchange.current()?.objective)
        }
    }
}
