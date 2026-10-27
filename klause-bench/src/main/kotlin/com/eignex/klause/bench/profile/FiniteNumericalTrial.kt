package com.eignex.klause.bench.profile

import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.field
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class FiniteNumericalTrial(
    private val problem: BakedProblem,
    private val objective: LinearObjective,
    private val sense: ObjectiveSense,
    workLimit: Long,
    private val stop: Cancellation,
) : AutoCloseable {
    private val fundingType = Class.forName("com.eignex.klause.lp.SourceLpFunding")
    private val funding = fundingType.getDeclaredConstructor(Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType).newInstance(workLimit, 8L * 1024 * 1024)
    private var budget: Any? = null
    private var authority: Any? = null
    private var publisher: Any? = null
    private var query: SourceProofContext? = null
    private var feedback: Any? = null
    private var support: Any? = null
    private var cutoffRecord: Any? = null
    var selector: Any? = null
        private set
    var recovered: Sample? = null
        private set
    var rawPointNativeAccepted: Boolean? = null
        private set
    var rawPointDisposition = "unavailable"
        private set
    var unavailableReason: String? = null
        private set
    var outcome = "unavailable"
        private set

    init { fundingType.getMethod("grant", Long::class.javaPrimitiveType).invoke(funding, workLimit) }

    val work: Long get() = field(funding, "spent") as Long
    val allocated: Long get() = field(funding, "allocated") as Long

    fun acquire(center: Sample, pins: Assumptions, arm: String, cuts: Boolean,
        checkSource: (Sample, Cancellation) -> Boolean,
    ) {
        fundingType.getMethod("charge", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(funding, 1L + problem.numIntVars + problem.numFactors, 64L)
        val originalObjective = if (sense == ObjectiveSense.MAXIMIZE) objective.negated() else objective
        val sourceMethod = Class.forName("com.eignex.klause.solver.pipeline.LinearSourceBindingKt").methods
            .single { it.name == "linearAuthorityContext" && it.parameterCount == 5 }
        val source = sourceMethod.invoke(null, problem, originalObjective, sense, stop, false) as SourceProofContext?
        if (source == null || stop()) {
            unavailableReason = if (stop()) "cancelled-source-preparation" else "unsupported-affine-frame"
            return
        }
        val entries = source.coordinates.size.toLong() + source.premises.values.sumOf {
            1L + it.inequality.coefficients.size + it.guards.size + it.integerPins.size
        } + source.objective.size
        fundingType.getMethod("charge", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(funding, entries, 64L + entries * 48L)
        val scoped = Class.forName("com.eignex.klause.lp.optimization.LinearApproachKt").methods
            .single { it.name == "atScope" }
            .invoke(null, source, source.scope.copy(condition = pins.sourceCondition())) as SourceProofContext
        val cutoffType = Class.forName("com.eignex.klause.solver.pipeline.SourceNumericalCutoff")
        val minimizingValue = objective.evaluateExact(center)
        val value = if (sense == ObjectiveSense.MAXIMIZE) minimizingValue.negated() else minimizingValue
        val cutoff = cutoffType.getDeclaredConstructor(BigFraction::class.java, String::class.java)
            .newInstance(value, "finite-repair-improvement")
        cutoffRecord = cutoff
        query = cutoffType.methods.single { it.name == "applyTo" }.invoke(cutoff, scoped, funding, stop)
            as SourceProofContext?
        val prepared = query
        if (prepared == null || stop()) {
            unavailableReason = "cutoff-preparation-declined"
            return
        }
        val budgetType = Class.forName("com.eignex.klause.lp.SourceLpBudget")
        budget = budgetType.declaredConstructors.single {
            it.parameterCount == 8 && it.parameterTypes.last().simpleName == "DefaultConstructorMarker"
        }.newInstance(16, null, null, null, null, funding, 30, null)
        val authorityType = Class.forName("com.eignex.klause.lp.optimization.LinearAuthority")
        val policy = Class.forName("com.eignex.klause.lp.SparseSourceLpPolicy").getConstructor().newInstance()
        authority = authorityType.declaredConstructors.single {
            it.parameterCount == 6 && it.parameterTypes.last() == Boolean::class.javaPrimitiveType
        }.newInstance(budget, { _: Any? -> }, true, policy, emptyList<Any>(), false)
        val publisherType = Class.forName("com.eignex.klause.solver.pipeline.SourceNumericalFeedback")
        publisher = publisherType.getDeclaredConstructor(fundingType, Cancellation::class.java,
            Boolean::class.javaPrimitiveType).newInstance(funding, stop, true)
        val acquisition = publisherType.methods.single { it.name == "acquire" }
            .invoke(publisher, authority, prepared, cutoff, cuts)
        feedback = field(acquisition, "feedback")
        recovered = field(checkNotNull(field(acquisition, "result")), "incumbent") as Sample?
        val captured = checkNotNull(feedback)
        outcome = captured.javaClass.simpleName
        if (captured.javaClass.simpleName != "Available") {
            unavailableReason = field(captured, "reason").toString()
            return
        }
        val refutation = arm.startsWith("numerical-refutation")
        if (refutation) {
            support = publisherType.methods.single { it.name == "refutationSupport" }.invoke(publisher, captured)
        }
        if (arm != "numerical-ablated") {
            val type = Class.forName("com.eignex.klause.solver.pipeline.FiniteNumericalRemoval")
            val kind = Class.forName("com.eignex.klause.solver.pipeline.FiniteNumericalRemoval\$Signal")
            val completion = Class.forName("com.eignex.klause.solver.pipeline.FiniteNumericalRemoval\$Completion")
            selector = type.getDeclaredConstructor(BakedProblem::class.java, LinearObjective::class.java,
                SourceProofContext::class.java, fundingType, kind, Assumptions::class.java,
                Class.forName("com.eignex.klause.localsearch.InvariantNetwork"), completion)
                .newInstance(problem, objective, source, funding,
                    kind.enumConstants.single {
                        it.toString() == if (refutation) "Refutation" else "Disagreement"
                    }, Assumptions.None, null, completion.enumConstants.single {
                        it.toString() == if (arm.endsWith("-completed")) "SourceRows" else "None"
                    })
            type.methods.single { it.name == "accept" }
                .invoke(selector, center, pins, prepared, cutoff, captured, support, stop)
        }
        val record = checkNotNull(field(captured, "query"))
        @Suppress("UNCHECKED_CAST")
        val point = field(record, "point") as Map<SourceCoordinate, BigFraction>?
        if (point == null) return
        val values = (0 until problem.numIntVars).map {
            point[SourceCoordinate(SourceCoordinate.Kind.Integer, it)]
        }
        if (values.any { it == null || it.den != BIG_ONE || !it.num.fitsLong() }) {
            rawPointDisposition = "fractional-or-incomplete-integer-point"
            return
        }
        val sample = Sample(booleanArrayOf(), values.map { checkNotNull(it).num.toLongExact() }.toLongArray())
        rawPointNativeAccepted = checkSource(sample, stop)
        rawPointDisposition = if (rawPointNativeAccepted == true) {
            "original-source-feasible"
        } else "source-check-declined"
    }

    fun capture() = buildJsonObject {
        put("unavailableReason", unavailableReason)
        put("sourceWork", work)
        put("sourceModeledAllocation", allocated)
        put("feedbackOutcome", outcome)
        cutoffRecord?.let { put("sourceCutoff", field(it, "value").toString()) }
        put("rawPointDisposition", rawPointDisposition)
        put("rawPointNativeSourceAccepted", rawPointNativeAccepted)
        put("guidanceStatus", selector?.let { get(it, "Status").toString() })
        put("rankedInputs", selector?.let { get(it, "RankedInputs") as Int })
        put("guidanceUnavailableReason", selector?.let { get(it, "UnavailableReason") as String? })
        selector?.takeIf { get(it, "Completion").toString() == "SourceRows" }?.let { owner ->
            put("completion", buildJsonObject {
                put("rule", "planned domain steps through violated original source rows; supported compensators first")
                put("lastDecline", get(owner, "LastDecline") as String?)
                put("lastCompletedInputsBoolThenInt", JsonArray((get(owner, "LastCompletedInputs") as IntArray)
                    .map(::JsonPrimitive)))
                put("lastFilledInputs", get(owner, "LastFilledInputs") as Int)
                put("packets", JsonArray((get(owner, "Packets") as List<*>).map { entry ->
                    val packet = checkNotNull(entry)
                    buildJsonObject {
                        put("seed", coordinate(get(packet, "Seed") as SourceCoordinate))
                        put("outcome", get(packet, "Outcome").toString())
                        put("blockedRow", get(packet, "BlockedRow") as String?)
                        put("members", JsonArray((get(packet, "Members") as List<*>).map { item ->
                            val member = checkNotNull(item)
                            buildJsonObject {
                                put("coordinate", coordinate(get(member, "Coordinate") as SourceCoordinate))
                                put("plannedValue", get(member, "Value").toString())
                                put("addedByRow", get(member, "Row") as String?)
                            }
                        }))
                    }
                }))
            })
        }
        budget?.let {
            put("sourceOperations", get(it, "Operations") as Int)
            put("reservedSourceWork", get(it, "ReservedWork") as Long)
            put("reservedSourceAllocation", get(it, "ReservedAllocation") as Long)
            put("sourceActiveNanos", get(it, "ActiveNanos") as Long)
        }
        support?.let {
            put("supportOutcome", it.javaClass.simpleName)
            if (it.javaClass.simpleName == "Available") {
                @Suppress("UNCHECKED_CAST")
                val coordinates = field(it, "coordinates") as Set<SourceCoordinate>
                put("supportCoordinates", JsonArray(coordinates.map(::coordinate)))
            } else put("supportUnavailableReason", field(it, "reason").toString())
        }
        publisher?.let {
            val journal = checkNotNull(field(it, "journal"))
            put("journal", NumericalSelectionSnapshot(stop).capture(journal))
        }
        put("independentProofClaim", false)
        put("globalProofClaim", false)
    }

    private fun coordinate(value: SourceCoordinate) = buildJsonObject {
        put("kind", value.kind.name)
        put("id", value.id)
    }

    private fun get(owner: Any, property: String): Any? = owner.javaClass.methods.single {
        it.name.substringBefore('$') == "get$property" && it.parameterCount == 0
    }.invoke(owner)

    fun finishAcquisition() {
        authority?.let { owner ->
            publisher?.let { capture ->
                capture.javaClass.methods.single { it.name == "detach" }.invoke(capture, owner)
            }
            checkNotNull(budget).javaClass.methods.single { it.name == "closeOwner" }.invoke(budget, owner)
        }
        authority = null
    }

    override fun close() {
        (selector as? AutoCloseable)?.close()
        finishAcquisition()
        selector = null
        publisher = null
        feedback = null
        support = null
        query = null
        budget = null
        cutoffRecord = null
        recovered = null
    }
}
