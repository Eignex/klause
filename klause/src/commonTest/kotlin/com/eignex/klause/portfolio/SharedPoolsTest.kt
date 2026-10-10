package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Publication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SharedPoolsTest {
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
            val exchange = requireNotNull(it.sharedPools?.solutions)
            val rejected = exchange.offer(Sample(booleanArrayOf(false), LongArray(0)), 0.0)
            assertIs<Publication.Rejected>(rejected)
            assertNull(exchange.current())
            exchange.offer(Sample(booleanArrayOf(true), LongArray(0)), 7.0)

            assertEquals(7.0, exchange.current()?.objective)
        }
    }
}
