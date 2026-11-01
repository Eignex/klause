package com.eignex.klause.bench.profile

import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.coefficients
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.field
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.scope
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NumericalAggregateSnapshot(private val stop: Cancellation, private val allowMixed: Boolean = false) {
    @Suppress("UNCHECKED_CAST")
    fun inspect(
        journal: Any, onAccepted: (Any, List<Any>) -> Unit = { _, _ -> }, emit: (JsonObject) -> Unit,
    ) {
        val records = field(journal, "retained") as List<Any>
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregateCuts")
        val owner = type.getField("INSTANCE").get(null)
        val inspect = type.declaredMethods.single { it.name == "inspectPairs" }
        val term = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregateTerm")
            .getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java, SourceInequality::class.java,
                BigFraction::class.java)
        val limits = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregateLimits")
            .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType)
        val maximumWork = 20_000_000L
        val maximumAllocation = 67_108_864L
        var work = 0L
        var allocation = 0L
        var examined = 0
        var unavailable = 0
        val active = Cancellation { stop() || work >= maximumWork || allocation >= maximumAllocation }
        for (record in records) {
            if (active()) break
            work++
            allocation += 256L
            if (active()) break
            val point = field(record, "point") as Map<SourceCoordinate, BigFraction>?
            if (point == null) {
                unavailable++
                emit(buildJsonObject {
                    put("originalQuery", field(record, "id") as Int)
                    put("state", "point-unavailable")
                    put("complete", false)
                })
                continue
            }
            val numeric = field(record, "numeric") as SourceProofContext
            val cited = field(record, "cited") as List<Pair<SourceInequality, SourceDerivation>>
            work += cited.size
            allocation += cited.size * 160L + point.size * 512L
            if (active()) break
            val premises = numeric.premises
            require(premises.size <= cited.size)
            val rows = premises.entries.mapIndexed { index, (id, premise) ->
                require((cited[index].second as? SourceDerivation.Premise)?.id == id)
                term.newInstance(index, id, premise.inequality, BigFraction.ONE)
            }
            val inactive = field(record, "inactive") as Set<Int>
            val observations = linkedMapOf<String, Long>()
            val observe: (String, Long) -> Unit = { stage, count ->
                observations[stage] = (observations[stage] ?: 0L) + count
                if (stage == "AggregateWork") work += count
                if (stage == "AggregateAllocation") allocation += count
            }
            val remaining = limits.newInstance(4, 128, 256, 16_384L, maximumWork - work,
                maximumAllocation - allocation)
            val result = inspect.invoke(owner, rows, inactive, point, active, observe, remaining, 64, allowMixed)
            val cuts = field(result, "candidates") as List<Any>
            val violations = field(result, "violations") as List<BigFraction>
            val evidence = cuts.map { cut ->
                SourceEvidence(numeric.scope, SourceClaim.Consequence(field(cut, "inequality") as SourceInequality),
                    field(cut, "derivation") as SourceDerivation)
            }
            val proofAllocation = cuts.size * (131_072L + premises.size * 64L)
            allocation += proofAllocation
            var proofWork = 0L
            val proofStop = Cancellation {
                proofWork++
                work++
                active() || proofWork >= 100_000L
            }
            val verifier = SourceEvidenceVerifier(numeric)
            val checks = evidence.map { verifier.verify(it, proofStop) }
            val accepted = cuts.filterIndexed { index, _ -> checks[index] == SourceEvidenceCheck.Checked }
            if (accepted.isNotEmpty()) onAccepted(record, accepted)
            examined++
            emit(buildJsonObject {
                put("originalQuery", field(record, "id") as Int)
                put("scope", scope(numeric.scope))
                put("family", if (allowMixed) "two-source-pivot-cancellation-and-mixed-rounding"
                    else "two-source-complete-continuous-cancellation-integer-rounding")
                put("mixedResiduals", allowMixed)
                put("boundPolicy", "first-active-source-side-preferring-negative-residual")
                put("lpQueries", 0)
                put("maximumAggregateRows", 4)
                put("examinedAggregateRows", 2)
                put("maximumSupport", 128)
                put("maximumScalarBits", 256)
                put("maximumProofBytes", 16_384L)
                put("maximumCandidates", 64)
                put("maximumProofSteps", 100_000L)
                put("complete", field(result, "complete") as Boolean)
                put("activeSourceRowsSatisfied", field(result, "activeSourceRowsSatisfied") as Boolean?)
                put("maximumSourceResidual", (field(result, "maximumSourceResidual") as BigFraction?)?.toString())
                put("independentProofCheck", false)
                put("generatingCutMask", buildJsonArray {
                    (field(record, "cuts") as List<Any>).forEach { cut ->
                        if (field(cut, "installed") as Boolean) add(JsonPrimitive(field(cut, "id") as Int))
                    }
                })
                put("point", coefficients(point))
                put("observations", buildJsonObject { observations.forEach { (key, value) -> put(key, value) } })
                put("proofWork", proofWork)
                put("proofModeledAllocation", proofAllocation)
                put("proofChecksComplete", checks.none { it is SourceEvidenceCheck.Unavailable })
                put("acceptedCandidates", checks.count { it == SourceEvidenceCheck.Checked })
                put("rejectedCandidates", checks.count { it is SourceEvidenceCheck.Rejected })
                put("cumulativeWork", work)
                put("cumulativeModeledAllocation", allocation)
                put("candidates", buildJsonArray {
                    for (index in cuts.indices) {
                        val cut = cuts[index]
                        val row = field(cut, "inequality") as SourceInequality
                        val rounding = field(cut, "derivation") as SourceDerivation
                        val combination = when (rounding) {
                            is SourceDerivation.IntegerRounding -> rounding.inequality
                            is SourceDerivation.MixedIntegerRounding -> rounding.inequality
                            else -> error("unsupported aggregate rounding derivation")
                        } as SourceDerivation.Combination
                        add(buildJsonObject {
                            put("coefficients", coefficients(row.coefficients))
                            put("rhs", row.rhs.toString())
                            put("violation", violations[index].toString())
                            put("freshNativeCheck", checks[index].toString())
                            put("owners", buildJsonArray {
                                (field(cut, "owners") as Set<Int>).sorted().forEach { add(JsonPrimitive(it)) }
                            })
                            put("rounding", rounding::class.simpleName)
                            put("terms", buildJsonArray {
                                combination.terms.forEach { weighted -> add(buildJsonObject {
                                    put("weight", weighted.weight.toString())
                                    put("premise", (weighted.derivation as SourceDerivation.Premise).id)
                                }) }
                            })
                            if (rounding is SourceDerivation.MixedIntegerRounding) put("bounds", buildJsonArray {
                                rounding.bounds.forEach { add(JsonPrimitive((it as SourceDerivation.Premise).id)) }
                            })
                        })
                    }
                })
            })
        }
        emit(buildJsonObject {
            put("summary", true)
            put("capturedQueries", records.size)
            put("examinedQueries", examined)
            put("pointUnavailableQueries", unavailable)
            put("captureLimited", journal.javaClass.getDeclaredMethod("getLimited").invoke(journal) as Boolean)
            put("stopped", active())
            put("maximumWork", maximumWork)
            put("maximumModeledAllocation", maximumAllocation)
            put("work", work)
            put("modeledAllocation", allocation)
            put("lpQueries", 0)
            put("wholeCliTimingClaim", false)
            put("independentProofCheck", false)
        })
    }

    @Suppress("UNCHECKED_CAST")
    fun replay(record: Any, candidates: List<Any>, emit: (JsonObject) -> Unit) {
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityReplay")
        val owner = type.getField("INSTANCE").get(null)
        val run = type.declaredMethods.single { it.name == "runWithCandidates" }
        val generated = type.declaredMethods.single { it.name == "generatedAggregates" }
        val context = field(record, "context") as SourceProofContext
        val snapshot = NumericalSelectionSnapshot(stop)
        for (arm in listOf("disabled", "individual", "individual-and-aggregate", "individual-and-generated")) {
            if (stop()) return
            val additional = if (arm == "individual-and-aggregate") candidates else emptyList()
            val result = if (arm == "individual-and-generated") {
                generated.invoke(owner, record, 20_000_000L, 67_108_864L, stop)
            } else run.invoke(owner, record, null, additional, arm != "disabled", 20_000_000L, 67_108_864L, stop)
            emit(buildJsonObject {
                put("originalQuery", field(record, "id") as Int)
                put("scope", scope(context.scope))
                put("arm", arm)
                put("additionalCandidates", additional.size)
                put("aggregateDiscoveryIncluded", arm == "individual-and-generated")
                put("freshBasis", true)
                put("diagnosticOnly", true)
                put("maximumWork", 20_000_000L)
                put("maximumModeledAllocation", 67_108_864L)
                put("maximumOperations", 1)
                put("operations", field(result, "operations") as Int)
                put("work", field(result, "work") as Long)
                put("modeledAllocation", field(result, "allocation") as Long)
                put("stopped", field(result, "stopped") as Boolean)
                put("observations", buildJsonObject {
                    (field(result, "observations") as Map<String, Long>).forEach { (name, count) -> put(name, count) }
                })
                put("independentProofCheck", false)
                field(result, "query")?.let { query ->
                    put("state", field(query, "state") as String)
                    (field(query, "point") as Map<SourceCoordinate, BigFraction>?)?.let {
                        put("point", coefficients(it))
                    }
                    put("cuts", buildJsonArray {
                        (field(query, "cuts") as List<Any>).forEach { cut -> add(buildJsonObject {
                            put("id", field(cut, "id") as Int)
                            put("installed", field(cut, "installed") as Boolean)
                            put("checked", field(cut, "checked") as Boolean)
                        }) }
                    })
                    for (name in listOf("bound", "refutation")) {
                        (field(query, name) as SourceEvidence?)?.let { put(name, snapshot.evidence(context, it)) }
                    }
                }
            })
        }
    }
}
