package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.IncumbentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shared cross-engine import gate (#644) both [LocalSearchSolver] and
 * [com.eignex.klause.meta.alns.Alns] poll: adopt a published assignment only when its version is new and it
 * is strictly better, stay inert under assumption pins or without a source.
 */
class PooledSolutionImporterTest {

    private val sample = Sample(booleanArrayOf(true), LongArray(0))

    private fun standing(assignment: Sample, objective: Double) =
        IncumbentExchange.minimizing<Sample>().apply { offer(assignment, objective) }

    @Test
    fun `adopts a fresh strictly-better published solution`() {
        val importer = PooledSolutionImporter(standing(sample, 5.0), enabled = true, evaluate = { 5.0 })
        assertEquals(PooledSolutionImporter.Adoption(sample, 5.0), importer.poll(currentBest = 10.0))
    }

    @Test
    fun `rejects a published solution no better than the current best`() {
        val importer = PooledSolutionImporter(standing(sample, 10.0), enabled = true, evaluate = { 10.0 })
        assertNull(importer.poll(currentBest = 10.0), "equal objective is not strictly better")
    }

    @Test
    fun `imports one published version only once`() {
        val importer = PooledSolutionImporter(standing(sample, 1.0), enabled = true, evaluate = { 1.0 })
        assertEquals(1.0, importer.poll(10.0)?.objective)
        assertNull(importer.poll(10.0), "version-gated: the same incumbent is not re-adopted")
    }

    @Test
    fun `imports the next published version`() {
        val exchange = standing(sample, 5.0)
        val importer = PooledSolutionImporter(exchange, enabled = true, evaluate = { exchange.current()!!.objective })
        importer.poll(10.0)
        exchange.offer(sample, 4.0)
        assertEquals(4.0, importer.poll(10.0)?.objective, "a new version of the same assignment is fresh")
    }

    @Test
    fun `scores the imported assignment with the caller's own objective view`() {
        val importer = PooledSolutionImporter(standing(sample, 5.0), enabled = true, evaluate = { 2.0 })
        assertEquals(2.0, importer.poll(3.0)?.objective, "the publisher's score need not agree with the caller's")
    }

    @Test
    fun `is inert when disabled and never reads the exchange`() {
        var reads = 0
        val exchange = standing(sample, 1.0)
        val source = IncumbentSource {
            reads++
            exchange.current()
        }
        assertNull(PooledSolutionImporter(source, enabled = false, evaluate = { 1.0 }).poll(10.0))
        assertEquals(0, reads, "a pin-guarded importer never reads the exchange")
    }

    @Test
    fun `is inert without a source`() {
        assertNull(PooledSolutionImporter(source = null, enabled = true, evaluate = { 1.0 }).poll(10.0))
    }
}
