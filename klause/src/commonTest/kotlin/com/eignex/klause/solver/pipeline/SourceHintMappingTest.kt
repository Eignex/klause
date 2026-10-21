package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.ValueSelection
import com.eignex.klause.ir.VariableSelection
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.presolve.retainedCoordinates
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceHintMappingTest {
    @Test
    fun `ordered minimum and maximum tiers should follow translated aliases through mapping stages`() {
        val source = Problem(0, 3, Array(3) { IntDomain(-10, 10) }, emptyArray())
        val middle = Problem(0, 3, source.finiteIntDomains(), emptyArray())
        val target = Problem(0, 3, source.finiteIntDomains(), emptyArray())
        val first = SourceMapping(source, middle, TransformationGuarantees.IDENTITY,
            SourceRebuilds(listOf(RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(1), 1))))
        val second = SourceMapping(middle, target, TransformationGuarantees.IDENTITY,
            SourceRebuilds(listOf(RebuildStep.AffineValue(1, -2, intArrayOf(2), longArrayOf(-1), -1))))
        val mapping = first.then(second)
        val hints = SearchHints(listOf(
            SearchGroup(intArrayOf(), intArrayOf(0, 2, 1)),
            SearchGroup(intArrayOf(), intArrayOf(1), VariableSelection.InputOrder, ValueSelection.Maximum),
        ), solutionGuided = true)

        val projected = assertNotNull(hints.remapped(mapping, Cancellation.Never))

        assertEquals(listOf(2, 2, 2), projected.tiers[0].intVars.toList())
        assertEquals(listOf(2), projected.tiers[1].intVars.toList())
        assertEquals(VariableSelection.InputOrder, projected.tiers[1].varSelector)
        assertEquals(ValueSelection.Maximum, projected.tiers[1].valueSelector)
        assertEquals(true, projected.solutionGuided)
        assertEquals(-1, assertNotNull(mapping.retainedCoordinates()).int(0))
    }

    @Test
    fun `search projection should exclude affine rebuilds that are not integer translations`() {
        val source = Problem(0, 3, Array(3) { IntDomain(-10, 10) }, emptyArray())
        val rebuilds = listOf(
            RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(-1), 1),
            RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(2), 1),
            RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(2), 2),
            RebuildStep.AffineValue(0, 1, intArrayOf(1, 2), longArrayOf(1, 1), 1),
        )
        val hints = SearchHints(listOf(SearchGroup(intArrayOf(), intArrayOf(0, 2))))
        for (rebuild in rebuilds) {
            val mapping = SourceMapping(source, source, TransformationGuarantees.IDENTITY,
                SourceRebuilds(listOf(rebuild)))

            val projected = assertNotNull(hints.remapped(mapping, Cancellation.Never))

            assertEquals(listOf(2), projected.tiers.single().intVars.toList())
        }
    }

    @Test
    fun `source bound and midpoint preferences should keep only direct coordinates`() {
        val source = Problem(0, 2, Array(2) { IntDomain(-10, 10) }, emptyArray())
        val mapping = SourceMapping(source, source, TransformationGuarantees.IDENTITY,
            SourceRebuilds(listOf(RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(1), 1))))
        val tiers = listOf(
            SearchGroup(intArrayOf(), intArrayOf(0, 1), VariableSelection.SmallestLowerBound),
            SearchGroup(intArrayOf(), intArrayOf(0, 1), VariableSelection.LargestUpperBound),
        ) + ValueSelection.entries.filter { it !in setOf(ValueSelection.Minimum, ValueSelection.Maximum) }.map {
            SearchGroup(intArrayOf(), intArrayOf(0, 1), valueSelector = it)
        }

        val projected = assertNotNull(SearchHints(tiers).remapped(mapping, Cancellation.Never))

        assertEquals(List(tiers.size) { listOf(1) }, projected.tiers.map { it.intVars.toList() })
    }

    @Test
    fun `opaque reconstruction and cancellation should withhold search projection`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val hints = SearchHints(listOf(SearchGroup(intArrayOf(), intArrayOf(0))))
        val opaque = SourceMapping(source, source, TransformationGuarantees.IDENTITY, lift = { it })
        val direct = SourceMapping(source, source, TransformationGuarantees.IDENTITY)

        assertNull(hints.remapped(opaque, Cancellation.Never))
        assertNull(hints.remapped(direct, Cancellation { true }))
    }
}
