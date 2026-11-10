package com.eignex.klause.bench.profile

import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.coefficients
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.field
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.scope
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.system
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
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

internal class NumericalProductionAggregateSnapshot(private val stop: Cancellation) {
    private var projectionWork = 0L
    private var projectionAllocation = 0L

    private data class Point(
        val id: Int,
        val generation: SourceProofContext,
        val verification: SourceProofContext,
        val exact: Map<SourceCoordinate, BigFraction>,
        val floating: Map<SourceCoordinate, Double>,
        val cuts: List<Any>,
        val projectionWork: Long,
        val projectionAllocation: Long,
    )

    private class Kernel {
        private val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregateCuts")
        private val owner = type.getField("INSTANCE").get(null)
        private val generate = type.declaredMethods.single {
            it.name == "prepareSourcePairsCached" && it.parameterCount == 9
        }
        private val cacheType = Class.forName(
            "com.eignex.klause.lp.optimization.LinearAuthorityAggregateCuts\$SourcePairsCache")
        private val cache = cacheType.getDeclaredConstructor().newInstance()
        private val clear = cacheType.declaredMethods.single { it.name == "clear" && it.parameterCount == 1 }
        private val limits = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregateLimits")
            .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType)

        @Suppress("UNCHECKED_CAST")
        fun run(point: Point, fractional: Boolean, stop: Cancellation, observe: (String, Long) -> Unit): List<Any> =
            generate.invoke(owner, point.generation, stop, observe,
                if (fractional) 64 - point.cuts.size else 64,
                limits.newInstance(4, 128, 256, 16_384L, 65_536L, 1_048_576L),
                point.floating, fractional, null, cache) as List<Any>

