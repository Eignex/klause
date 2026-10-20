package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective

/** One original real coordinate represented by a private integer bucket. */
class SourceFloatGridCoordinate internal constructor(
    /** Original real namespace coordinate. */
    val source: SourceRealId,
    /** Integer coordinate in [SourceFloatProjection.privateProblem]. */
    val bucketVariable: Int,
    /** Exact value at bucket zero. */
    val origin: SemanticRational,
    /** Exact increment between neighboring buckets. */
    val step: SemanticRational,
    /** Number of private bucket values. */
    val buckets: Int,
    /** Integer coordinate carrying the value used by every private relation. */
    val valueVariable: Int,
    /** Exact source value at value-carrier index zero. */
    val valueOrigin: SemanticRational,
    /** Exact source increment per value-carrier index. */
    val valueStep: SemanticRational,
    /** Number of values in the carrier, including conditional absent values. */
    val valueBuckets: Int,
)

/** Immutable correspondence for a selected approximation, without source proof authority. */
class SourceFloatProjection internal constructor(binding: SelectedFloatGridBinding) {
    /** Whether publication reports claims under the declared grid interpretation. */
    val declaredGridSemantics: Boolean = binding.consumer.declaredGridSemantics

    /** Original source attachment, whose real declarations remain authoritative. */
    val source: SourceAttachment = binding.consumer.invocation.source

    /** Borrowed read-only private model; its integer bucket IDs are not original integer IDs. */
    val privateProblem: Problem = binding.projection.problem

    /** Captured permission and private search window. */
    val policy: FloatSemantics.Grid = source.semantics.effective.floats as FloatSemantics.Grid

    private val capturedCoordinates = binding.projection.mapping.columns().map {
        val selector = it.selector()
        SourceFloatGridCoordinate(it.source, selector.coordinate.variable, selector.origin.semantic(),
            selector.step.semantic(), selector.coordinate.buckets, it.coordinate.variable,
            it.origin.semantic(), it.step.semantic(), it.coordinate.buckets)
    }
    private val origins = binding.projection.mapping.factorOrigins()

    /** Owned cross-kind coordinate descriptors. */
    val coordinates: List<SourceFloatGridCoordinate> get() = capturedCoordinates.toList()

    /** Original factor index for each private factor, in private factor order. */
    val factorOrigins: IntArray get() = origins.copyOf()

    /** Objective in original source coordinates and units. */
    val originalObjective: LinearObjective? = binding.objective?.original

    /** Positive multiplier from unnormalized native objective to original units. */
    val objectiveScale: SemanticRational? = binding.objective?.scale?.semantic()

    /** Original objective value at native objective zero. */
    val objectiveOffset: SemanticRational? = binding.objective?.offset?.semantic()

    /** Whether native minimization negates the projected source objective. */
    val maximize: Boolean = binding.consumer.invocation.request.maximize
}

/** Native private-grid result retained independently of publication claim qualification. */
class NativeGridOutcome internal constructor(
    /** Immutable namespace and value correspondence for this result. */
    val projection: SourceFloatProjection,
    /** Native outcome in the private grid frame, with its original claim scope. */
    val outcome: FiniteSolveOutcome,
)

private fun BigFraction.semantic(): SemanticRational = SemanticRational.of(num, den)
