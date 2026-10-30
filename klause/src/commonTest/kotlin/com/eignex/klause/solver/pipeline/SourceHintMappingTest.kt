package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.ValueSelection
import com.eignex.klause.ir.VariableSelection
import com.eignex.klause.localsearch.CapturedBooleanDefinition
import com.eignex.klause.presolve.PresolveBudget
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
    fun `comparison projection should preserve strict translated predicate truth`() {
        val hint = DefinitionHint.Comparison(0, longArrayOf(2, -1, 1), arrayOf(
            DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1),
            DefinitionHint.Operand.Constant(5),
        ), 10, LinearOp.LE, strict = true)
        val reconstruction = AffineReconstruction(3, intArrayOf(1, 2), longArrayOf(2, -1), 1)
        val projected = CapturedBooleanDefinition(assertNotNull(
            hint.reconstructed(mapOf(0 to reconstruction), Cancellation.Never),
        ))

        for ((y, z) in listOf(1L to 2L, 0L to 1L, 1L to 1L)) {
            val truth = projected.holds { id -> when (id) {
                1 -> y
                2 -> z
                else -> error("a retained input is required")
            } }

            assertEquals(2 * (3 + 2 * y - z) - y + 5 < 10, truth)
        }
    }

    @Test
    fun `comparison projection should decline overflowing reconstructed coefficients`() {
        val hint = DefinitionHint.Comparison(0, longArrayOf(Long.MAX_VALUE),
            arrayOf(DefinitionHint.Operand.Variable(0)), 0, LinearOp.LE)
        val reconstruction = AffineReconstruction(0, intArrayOf(1), longArrayOf(2), 1)

        val projected = hint.reconstructed(mapOf(0 to reconstruction), Cancellation.Never)

        assertNull(projected)
    }

    @Test
    fun `comparison projection should withhold an interrupted expansion`() {
        val hint = DefinitionHint.Comparison(0, longArrayOf(1),
            arrayOf(DefinitionHint.Operand.Variable(0)), 0, LinearOp.LE)
        val reconstruction = AffineReconstruction(0, intArrayOf(1, 2), longArrayOf(1, 1), 1)
        val budget = PresolveBudget(2)

        val projected = hint.reconstructed(mapOf(0 to reconstruction), budget.orSpent(Cancellation.Never))

        assertNull(projected)
    }

    @Test
    fun `comparison projection should withhold a divided input`() {
        val hint = DefinitionHint.Comparison(0, longArrayOf(1),
            arrayOf(DefinitionHint.Operand.Variable(0)), 0, LinearOp.LE)
        val reconstruction = AffineReconstruction(0, intArrayOf(1), longArrayOf(1), 2)

        val projected = hint.reconstructed(mapOf(0 to reconstruction), Cancellation.Never)

        assertNull(projected)
    }

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
    fun `linear definitions should substitute divided reconstructions and pass a rebuilt output to its partner`() {
        val source = Problem(0, 3, Array(3) { IntDomain(-10, 10) }, emptyArray())
        val mapping = SourceMapping(source, source, TransformationGuarantees.IDENTITY,
            SourceRebuilds(listOf(RebuildStep.AffineValue(1, 1, intArrayOf(2), longArrayOf(3), 2))))
        val reconstructions = assertNotNull(mapping.affineReconstructions(Cancellation.Never))
        val row = arrayOf<DefinitionHint.Operand>(
            DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1),
        )
        for ((output, expected) in listOf(0 to 0, 1 to 2)) {
            val hint = DefinitionHint.Linear(output, longArrayOf(1, -1), row, 3)

            val rebuilt = assertNotNull(hint.reconstructed(reconstructions))

            assertEquals(expected, rebuilt.output)
            assertEquals(listOf(0, 2), rebuilt.inputs.map { (it as DefinitionHint.Operand.Variable).id })
            assertEquals(listOf(2L, -3L), rebuilt.coefficients.toList())
            assertEquals(7L, rebuilt.bound)
        }
    }

    @Test
    fun `linear definitions should decline an output the reconstruction cancels`() {
        val source = Problem(0, 2, Array(2) { IntDomain(-10, 10) }, emptyArray())
        val mapping = SourceMapping(source, source, TransformationGuarantees.IDENTITY,
            SourceRebuilds(listOf(RebuildStep.AffineValue(1, 2, intArrayOf(0), longArrayOf(1), 1))))
        val hint = DefinitionHint.Linear(0, longArrayOf(1, -1),
            arrayOf(DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), -2)

        assertNull(hint.reconstructed(assertNotNull(mapping.affineReconstructions(Cancellation.Never))))
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