        fun close(observe: (String, Long) -> Unit) {
            clear.invoke(cache, observe)
        }
    }

    private class ProofBudget(private val stop: Cancellation) {
        var work = 0L
            private set
        var allocation = 0L
            private set
        var withheld = 0
            private set

        fun check(point: Point, row: SourceInequality, derivation: SourceDerivation): Pair<String, String?>? {
            val bytes = 262_144L +
                (point.generation.premises.size.toLong() + point.verification.premises.size) * 64L
            if (stop() || work >= 100_000L || bytes > 16_777_216L - allocation) {
                withheld++
                return null
            }
            allocation += bytes
            val active = Cancellation {
                if (stop() || work >= 100_000L) true else {
                    work++
                    false
                }
            }
            val generation = SourceEvidenceVerifier(point.generation).verify(SourceEvidence(
                point.generation.scope, SourceClaim.Consequence(row), derivation), active)
            if (stop() || work >= 100_000L) {
                withheld++
                return generation.toString() to null
            }
            val verification = SourceEvidenceVerifier(point.verification).verify(SourceEvidence(
                point.verification.scope, SourceClaim.Consequence(row), derivation), active)
            return generation.toString() to verification.toString()
        }
    }

    fun project(exact: Map<SourceCoordinate, BigFraction>): Map<SourceCoordinate, Double>? {
        if (exact.size > 8192) return null
        val bytes = 128L + exact.size * 192L
        if (bytes > 1_048_576L || bytes > 2_097_152L - projectionAllocation) return null
        projectionAllocation += bytes
        var work = 0L
        val floating = HashMap<SourceCoordinate, Double>()
        for ((coordinate, value) in exact) {
            val words = (value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength() + 63L) / 64L
            if (words > 255L) return null
            val cost = 1L + words * words
            if (stop() || cost > 65_536L - work || cost > 131_072L - projectionWork) return null
            work += cost
            projectionWork += cost
            val number = value.toDouble()
            if (!number.isFinite()) return null
            floating[coordinate] = number
        }
        return floating
    }

    @Suppress("UNCHECKED_CAST")
    private fun point(journal: Any, query: Int): Point? {
        val record = (field(journal, "retained") as List<Any>).singleOrNull { field(it, "id") == query }
            ?: return null
        if (field(record, "state") != "completed") return null
        val generation = journal.javaClass.getDeclaredMethod("generationContext", Int::class.javaPrimitiveType)
            .invoke(journal, query) as SourceProofContext? ?: return null
        require(generation.scope == field(record, "generationScope"))
        val exact = field(record, "point") as Map<SourceCoordinate, BigFraction>? ?: return null
        val beforeWork = projectionWork
        val beforeAllocation = projectionAllocation
        val floating = project(exact) ?: return null
        val cuts = field(record, "cuts") as List<Any>
        if (cuts.size > 64) return null
        return Point(query, generation, field(record, "numeric") as SourceProofContext, exact, floating, cuts,
            projectionWork - beforeWork, projectionAllocation - beforeAllocation)
    }

    @Suppress("LongMethod")
    fun inspect(journal: Any, query: Int, emit: (JsonObject) -> Unit) {
        require(query > 0)
        var kernel: Kernel? = null
        val totals = linkedMapOf<String, Long>()
        val released = linkedMapOf<String, Long>()
        val proofs = ProofBudget(stop)
        var calls = 0
        try {
            val activeKernel = Kernel().also { kernel = it }
            val seed = point(journal, 0)
            val target = point(journal, query)
            if (seed == null || target == null || target.cuts.isEmpty() || target.cuts.any {
                field(it, "installed") != true || field(it, "checked") != true || field(it, "ownerActive") != true
            }) {
                emit(buildJsonObject {
                    put("requestedQuery", query)
                    put("state", "requested-point-generation-context-or-installed-pool-unavailable")
                    put("lpQueries", 0)
                    put("sourceVerdictClaim", false)
                })
                return
            }
            for ((point, fractional) in listOf(seed to false, target to true)) {
                if (stop()) break
                val observations = linkedMapOf<String, Long>()
                val observe: (String, Long) -> Unit = { name, count ->
                    observations[name] = (observations[name] ?: 0L) + count
                    totals[name] = (totals[name] ?: 0L) + count
                }
                val candidates = activeKernel.run(point, fractional, stop, observe)
                calls++
                val beforeWork = proofs.work
                val beforeAllocation = proofs.allocation
                val values = candidates.map { cut -> candidate(point, cut, proofs) }
                emit(buildJsonObject {
                    put("originalQuery", point.id)
                    put("role", if (fractional) "later-fractional" else "cold-current-order-seed")
                    put("generator", "prepareSourcePairsCached")
                    put("cacheOwnership", "private-diagnostic-reconstruction")
                    put("fractionalTargeting", fractional)
                    put("generationContextSameAsSeed", point.generation === seed.generation)
                    put("generationScope", scope(point.generation.scope))
                    put("verificationScope", scope(point.verification.scope))
                    put("generationSystem", system(point.generation))
                    put("verificationSystem", system(point.verification))
                    put("point", coefficients(point.exact))
                    put("projectedPoint", coefficients(point.floating.mapValues {
                        checkNotNull(BigFraction.ofDouble(it.value))
                    }))
                    put("retainedPoolRows", point.cuts.size)
                    put("maximumCandidates", if (fractional) 64 - point.cuts.size else 64)
                    put("maximumGenerationWork", 65_536L)
                    put("maximumGenerationAllocation", 1_048_576L)
                    put("projectionWork", point.projectionWork)
                    put("projectionModeledAllocation", point.projectionAllocation)
                    put("observations", buildJsonObject { observations.forEach { (key, value) -> put(key, value) } })
                    put("proofWork", proofs.work - beforeWork)
                    put("proofModeledAllocation", proofs.allocation - beforeAllocation)
                    put("candidates", buildJsonArray { values.forEach { add(it) } })
                    put("lpQueries", 0)
                    put("independentProofCheck", false)
                    put("sourceVerdictClaim", false)
                })
            }
        } finally {
            var contexts = 0
            try {
                kernel?.close { name, count -> released[name] = (released[name] ?: 0L) + count }
            } finally {
                contexts = journal.javaClass.getDeclaredMethod("releaseGenerationContexts").invoke(journal) as Int
            }
            emit(buildJsonObject {
                put("summary", true)
                put("requestedQuery", query)
                put("generationCalls", calls)
                put("observations", buildJsonObject { totals.forEach { (key, value) -> put(key, value) } })
                put("release", buildJsonObject { released.forEach { (key, value) -> put(key, value) } })
                put("releasedGenerationContextReferences", contexts)
                put("projectionWork", projectionWork)
                put("projectionModeledAllocation", projectionAllocation)
                put("proofWork", proofs.work)
                put("proofModeledAllocation", proofs.allocation)
                put("proofWithheld", proofs.withheld)
                put("maximumProofWork", 100_000L)
                put("maximumProofModeledAllocation", 16_777_216L)
                put("stopped", stop())
                put("lpQueries", 0)
                put("wholeCliTimingClaim", false)
                put("independentProofCheck", false)
            })
        }
    }

    private fun candidate(point: Point, candidate: Any, proofs: ProofBudget): JsonObject {
        val row = field(candidate, "inequality") as SourceInequality
        val derivation = field(candidate, "derivation") as SourceDerivation
        val checked = proofs.check(point, row, derivation)
        return buildJsonObject {
            put("coefficients", coefficients(row.coefficients))
            put("rhs", row.rhs.toString())
            put("strict", row.strict)
            put("generationCheck", checked?.first)
            put("verificationCheck", checked?.second)
            put("proofBudgetWithheld", checked == null || checked.second == null)
            put("derivation", NumericalSelectionSnapshot(stop).proof(derivation))
            put("proofSerializationStopped", stop())
            put("owners", buildJsonArray {
                @Suppress("UNCHECKED_CAST")
                (field(candidate, "owners") as Set<Int>).sorted().forEach { add(JsonPrimitive(it)) }
            })
        }
    }
}
