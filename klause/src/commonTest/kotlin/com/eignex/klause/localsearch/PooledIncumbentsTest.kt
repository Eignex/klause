package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PooledIncumbentsTest {

    private val sample = Sample(booleanArrayOf(true), LongArray(0))

    private fun standing(assignment: Sample, objective: Double) =
        IncumbentExchange.minimizing<Sample>().apply { offer(assignment, objective) }

    private fun port(exchange: IncumbentExchange<Sample, Double>?, enabled: Boolean = true, score: Double = 1.0) =
        PooledIncumbents(exchange, importEnabled = enabled, evaluate = { score })

    @Test
    fun `pooled incumbents should adopt a fresh strictly-better published solution`() {
        val port = port(standing(sample, 5.0), score = 5.0)
        assertEquals(PooledIncumbents.Adoption(sample, 5.0), port.poll(currentBest = 10.0))
    }

    @Test
    fun `pooled incumbents should reject a published solution no better than the current best`() {
        val port = port(standing(sample, 10.0), score = 10.0)
        assertNull(port.poll(currentBest = 10.0))
    }

    @Test
    fun `pooled incumbents should import one published version only once`() {
        val port = port(standing(sample, 1.0), score = 1.0)
        assertEquals(1.0, port.poll(10.0)?.objective)
        assertNull(port.poll(10.0))
    }

    @Test
    fun `pooled incumbents should import the next published version`() {
        val exchange = standing(sample, 5.0)
        val port = PooledIncumbents(exchange, importEnabled = true, evaluate = { exchange.current()!!.objective })
        port.poll(10.0)
        exchange.offer(sample, 4.0)
        assertEquals(4.0, port.poll(10.0)?.objective)
    }

    @Test
    fun `pooled incumbents should score the imported assignment with the caller's own objective view`() {
        val port = port(standing(sample, 5.0), score = 2.0)
        assertEquals(2.0, port.poll(3.0)?.objective)
    }

    @Test
    fun `pooled incumbents should be inert when import is disabled`() {
        assertNull(port(standing(sample, 1.0), enabled = false).poll(10.0))
    }

    @Test
    fun `pooled incumbents should be inert without an exchange`() {
        val port = port(exchange = null)
        port.publish(sample, 7.0)
        assertNull(port.poll(10.0))
    }

    @Test
    fun `pooled incumbents should publish a candidate into the exchange`() {
        val exchange = IncumbentExchange.minimizing<Sample>()
        port(exchange).publish(sample, 7.0)
        val installed = assertNotNull(exchange.current())
        assertEquals(7.0, installed.objective)
    }
}
