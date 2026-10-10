package com.eignex.klause.presolve

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceMappingTest {
    @Test
    fun `composition reconstructs in reverse elimination order`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 9) }, emptyArray())
        val middle = source.withFactors(emptyArray())
        val target = middle.withFactors(emptyArray())
        val first = SourceMapping(
            source,
            middle,
            PresolvePass.ELIMINATE_AFFINE_SINGLETONS.guarantees,
            SourceRebuilds(listOf(RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(1), 1))),
        )
        val second = SourceMapping(
            middle,
            target,
            PresolvePass.ELIMINATE_AFFINE_SINGLETONS.guarantees,
            SourceRebuilds(listOf(RebuildStep.AffineValue(1, 2, intArrayOf(2), longArrayOf(1), 1))),
        )

        val chain = first.then(second)
        val reconstructed = chain.reconstructFrom(target, Sample(BooleanArray(0), longArrayOf(0, 0, 3)))

        assertEquals(listOf(6L, 5L, 3L), reconstructed.ints.toList())
        assertTrue(chain.guarantees.projectedSolutions)
        assertFalse(chain.guarantees.bijection)
    }

    @Test
    fun `a chain rejects an unrelated middle model with the same shape`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray())
        val middle = source.withFactors(emptyArray())
        val unrelated = source.withFactors(emptyArray())
        val first = SourceMapping(source, middle, TransformationGuarantees.IDENTITY)
        val second = SourceMapping(unrelated, unrelated, TransformationGuarantees.IDENTITY)

        assertFailsWith<IllegalArgumentException> { first.then(second) }
    }

    @Test
    fun `reconstruction rejects a witness associated with another reduced model`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray())
        val target = source.withFactors(emptyArray())
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY)

        assertFailsWith<IllegalArgumentException> {
            mapping.reconstructFrom(source, Sample(BooleanArray(0), LongArray(0)))
        }
    }

    @Test
    fun `objective adjustments compose from reduced units into source units`() {
        val first = ObjectiveAdjustment(BigFraction.of(bigIntOf(1), bigIntOf(2)), BigFraction.ofLong(3))
        val second = ObjectiveAdjustment(BigFraction.of(bigIntOf(5), bigIntOf(3)), BigFraction.ofLong(7))

        val composed = first.then(second)

        assertEquals(BigFraction.of(bigIntOf(47), bigIntOf(3)), composed.sourceValue(BigFraction.ofLong(11)))
        assertEquals(
            first.sourceValue(second.sourceValue(BigFraction.ofLong(11))),
            composed.sourceValue(BigFraction.ofLong(11)),
        )
    }

    @Test
    fun `objective adjustments cannot reverse minimization order`() {
        assertFailsWith<IllegalArgumentException> { ObjectiveAdjustment(BigFraction.ofLong(-1)) }
    }

    @Test
    fun `objective verification rejects an inconsistent adjustment`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val mapping = SourceMapping(
            model,
            model,
            TransformationGuarantees.IDENTITY,
            objectiveAdjustment = ObjectiveAdjustment(offset = BigFraction.ONE),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(3))

        assertFailsWith<IllegalArgumentException> {
            mapping.requireObjectivePreserved(objective, objective, Sample(BooleanArray(0), longArrayOf(2)))
        }
    }

    @Test
    fun `sensitive queries refuse multiplicity changing explicit overrides`() {
        val config = PresolveConfig.parse("all")
        val context = PresolveContext(solutionSetSensitive = true)

        val passes = config.problemPasses(context)

        assertTrue(passes.all { it.guarantees.bijection })
        assertFalse(config.resolved(PresolvePass.ELIMINATE_AFFINE_SINGLETONS, context))
    }

    @Test
    fun `satisfiability only reductions do not claim projected source coverage`() {
        val affine = PresolvePass.ELIMINATE_AFFINE_SINGLETONS.guarantees
        val bve = PresolvePass.ELIMINATE_BOOL_VARS.guarantees

        val chain = affine.then(bve)

        assertTrue(chain.satisfiability)
        assertTrue(chain.reconstructability)
        assertFalse(chain.projectedSolutions)
        assertFalse(PresolvePass.FOLD_COMPARISON_CLAUSES.guarantees.reconstructability)
    }

    @Test
    fun `source mapping reconstructs arbitrary precision coordinates without a finite projection`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 9) }, emptyArray())
        val target = source.withFactors(emptyArray())
        val rebuild = SourceRebuilds(listOf(RebuildStep.AffineValue(0, 1, intArrayOf(1), longArrayOf(1), 1)))
        val mapping = SourceMapping(source, target, PresolvePass.ELIMINATE_AFFINE_SINGLETONS.guarantees, rebuild)
        val wide = BIG_ONE shl 70
        val sample = Sample(BooleanArray(0), LongArray(0), exactInts = listOf(BIG_ZERO, wide))

        val reconstructed = mapping.reconstructFrom(target, sample)

        assertEquals(listOf(wide + BIG_ONE, wide), reconstructed.exactInts)
        assertEquals(listOf(BIG_ZERO, wide), sample.exactInts)
    }

    @Test
    fun `reconstruction cannot promote a reduced witness certificate into source authority`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray())
        val target = source.withFactors(emptyArray())
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY)
        val certificate = EvidenceCertificate.verified(ModelIdentity.of(target), EvidenceKind.Witness)
        val sample = Sample(BooleanArray(0), LongArray(0)).also { it.witnessCertificate = certificate }

        val reconstructed = mapping.reconstructFrom(target, sample)

        assertNull(reconstructed.witnessCertificate)
        assertSame(certificate, sample.witnessCertificate)
    }
}
