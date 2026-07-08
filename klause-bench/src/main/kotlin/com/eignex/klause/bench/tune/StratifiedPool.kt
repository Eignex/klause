package com.eignex.klause.bench.tune

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners
import kotlin.random.Random

/**
 * A large sampling pool backed by references.csv (#35). Each candidate ref that has a reference row is
 * bucketed into a **stratum = (sizeTier, structure)**: `sizeTier` ∈ {S,M,L,XL} splits the pool's cp-sat
 * `elapsedMs` (the difficulty proxy) at its quartiles, and `structure` is the classified column from
 * #40. [sample] draws round-robin across strata and resolves **only** the drawn refs — the whole pool
 * is never resolved, so a huge corpus stays memory-safe — and a batch always spans sizes/structures, so
 * every stratum stays covered as batches accumulate. That is what keeps the stratum-frontier residual
 * meaningful regardless of pool size. Candidates with no reference row are dropped (no oracle → no COP
 * reward, and no stratum labels).
 */
internal class StratifiedPool(
    candidates: List<ProblemRef>,
    references: Map<Pair<String, String>, ReferenceEntry> = ReferenceStore.load(),
) : SamplingPool {
    private val stratumByRef: Map<ProblemRef, String>
    private val byStratum: Map<String, List<ProblemRef>>

    init {
        val rows = candidates.mapNotNull { ref ->
            references[ReferenceStore.suiteOf(ref) to ref.name]?.let { ref to it }
        }
        val cuts = quartiles(rows.map { it.second.elapsedMs })
        stratumByRef = rows.associate { (ref, row) ->
            ref to "${tier(row.elapsedMs, cuts)}|${row.structure.ifBlank { "?" }}"
        }
        byStratum = stratumByRef.entries.groupBy({ it.value }, { it.key })
    }

    /** The strata and how many candidates each holds — for the run banner / analysis. */
    fun strata(): Map<String, Int> = byStratum.mapValues { it.value.size }

    override fun isNotEmpty(): Boolean = byStratum.isNotEmpty()

    override fun sample(size: Int, rng: Random): List<ResolvedProblem> =
        sampleRefs(size, rng).map { Runners.resolve(it) }

    /** The refs a [sample] would draw (round-robin across strata), before resolving — the testable core. */
    fun sampleRefs(size: Int, rng: Random): List<ProblemRef> {
        val picked = LinkedHashSet<ProblemRef>()
        val strata = byStratum.keys.shuffled(rng)
        // Round-robin: one still-unpicked ref from each stratum per pass, until `size` distinct refs.
        while (picked.size < size) {
            val before = picked.size
            for (stratum in strata) {
                if (picked.size >= size) break
                byStratum.getValue(stratum).filter { it !in picked }.randomOrNull(rng)?.let { picked += it }
            }
            if (picked.size == before) break // every stratum exhausted
        }
        return picked.toList()
    }

    /** The stratum label assigned to [ref] (test/analysis visibility). */
    fun stratumFor(ref: ProblemRef): String? = stratumByRef[ref]

    override fun stratumOf(p: ResolvedProblem): String = stratumByRef[p.ref] ?: instanceKey(p)

    private companion object {
        /** The 25th/50th/75th-percentile cut points of [times] (empty → all-zero, one stratum). */
        fun quartiles(times: List<Long>): List<Long> {
            if (times.isEmpty()) return listOf(0L, 0L, 0L)
            val sorted = times.sorted()
            fun q(f: Double) = sorted[((sorted.size - 1) * f).toInt()]
            return listOf(q(0.25), q(0.5), q(0.75))
        }

        fun tier(ms: Long, cuts: List<Long>): String = when {
            ms <= cuts[0] -> "S"
            ms <= cuts[1] -> "M"
            ms <= cuts[2] -> "L"
            else -> "XL"
        }
    }
}
