package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shared cross-engine incumbent port (#644) both [LocalSearchSolver] and
 * [com.eignex.klause.meta.alns.Alns] drive: publication reaches the exchange and nowhere else, and an
 * import is adopted only when its version is new and it is strictly better — inert under assumption
 * pins or without an exchange.
 */
class PooledIncumbentsTest {

    private val sample = Sample(booleanArrayOf(true), LongArray(0))
    private val other = Sample(booleanArrayOf(false), LongArray(0))

    private fun standing(assignment: Sample, objective: Double) =
        IncumbentExchange.minimizing<Sample>().apply { offer(assignment, objective) }

    private fun port(exchange: IncumbentExchange<Sample, Double>?, enabled: Boolean = true, score: Double = 1.0) =
        PooledIncumbents(exchange, importEnabled = enabled, evaluate = { score })

    @Test
    fun `adopts a fresh strictly-better published solution`() {
        val port = port(standing(sample, 5.0), score = 5.0)
        assertEquals(PooledIncumbents.Adoption(sample, 5.0), port.poll(currentBest = 10.0))
    }

    @Test
    fun `rejects a published solution no better than the current best`() {
        val port = port(standing(sample, 10.0), score = 10.0)
        assertNull(port.poll(currentBest = 10.0), "equal objective is not strictly better")
    }

    @Test
    fun `imports one published version only once`() {
        val port = port(standing(sample, 1.0), score = 1.0)
        assertEquals(1.0, port.poll(10.0)?.objective)
        assertNull(port.poll(10.0), "version-gated: the same incumbent is not re-adopted")
    }

    @Test
    fun `imports the next published version`() {
        val exchange = standing(sample, 5.0)
        val port = PooledIncumbents(exchange, importEnabled = true, evaluate = { exchange.current()!!.objective })
        port.poll(10.0)
        exchange.offer(sample, 4.0)
        assertEquals(4.0, port.poll(10.0)?.objective, "a new version of the same assignment is fresh")
    }

    @Test
    fun `scores the imported assignment with the caller's own objective view`() {
        val port = port(standing(sample, 5.0), score = 2.0)
        assertEquals(2.0, port.poll(3.0)?.objective, "the publisher's score need not agree with the caller's")
    }

    @Test
    fun `is inert when import is disabled`() {
        assertNull(port(standing(sample, 1.0), enabled = false).poll(10.0), "a pin-guarded port adopts nothing")
    }

    @Test
    fun `is inert without an exchange`() {
        val port = port(exchange = null)
        port.publish(sample, 7.0)
        assertNull(port.poll(10.0), "with nothing to publish into there is nothing to import from")
    }

    @Test
    fun `publishes a candidate into the exchange`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        port(exchange).publish(sample, 7.0)
        val installed = assertNotNull(exchange.current(), "the offered candidate becomes the incumbent")
        assertEquals(7.0, installed.objective)
    }

    @Test
    fun `only strict improvements are installed and each advances the version`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        val port = port(exchange)
        port.publish(sample, 7.0)
        port.publish(other, 7.0)
        port.publish(other, 9.0)
        port.publish(other, 4.0)
        val standing = assertNotNull(exchange.current())
        assertEquals(4.0, standing.objective, "the equal and the worse candidate never displace the incumbent")
        assertEquals(2L, standing.version, "only the two installations advance the version sequence")
    }

    @Test
    fun `a candidate the exchange refutes is not installed`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        port(exchange).publish(sample, Double.NaN)
        assertNull(exchange.current(), "a non-finite objective is refuted by the exchange's verifier")
    }
}
