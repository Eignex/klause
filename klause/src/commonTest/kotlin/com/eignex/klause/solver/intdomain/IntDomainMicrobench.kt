package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.values
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Microbenchmarks justifying the [IntDomain] rep selection (the `2·runs <= survivors` crossover and
 * the bitset cutoff) and confirming the wide reps are span-independent on the
 * pathological shapes from #723. Not run in CI — `@Ignore`d because they print timings rather than
 * assert, and are JIT/host sensitive. Run one explicitly, e.g.:
 *
 *   ./gradlew :klause:jvmTest --tests "com.eignex.klause.solver.intdomain.IntDomainMicrobench" -Dkotest=… off
 *   (or remove @Ignore locally / run from the IDE).
 *
 * The point each one makes:
 *  - [membership_is_span_independent]: contains() cost does NOT grow with the carved span — the
 *    regression #723 fixes (the old hole list bound it to O(span)).
 *  - [rep_crossover_picks_the_compact_one]: near the run-vs-survivor crossover the picked rep is the
 *    smaller of the two, and ops stay cheap on both sides.
 *  - [pathological_shapes]: wide-sparse, few-holes-wide and alternating-comb all stay fast.
 */
@Ignore
class IntDomainMicrobench {

    private fun carveDownTo(span: Int, survivors: IntArray): IntDomain {
        val toExclude = IntArrayList()
        var next = 0
        for (s in survivors) {
            for (v in next until s) toExclude.add(v)
            next = s + 1
        }
        for (v in next until span) toExclude.add(v)
        return IntDomain(
            0,
            (span - 1).toLong(),
        ).excludeValues(toExclude.toIntArray().map { it.toLong() }.toLongArray())!!
    }

    @Test
    fun membership_is_span_independent() {
        val rng = Random(1)
        val survivorCount = 2_000
        println("=== contains() vs declared span (survivors fixed at $survivorCount) ===")
        for (spanShift in intArrayOf(16, 18, 20, 22, 24)) {
            val span = 1 shl spanShift
            val survivors = IntArray(survivorCount) { rng.nextInt(span) }.distinct().sorted().toIntArray()
            val d = carveDownTo(span, survivors)
            val probes = IntArray(1_000_000) { rng.nextInt(span) }
            var hits = 0
            val t = measureTime { for (p in probes) if (p.toLong() in d) hits++ }
            println(
                "span=2^$spanShift ($span) rep=${d::class.simpleName} size=${d.values.size} " +
                    "1M contains=$t hits=$hits",
            )
        }
    }

    @Test
    fun rep_crossover_picks_the_compact_one() {
        val rng = Random(2)
        val span = 4_000_000
        println("=== run-vs-survivor crossover (2R <= S ⇒ runs) ===")
        // Vary average run length around 2 (the crossover) by emitting clustered survivors.
        for (avgRun in intArrayOf(1, 2, 3, 8, 64)) {
            val survivors = IntArrayList()
            var v = 0
            while (v < span && survivors.size < 200_000) {
                val runLen = 1 + rng.nextInt(2 * avgRun) // mean ~ avgRun
                repeat(runLen) {
                    if (v < span) survivors.add(v)
                    v++
                }
                v += 1 + rng.nextInt(2 * avgRun) // gap of similar size
            }
            val d = carveDownTo(span, survivors.toIntArray())
            val probes = IntArray(1_000_000) { rng.nextInt(span) }
            var hits = 0
            val t = measureTime { for (p in probes) if (p.toLong() in d) hits++ }
            println("avgRun~$avgRun rep=${d::class.simpleName} size=${d.values.size} 1M contains=$t")
        }
    }

    @Test
    fun pathological_shapes() {
        val rng = Random(3)
        val span = 20_000_000
        println("=== pathological shapes at span=$span ===")

        // Wide-sparse: a handful of scattered survivors (the liner-sf shape).
        run {
            val sv = IntArray(5_000) { rng.nextInt(span) }.distinct().sorted().toIntArray()
            val d = carveDownTo(span, sv)
            val excl = LongArray(1000) { rng.nextInt(span).toLong() }.also { it.sort() }
            val tExcl = measureTime { repeat(1000) { d.excludeValues(excl) } }
            println("wide-sparse rep=${d::class.simpleName} size=${d.values.size} 1000xexcludeValues=$tExcl")
        }

        // Few-holes-wide: nearly full domain with a few holes (interval rep, few runs).
        run {
            val holes = LongArray(50) { rng.nextInt(span).toLong() }.also { it.sort() }
            val d = IntDomain(0, (span - 1).toLong()).excludeValues(holes)!!
            val probes = IntArray(1_000_000) { rng.nextInt(span) }
            var hits = 0
            val t = measureTime { for (p in probes) if (p.toLong() in d) hits++ }
            println("few-holes-wide rep=${d::class.simpleName} size=${d.values.size} 1M contains=$t hits=$hits")
        }

        // Alternating comb: every other value present (survivor rep, the adversary).
        run {
            val combSpan = 2_000_000
            val sv = IntArray(combSpan / 2) { it * 2 }
            val d = IntDomain(
                0,
                (combSpan - 1).toLong(),
            ).excludeValues(LongArray(combSpan / 2) { (it * 2 + 1).toLong() })!!
            val probes = IntArray(1_000_000) { rng.nextInt(combSpan) }
            var hits = 0
            val t = measureTime { for (p in probes) if (p.toLong() in d) hits++ }
            println(
                "comb rep=${d::class.simpleName} size=${d.values.size} (survivors=${sv.size}) " +
                    "1M contains=$t hits=$hits",
            )
        }
    }
}
