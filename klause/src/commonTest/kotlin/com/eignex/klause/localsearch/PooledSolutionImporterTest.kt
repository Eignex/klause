package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shared cross-engine import gate (#644) both [LocalSearchSolver] and
 * [com.eignex.klause.meta.alns.Alns] poll: adopt a pooled assignment only when it is fresh and strictly
 * better, stay inert under assumption pins or without a supplier.
 */
class PooledSolutionImporterTest {

    private val sample = Sample(booleanArrayOf(true), LongArray(0))

    @Test
    fun `adopts a fresh strictly-better pooled solution`() {
        val importer = PooledSolutionImporter(supplier = { sample }, enabled = true, evaluate = { 5.0 })
        assertEquals(PooledSolutionImporter.Adoption(sample, 5.0), importer.poll(currentBest = 10.0))
    }

    @Test
    fun `rejects a pooled solution no better than the current best`() {
        val importer = PooledSolutionImporter(supplier = { sample }, enabled = true, evaluate = { 10.0 })
        assertNull(importer.poll(currentBest = 10.0), "equal objective is not strictly better")
    }

    @Test
    fun `imports the same pooled sample only once`() {
        val importer = PooledSolutionImporter(supplier = { sample }, enabled = true, evaluate = { 1.0 })
        assertEquals(1.0, importer.poll(10.0)?.objective)
        assertNull(importer.poll(10.0), "identity-gated: the same sample is not re-adopted")
    }

    @Test
    fun `is inert when disabled and never consults the supplier`() {
        var polls = 0
        val importer = PooledSolutionImporter(
            supplier = {
                polls++
                sample
            },
            enabled = false,
            evaluate = { 1.0 },
        )
        assertNull(importer.poll(10.0))
        assertEquals(0, polls, "a pin-guarded importer never consults the supplier")
    }

    @Test
    fun `is inert without a supplier`() {
        assertNull(PooledSolutionImporter(supplier = null, enabled = true, evaluate = { 1.0 }).poll(10.0))
    }
}
