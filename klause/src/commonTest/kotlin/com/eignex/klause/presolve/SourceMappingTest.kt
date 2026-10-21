package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.ValueSelection
import com.eignex.klause.ir.VariableSelection
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.linear.SingletonInequalityProjection
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.remapped
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceMappingTest {
    @Test
    fun `binary substitution composed with Boolean elimination should retain unrelated hints and source values`() {
        val source = Problem(2, 4,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(-2, 2), IntDomain(0, 2)),
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1), Abs(2, 3))).bake()
        val substitution = assertNotNull(BinaryColumnSubstitution.substitute(source, emptySet(), BakeConfig.NONE))
        val middle = substitution.problem
        val delta = BoundedVariableElimination.eliminate(middle)
        val target = middle.withPassDelta(delta, BakeConfig.NONE)
        val mapping = SourceMapping(source, middle, TransformationGuarantees.IDENTITY, substitution.rebuild)
            .then(SourceMapping(middle, target, PresolvePass.ELIMINATE_BOOL_VARS.guarantees, delta.rebuild))
        val reduced = Sample(booleanArrayOf(true, false, false, false), longArrayOf(0, 0, -2, 2))

        val coordinates = assertNotNull(mapping.retainedCoordinates())
        val search = assertNotNull(SearchHints(listOf(SearchGroup(intArrayOf(1, 0), intArrayOf(0, 3, 1, 2))))
            .remapped(coordinates, Cancellation.Never))
        val point = mapping.reconstructFrom(target, reduced)

        assertEquals(4, target.numBoolVars)
        assertEquals(2, point.numBoolVars)
        assertEquals(4, point.numIntVars)
        assertEquals(listOf(true, false), point.bools.toList())
        assertEquals(listOf(1L, 0L, -2L, 2L), point.ints.toList())
        assertEquals(listOf(1, 0), search.tiers.single().boolVars.toList())
        assertEquals(listOf(3, 2), search.tiers.single().intVars.toList())
        assertNull(DefinitionHint.IntegerReference(0).remap(coordinates))
        assertNull(DefinitionHint.IntegerReference(1).remap(coordinates))
        assertNotNull(DefinitionHint.Abs(3, DefinitionHint.Operand.Variable(2)).remap(coordinates))
        assertEquals(listOf(false, false), reduced.bools.drop(2))
        assertEquals(listOf(0L, 0L, -2L, 2L), reduced.ints.toList())
    }

    @Test
    fun `finite Boolean reductions should retain hints only for unchanged coordinates`() {
        for (pass in listOf(PresolvePass.ELIMINATE_BOOL_VARS, PresolvePass.ELIMINATE_BLOCKED_CLAUSES,
            PresolvePass.IMPLICATION_GRAPH)) {
            val clauses = if (pass == PresolvePass.IMPLICATION_GRAPH) listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
            ) else listOf(Clause(intArrayOf(Lit.make(1, true), Lit.make(0, true))))
            val source = Problem(3, 1, arrayOf(IntDomain(0, 3)), clauses).bake()
            val delta = when (pass) {
                PresolvePass.ELIMINATE_BOOL_VARS -> BoundedVariableElimination.eliminate(source, setOf(0, 2))
                PresolvePass.ELIMINATE_BLOCKED_CLAUSES -> BlockedClauseElimination.eliminate(source, setOf(0, 2))
                else -> ImplicationGraph.reduce(source, 3, Cancellation.Never)
            }
            val target = source.withPassDelta(delta, BakeConfig.NONE)
            val mapping = SourceMapping(source, target, pass.guarantees, delta.rebuild)

            val coordinates = assertNotNull(mapping.retainedCoordinates(), pass.id)
            val search = assertNotNull(SearchHints(listOf(SearchGroup(intArrayOf(1, 2, 0), intArrayOf(0),
                VariableSelection.InputOrder, ValueSelection.Maximum))).remapped(coordinates, Cancellation.Never))
            val point = mapping.reconstructFrom(target, Sample(
                booleanArrayOf(pass == PresolvePass.IMPLICATION_GRAPH, false, false), longArrayOf(2),
            ))

            assertEquals(listOf(2, 0), search.tiers.single().boolVars.toList(), pass.id)
            assertEquals(ValueSelection.Maximum, search.tiers.single().valueSelector)
            assertEquals(VariableSelection.InputOrder, search.tiers.single().varSelector)
            assertNull(DefinitionHint.BooleanReference(1).remap(coordinates), pass.id)
            assertEquals(2, assertNotNull(DefinitionHint.BooleanReference(2).remap(coordinates)).output)
            assertEquals(0, assertNotNull(DefinitionHint.IntegerReference(0).remap(coordinates)).output)
            assertTrue(point.boolValue(1), pass.id)
            assertEquals(3, point.numBoolVars)
            assertEquals(2L, point.intValue(0))
        }
    }

    @Test
    fun `finite singleton projection should retain hints for its surviving columns`() {
        val source = Problem(1, 3, arrayOf(IntDomain(2, 5), IntDomain(0, 8), IntDomain(0, 8)),
            listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10))).bake()
        val delta = SingletonInequalityProjection.project(source, setOf(1))
        val target = source.withPassDelta(delta, BakeConfig.NONE)
        val mapping = SourceMapping(source, target, PresolvePass.PROJECT_SINGLETON_INEQUALITIES.guarantees,
            delta.rebuild)

        val coordinates = assertNotNull(mapping.retainedCoordinates())
        val search = assertNotNull(SearchHints(listOf(SearchGroup(intArrayOf(0), intArrayOf(0, 2, 1))))
            .remapped(coordinates, Cancellation.Never))
        val point = mapping.reconstructFrom(target, Sample(booleanArrayOf(true), longArrayOf(5, 8, 4)))

        assertEquals(listOf(2, 1), search.tiers.single().intVars.toList())
        assertNull(DefinitionHint.IntegerReference(0).remap(coordinates))
        assertEquals(2, assertNotNull(DefinitionHint.IntegerReference(2).remap(coordinates)).output)
        assertEquals(listOf(2L, 8L, 4L), point.ints.toList())
    }

    @Test
    fun `comparison indicators should be reconstructed without direct hint correspondence`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val target = source.withFactors(emptyArray())
        val mapping = SourceMapping(source, target, PresolvePass.FOLD_COMPARISON_CLAUSES.guarantees,
            SourceRebuilds(listOf(RebuildStep.ComparisonValue(0, 0, LinearOp.EQ, 1))))
        val reduced = Sample(booleanArrayOf(false), longArrayOf(1))

        val point = mapping.reconstructFrom(target, reduced)
        val coordinates = assertNotNull(mapping.retainedCoordinates())

        assertTrue(point.boolValue(0))
        assertNull(DefinitionHint.BooleanReference(0).remap(coordinates))
        assertEquals(0, assertNotNull(DefinitionHint.IntegerReference(0).remap(coordinates)).output)
        assertTrue(mapping.guarantees.reconstructability)
        assertTrue(mapping.guarantees.projectedSolutions)
        assertFalse(mapping.guarantees.bijection)
    }

    @Test
    fun `source hints should exclude every coordinate reconstructed by a mapping chain`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 9) }, emptyArray())
        val middle = source.withFactors(emptyArray())
        val target = middle.withFactors(emptyArray())
        val first = SourceMapping(
            source,
            middle,
            TransformationGuarantees.IDENTITY,
            SourceRebuilds(
                listOf(
                    RebuildStep.CopyLiteral(0, 0),
                    RebuildStep.AffineValue(0, 1, intArrayOf(2), longArrayOf(1), 1),
                ),
            ),
        )
        val second = SourceMapping(
            middle,
            target,
            TransformationGuarantees.IDENTITY,
            SourceRebuilds(
                listOf(
                    RebuildStep.QuotientValue(
                        1,
                        1,
                        intArrayOf(2),
                        longArrayOf(1),
                        1,
                        true,
                        null,
                    ),
                ),
            ),
        )

        val coordinates = assertNotNull(first.then(second).retainedCoordinates())

        assertNull(DefinitionHint.BooleanReference(0).remap(coordinates))
        assertNull(DefinitionHint.IntegerReference(0).remap(coordinates))
        assertNull(DefinitionHint.IntegerReference(1).remap(coordinates))
        assertEquals(2, assertNotNull(DefinitionHint.IntegerReference(2).remap(coordinates)).output)
    }

    @Test
    fun `an opaque reconstruction should not confer a direct hint correspondence`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val target = source.withFactors(emptyArray())
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY, lift = { it })

        assertNull(mapping.retainedCoordinates())
    }

    @Test
    fun `retained coordinate capture should stop on its containing work allowance`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 2) }, emptyArray())
        val mapping = SourceMapping(source, source, TransformationGuarantees.IDENTITY)
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val coordinates = mapping.retainedCoordinates(stop)

        assertNull(coordinates)
        assertEquals(2L, charged)
    }

    @Test
    fun `literal reconstruction hint projection should stop at its rebuild work boundary`() {
        val source = Problem(1, 2, Array(2) { IntDomain(0, 1) }, emptyArray())
        val target = Problem(3, 2, Array(2) { IntDomain(0, 1) }, emptyArray())
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY,
            SourceRebuilds(listOf(RebuildStep.IntegerFromLiteral(0, Lit.make(1, true))), sourceBoolVars = 1))
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 5
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val coordinates = mapping.retainedCoordinates(stop)

        assertNull(coordinates)
        assertEquals(5L, charged)
    }

    @Test
    fun `composition should reconstruct in reverse elimination order`() {
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
    fun `a chain should reject an unrelated middle model with the same shape`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray())
        val middle = source.withFactors(emptyArray())
        val unrelated = source.withFactors(emptyArray())
        val first = SourceMapping(source, middle, TransformationGuarantees.IDENTITY)
        val second = SourceMapping(unrelated, unrelated, TransformationGuarantees.IDENTITY)

        assertFailsWith<IllegalArgumentException> { first.then(second) }
    }

    @Test
    fun `reconstruction should reject a witness associated with another reduced model`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray())
        val target = source.withFactors(emptyArray())
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY)

        assertFailsWith<IllegalArgumentException> {
            mapping.reconstructFrom(source, Sample(BooleanArray(0), LongArray(0)))
        }
    }

    @Test
    fun `objective adjustments should compose from reduced units into source units`() {
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
    fun `objective adjustments should not reverse minimization order`() {
        assertFailsWith<IllegalArgumentException> { ObjectiveAdjustment(BigFraction.ofLong(-1)) }
    }

    @Test
    fun `objective verification should reject an inconsistent adjustment`() {
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
    fun `sensitive queries should refuse multiplicity-changing explicit overrides`() {
        val config = PresolveConfig.parse("all")
        val context = PresolveContext(solutionSetSensitive = true)

        val passes = config.problemPasses(context)

        assertTrue(passes.all { it.guarantees.bijection })
        assertFalse(config.resolved(PresolvePass.ELIMINATE_AFFINE_SINGLETONS, context))
    }

    @Test
    fun `satisfiability-only reductions should not claim projected source coverage`() {
        val affine = PresolvePass.ELIMINATE_AFFINE_SINGLETONS.guarantees
        val bve = PresolvePass.ELIMINATE_BOOL_VARS.guarantees

        val chain = affine.then(bve)

        assertTrue(chain.satisfiability)
        assertTrue(chain.reconstructability)
        assertFalse(chain.projectedSolutions)
    }

    @Test
    fun `source mapping should reconstruct arbitrary precision coordinates without a finite projection`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                LongArray(2),
                LongArray(2),
                Bits(2).also {
                    it.set(0)
                    it.set(1)
                },
                Bits(2).also {
                    it.set(0)
                    it.set(1)
                },
            ),
            factors = arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
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
    fun `reconstruction should not promote a reduced witness certificate into source authority`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray())
        val target = source.withFactors(emptyArray())
        val mapping = SourceMapping(source, target, TransformationGuarantees.IDENTITY)
        val sample = Sample(BooleanArray(0), LongArray(0))
        val runtime = EngineInvocation(target.prepareExecutionPlan(), ModelIdentity.of(target))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(runtime, sample, null))
        sample.witnessCertificate = certificate

        val reconstructed = mapping.reconstructFrom(target, sample)

        assertNull(reconstructed.witnessCertificate)
        assertSame(certificate, sample.witnessCertificate)
    }
}
