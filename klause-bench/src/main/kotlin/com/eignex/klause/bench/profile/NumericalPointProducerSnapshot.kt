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
import com.eignex.util.numbers.magnitudeBitLength
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class NumericalPointProducerSnapshot(private val stop: Cancellation) {
    @Suppress("UNCHECKED_CAST")
    fun inspect(journal: Any, emit: (JsonObject) -> Unit) {
        val records = field(journal, "retained") as List<Any>
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregateCuts")
        val owner = type.getField("INSTANCE").get(null)
        val prepare = type.declaredMethods.single { it.name == "prepareSourcePairs" }
        val limits = type.getDeclaredMethod("getPointSourcePairLimits").invoke(owner)
        require(field(limits, "work") == 65_536L && field(limits, "allocation") == 1_048_576L)
        val maximumWork = 20_000_000L
        val maximumAllocation = 67_108_864L
        var work = 0L
        var allocation = 0L
        var examined = 0
        var limited = false
        val active = Cancellation { stop() || work >= maximumWork || allocation >= maximumAllocation }
        val snapshot = NumericalSelectionSnapshot(active)
        for (record in records) {
            if (active()) break
            val point = field(record, "point") as Map<SourceCoordinate, BigFraction>?
            val values = HashMap<SourceCoordinate, Double>()
            var projectionWork = 0L
            val projectionAllocation = 128L + (point?.size ?: 0) * 96L
            if (projectionAllocation > maximumAllocation - allocation) {
                limited = true
                break
            }
            allocation += projectionAllocation
            var projected = point != null && point.size <= 8192 && projectionAllocation <= 1_048_576L
            if (projected) for ((coordinate, value) in checkNotNull(point)) {
                val words = (value.num.magnitudeBitLength().toLong() +
                    value.den.magnitudeBitLength() + 63L) / 64L
                val cost = 1L + words * words
                if (active() || cost > 65_536L - projectionWork || cost > maximumWork - work) {
                    projected = false
                    break
                }
                projectionWork += cost
                work += cost
                val number = value.toDouble()
                if (!number.isFinite()) {
                    projected = false
                    break
                }
                values[coordinate] = number
            }
            if (!projected || active()) {
                emit(buildJsonObject {
                    put("originalQuery", field(record, "id") as Int)
                    put("state", "point-unavailable")
                    put("projectionWork", projectionWork)
                    put("projectionModeledAllocation", projectionAllocation)
                })
                continue
            }
            val numeric = field(record, "numeric") as SourceProofContext
            if (maximumWork - work < 65_536L || maximumAllocation - allocation < 1_048_576L) {
                limited = true
                break
            }
            val observations = linkedMapOf<String, Long>()
            val observe: (String, Long) -> Unit = { stage, count ->
                observations[stage] = (observations[stage] ?: 0L) + count
                if (stage == "AggregateWork") work += count
                if (stage == "AggregateAllocation") allocation += count
            }
            val cuts = prepare.invoke(owner, numeric, active, observe, 64, limits, values) as List<Any>
            val proofAllocation = cuts.size * (131_072L + numeric.premises.size * 64L)
            var proofAllocated = 0L
            var proofWork = 0L
            val proofStop = Cancellation {
                if (active() || proofWork >= 100_000L) true else {
                    proofWork++
                    work++
                    false
                }
            }
            val verifier = SourceEvidenceVerifier(numeric)
            val checks = if (proofAllocation > maximumAllocation - allocation) {
                limited = true
                List(cuts.size) { SourceEvidenceCheck.Unavailable("inspection allocation") }
            } else {
                allocation += proofAllocation
                proofAllocated = proofAllocation
                cuts.map { cut ->
                    verifier.verify(SourceEvidence(numeric.scope,
                        SourceClaim.Consequence(field(cut, "inequality") as SourceInequality),
                        field(cut, "derivation") as SourceDerivation), proofStop)
                }
            }
            examined++
            emit(buildJsonObject {
                put("originalQuery", field(record, "id") as Int)
                put("scope", scope(numeric.scope))
                put("producer", "production-point-source-pairs")
                put("maximumGenerationWork", 65_536L)
                put("maximumGenerationModeledAllocation", 1_048_576L)
                put("maximumCandidates", 64)
                put("projectionWork", projectionWork)
                put("projectionModeledAllocation", projectionAllocation)
                put("observations", buildJsonObject { observations.forEach { (key, value) -> put(key, value) } })
                put("proofWork", proofWork)
                put("proofModeledAllocation", proofAllocated)
                put("proofChecksComplete", checks.none { it is SourceEvidenceCheck.Unavailable })
                put("acceptedCandidates", checks.count { it == SourceEvidenceCheck.Checked })
                put("rejectedCandidates", checks.count { it is SourceEvidenceCheck.Rejected })
                put("candidates", buildJsonArray {
                    cuts.forEachIndexed { index, cut -> add(buildJsonObject {
                        val row = field(cut, "inequality") as SourceInequality
                        put("coefficients", coefficients(row.coefficients))
                        put("rhs", row.rhs.toString())
                        put("strict", row.strict)
                        put("freshNativeCheck", checks[index].toString())
                        put("owners", buildJsonArray {
                            (field(cut, "owners") as Set<Int>).sorted().forEach { add(JsonPrimitive(it)) }
                        })
                        put("derivation", snapshot.proof(field(cut, "derivation") as SourceDerivation))
                    }) }
                })
                put("cumulativeWork", work)
                put("cumulativeModeledAllocation", allocation)
                put("lpQueries", 0)
                put("installedIntoTraversal", false)
                put("independentProofCheck", false)
            })
        }
        emit(buildJsonObject {
            put("summary", true)
            put("capturedQueries", records.size)
            put("examinedQueries", examined)
            put("captureLimited", journal.javaClass.getDeclaredMethod("getLimited").invoke(journal) as Boolean)
            put("stopped", active() || limited)
            put("maximumWork", maximumWork)
            put("maximumModeledAllocation", maximumAllocation)
            put("work", work)
            put("modeledAllocation", allocation)
            put("lpQueries", 0)
            put("wholeCliTimingClaim", false)
            put("independentProofCheck", false)
        })
    }
}
