package com.eignex.klause.bench.tune

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners
import kotlin.random.Random

/**
 * A large sampling pool backed by the reference tables (#35). Each candidate ref that has a reference row is
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
    private val resolve: (ProblemRef) -> ResolvedProblem = Runners::resolve,
) : SamplingPool {
    private val stratumByRef: Map<ProblemRef, String>
    private val byStratum: Map<String, List<ProblemRef>>

    // Refs that threw while resolving (a corpus instance with a missing include, an unsupported
    // construct): dropped so a single un-parseable instance can't abort the sweep, and never redrawn.
    private val poisoned = HashSet<ProblemRef>()

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

    override fun sample(size: Int, rng: Random): List<ResolvedProblem> {
        // Draw, resolve, and skip refs whose problem setup throws — one un-resolvable instance must not
        // abort the campaign. A failing ref is poisoned (never redrawn) and the batch is topped up from
        // resolvable refs, so a sample degrades to fewer instances only once the pool is exhausted.
        val resolved = ArrayList<ResolvedProblem>(size)
        val tried = HashSet<ProblemRef>()
        while (resolved.size < size) {
            val batch = sampleRefs(size - resolved.size, rng, tried)
            if (batch.isEmpty()) break
            for (ref in batch) {
                tried += ref
                runCatching { resolve(ref) }
                    .onSuccess { resolved += it }
                    .onFailure { poison(ref, it) }
                if (resolved.size >= size) break
            }
        }
        return resolved
    }

    /** The refs a [sample] would draw (round-robin across strata), skipping [exclude] and any poisoned
     *  ref, before resolving — the testable core. */
    fun sampleRefs(size: Int, rng: Random, exclude: Set<ProblemRef> = emptySet()): List<ProblemRef> {
        val picked = LinkedHashSet<ProblemRef>()
        val strata = byStratum.keys.shuffled(rng)
        // Round-robin: one still-unpicked ref from each stratum per pass, until `size` distinct refs.
        while (picked.size < size) {
            val before = picked.size
            for (stratum in strata) {
                if (picked.size >= size) break
                byStratum.getValue(stratum)
                    .filter { it !in picked && it !in poisoned && it !in exclude }
                    .randomOrNull(rng)?.let { picked += it }
            }
            if (picked.size == before) break // every stratum exhausted
        }
        return picked.toList()
    }

    private fun poison(ref: ProblemRef, cause: Throwable) {
        poisoned += ref
        println("[pool] failed to compile ${ref.name}: ${cause.message ?: cause::class.simpleName} (skipped)")
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
