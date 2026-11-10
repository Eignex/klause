package com.eignex.klause.bench.profile

import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.coefficients
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.field
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.scope
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NumericalCapturedPoolReplay(private val stop: Cancellation) {
    private val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityReplay")
    private val owner = type.getField("INSTANCE").get(null)
    private val run = type.declaredMethods.single { it.name == "runWithCapturedCandidates" && it.parameterCount == 6 }
    private var postcheckWork = 0L
    private var postcheckAllocation = 0L
    private val checkStop = Cancellation {
        if (stop() || postcheckWork >= 100_000L) true else {
            postcheckWork++
            false
        }
    }
    private val snapshot = NumericalSelectionSnapshot(checkStop)

    @Suppress("UNCHECKED_CAST", "LongMethod")
    fun replay(record: Any, candidates: List<Any>, emit: (JsonObject) -> Unit) {
        if (candidates.isEmpty()) {
            emit(buildJsonObject {
                put("state", "no-fresh-checked-proposals")
                put("lpQueries", 0)
            })
            return
        }
        val definitions = field(record, "cuts") as List<Any>
        val context = field(record, "context") as SourceProofContext
        require(definitions.size <= 64 && candidates.size <= 64 - definitions.size &&
            context.coordinates.size <= 8192 && context.premises.size <= 8192)
        val installed = definitions.filter { field(it, "installed") == true }.mapTo(HashSet()) {
            field(it, "id") as Int
        }
        require(installed.size == definitions.size && definitions.all {
            field(it, "checked") == true && field(it, "ownerActive") == true
        })
        val introduced = (definitions.size until definitions.size + candidates.size).toSet()
        var queries = 0
        for ((arm, mask) in listOf("no-cut" to emptySet(), "current" to installed,
            "current-plus-proposals" to installed + introduced)) {
            if (stop()) break
            val before = postcheckWork
            val beforeAllocation = postcheckAllocation
            val result = run.invoke(owner, record, mask, candidates, 20_000_000L, 67_108_864L, stop)
            val query = field(result, "query")
            queries += field(result, "operations") as Int
            emit(buildJsonObject {
                put("originalQuery", field(record, "id") as Int)
                put("scope", scope(context.scope))
                put("arm", arm)
                put("mask", buildJsonArray { mask.sorted().forEach { add(JsonPrimitive(it)) } })
                put("introducedCutIds", buildJsonArray { introduced.sorted().forEach { add(JsonPrimitive(it)) } })
                put("capturedDefinitions", definitions.size)
                put("additionalDefinitions", candidates.size)
                put("freshOwner", true)
                put("commonDefinitions", true)
                put("importCostsIncluded", true)
                put("maximumOperations", 1)
                put("maximumWork", 20_000_000L)
                put("maximumModeledAllocation", 67_108_864L)
                put("operations", field(result, "operations") as Int)
                put("work", field(result, "work") as Long)
                put("modeledAllocation", field(result, "allocation") as Long)
                put("stopped", field(result, "stopped") as Boolean)
                put("decline", field(result, "decline") as String?)
                if (query != null) {
                    put("state", field(query, "state") as String)
                    val bytes = (definitions.size + candidates.size) * 131_072L
                    if (bytes <= 16_777_216L - postcheckAllocation && !checkStop()) {
                        postcheckAllocation += bytes
                        put("cuts", buildJsonArray {
                            for (cut in field(query, "cuts") as List<Any>) add(definition(cut))
                        })
                    } else put("cutSerializationUnavailable", true)
                    for (name in listOf("bound", "refutation")) {
                        val evidence = field(query, name) as SourceEvidence? ?: continue
                        val bytes = 262_144L + context.premises.size * 64L
                        if (bytes > 16_777_216L - postcheckAllocation || checkStop()) continue
                        postcheckAllocation += bytes
                        if (name == "bound") put("boundStrict", (evidence.claim as SourceClaim.Bound).strict)
                        put(name, snapshot.evidence(context, evidence))
                    }
                }
                put("nativePostcheckWork", postcheckWork - before)
                put("nativePostcheckModeledAllocation", postcheckAllocation - beforeAllocation)
                put("independentProofCheck", false)
                put("ordinaryPerformanceClaim", false)
            })
        }
        emit(buildJsonObject {
            put("summary", true)
            put("originalQuery", field(record, "id") as Int)
            put("lpQueries", queries)
            put("maximumLpQueries", 3)
            put("nativePostcheckWork", postcheckWork)
            put("nativePostcheckModeledAllocation", postcheckAllocation)
            put("maximumNativePostcheckWork", 100_000L)
            put("maximumNativePostcheckModeledAllocation", 16_777_216L)
            put("stopped", stop())
            put("postcheckStopped", stop() || postcheckWork >= 100_000L)
            put("independentProofCheck", false)
        })
    }

    private fun definition(cut: Any): JsonObject {
        val row = field(cut, "inequality") as SourceInequality
        return buildJsonObject {
            put("id", field(cut, "id") as Int)
            put("owner", field(cut, "owner") as Int)
            put("mixed", field(cut, "mixed") as Boolean)
            put("installed", field(cut, "installed") as Boolean)
            put("checked", field(cut, "checked") as Boolean)
            put("ownerActive", field(cut, "ownerActive") as Boolean)
            put("family", field(cut, "family").toString())
            put("coefficients", coefficients(row.coefficients))
            put("rhs", row.rhs.toString())
            put("strict", row.strict)
            put("derivation", snapshot.proof(field(cut, "derivation") as SourceDerivation))
            put("owners", buildJsonArray {
                @Suppress("UNCHECKED_CAST")
                (field(cut, "owners") as Set<Int>).sorted().forEach { add(JsonPrimitive(it)) }
            })
        }
    }
}
