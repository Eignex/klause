package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ObjectiveCoordinateViewTest {
    @Test
    fun `private reconstruction should check canonical proposals without changing its input`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 5), IntDomain(0, 6)),
            listOf(Linear(longArrayOf(1, -1), intArrayOf(1, 0), LinearOp.EQ, 1)))
        val canonical = source.bake()
        val privateProblem = Problem(0, 2, arrayOf(IntDomain(0, 5), IntDomain(0, 6)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 2))
        val preparation = FinitePipelinePreparation(canonical, objective, { it }, null).also {
            it.sourceMapping = SourceMapping(source, canonical, TransformationGuarantees.IDENTITY)
        }
        val mapping = SourceMapping(source, privateProblem, TransformationGuarantees.IDENTITY,
            SourceRebuilds(listOf(RebuildStep.AffineValue(1, 1, intArrayOf(0), longArrayOf(1), 1))))
        val coordinates = FinitePipelinePreparation(privateProblem,
            LinearObjective(intCoefficients = longArrayOf(1, 0), constant = 1),
            { mapping.reconstructFrom(privateProblem, it) }, null).also { it.sourceMapping = mapping }
        val view = assertNotNull(ObjectiveCoordinateView.create(preparation, coordinates, null, Cancellation.Never))
        val cases = listOf(
            Triple(3L, listOf(3L, 4L), 8.0),
            Triple(Long.MAX_VALUE, null, null),
        )
        for ((value, expected, score) in cases) {
            val sample = Sample(booleanArrayOf(), longArrayOf(value, 0))

            val result = view.proposal(sample)

            assertEquals(expected, result?.sample?.ints?.toList())
            assertEquals(score, result?.objective)
            assertEquals(listOf(value, 0L), sample.ints.toList())
        }
    }

    @Test
    fun `private proposals should be checked against canonical constraints and objective`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val canonical = Problem(0, 1, arrayOf(IntDomain(0, 5)),
            listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, 2))).bake()
        val privateProblem = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(2))
        val preparation = FinitePipelinePreparation(canonical, objective, { it }, null).also {
            it.sourceMapping = SourceMapping(source, canonical, TransformationGuarantees.IDENTITY)
        }
        val coordinates = FinitePipelinePreparation(privateProblem,
            LinearObjective(intCoefficients = longArrayOf(1)), { it }, null).also {
            it.sourceMapping = SourceMapping(source, privateProblem, TransformationGuarantees.IDENTITY)
        }
        val view = assertNotNull(ObjectiveCoordinateView.create(preparation, coordinates, null, Cancellation.Never))

        for ((value, expected) in listOf(0L to null, 3L to 6.0)) {
            val result = view.proposal(Sample(booleanArrayOf(), longArrayOf(value)))

            assertEquals(expected, result?.objective)
        }
    }

    @Test
    fun `equal sized models should not establish a common source mapping`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val foreign = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val canonical = source.bake()
        val privateProblem = foreign.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val preparation = FinitePipelinePreparation(canonical, objective, { it }, null).also {
            it.sourceMapping = SourceMapping(source, canonical, TransformationGuarantees.IDENTITY)
        }
        val coordinates = FinitePipelinePreparation(privateProblem, objective, { it }, null).also {
            it.sourceMapping = SourceMapping(foreign, privateProblem, TransformationGuarantees.IDENTITY)
        }

        val view = ObjectiveCoordinateView.create(preparation, coordinates, null, Cancellation.Never)

        assertNull(view)
    }
}
