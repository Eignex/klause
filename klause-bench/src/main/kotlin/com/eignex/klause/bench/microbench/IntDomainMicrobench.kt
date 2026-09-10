package com.eignex.klause.bench.microbench

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.values
import kotlin.random.Random
import kotlin.time.measureTime

/** Host-sensitive timing probes for the wide [IntDomain] representations. */
object IntDomainMicrobench {

    /** Run every host-sensitive IntDomain representation timing probe. */
    @JvmStatic
    fun main(args: Array<String>) {
        membershipIsSpanIndependent()
        repCrossoverPicksTheCompactOne()
        pathologicalShapes()
    }

    private fun carveDownTo(span: Int, survivors: IntArray): IntDomain {
        val toExclude = LongArray(span - survivors.size)
        var excluded = 0
        var next = 0
        for (survivor in survivors) {
            for (value in next until survivor) toExclude[excluded++] = value.toLong()
            next = survivor + 1
        }
        for (value in next until span) toExclude[excluded++] = value.toLong()
        return IntDomain(
            0,
            (span - 1).toLong(),
        ).excludeValues(toExclude) ?: error("excluding every value from a non-empty domain")
    }

    private fun membershipIsSpanIndependent() {
        val rng = Random(1)
        val survivorCount = 2_000
        println("=== contains() vs declared span (survivors fixed at $survivorCount) ===")
        for (spanShift in intArrayOf(16, 18, 20, 22, 24)) {
            val span = 1 shl spanShift
            val survivors = IntArray(survivorCount) { rng.nextInt(span) }.distinct().sorted().toIntArray()
            val domain = carveDownTo(span, survivors)
            val probes = IntArray(1_000_000) { rng.nextInt(span) }
            var hits = 0
            val elapsed = measureTime { for (probe in probes) if (probe.toLong() in domain) hits++ }
            println(
                "span=2^$spanShift ($span) rep=${domain::class.simpleName} size=${domain.values.size} " +
                    "1M contains=$elapsed hits=$hits",
            )
        }
    }

    private fun repCrossoverPicksTheCompactOne() {
        val rng = Random(2)
        val span = 4_000_000
        println("=== run-vs-survivor crossover (2R <= S => runs) ===")
        for (averageRun in intArrayOf(1, 2, 3, 8, 64)) {
            val survivors = IntBuilder()
            var value = 0
            while (value < span && survivors.size < 200_000) {
                val runLength = 1 + rng.nextInt(2 * averageRun)
                repeat(runLength) {
                    if (value < span) survivors.add(value)
                    value++
                }
                value += 1 + rng.nextInt(2 * averageRun)
            }
            val domain = carveDownTo(span, survivors.toArray())
            val probes = IntArray(1_000_000) { rng.nextInt(span) }
            var hits = 0
            val elapsed = measureTime { for (probe in probes) if (probe.toLong() in domain) hits++ }
            println(
                "avgRun~$averageRun rep=${domain::class.simpleName} size=${domain.values.size} " +
                    "1M contains=$elapsed",
            )
        }
    }

    private fun pathologicalShapes() {
        val rng = Random(3)
        val span = 20_000_000
        println("=== pathological shapes at span=$span ===")

        val sparse = IntArray(5_000) { rng.nextInt(span) }.distinct().sorted().toIntArray()
        val sparseDomain = carveDownTo(span, sparse)
        val exclusions = LongArray(1_000) { rng.nextInt(span).toLong() }.also { it.sort() }
        val excludeElapsed = measureTime { repeat(1_000) { sparseDomain.excludeValues(exclusions) } }
        println(
            "wide-sparse rep=${sparseDomain::class.simpleName} size=${sparseDomain.values.size} " +
                "1000xexcludeValues=$excludeElapsed",
        )

        val holes = LongArray(50) { rng.nextInt(span).toLong() }.also { it.sort() }
        val wideDomain = IntDomain(0, (span - 1).toLong())
            .excludeValues(holes) ?: error("excluding sparse holes from a wide domain")
        val wideProbes = IntArray(1_000_000) { rng.nextInt(span) }
        var wideHits = 0
        val wideElapsed = measureTime { for (probe in wideProbes) if (probe.toLong() in wideDomain) wideHits++ }
        println(
            "few-holes-wide rep=${wideDomain::class.simpleName} size=${wideDomain.values.size} " +
                "1M contains=$wideElapsed hits=$wideHits",
        )

        val combSpan = 2_000_000
        val combDomain = IntDomain(0, (combSpan - 1).toLong())
            .excludeValues(LongArray(combSpan / 2) { (it * 2 + 1).toLong() })
            ?: error("excluding alternate values from a wide domain")
        val combProbes = IntArray(1_000_000) { rng.nextInt(combSpan) }
        var combHits = 0
        val combElapsed = measureTime { for (probe in combProbes) if (probe.toLong() in combDomain) combHits++ }
        println(
            "comb rep=${combDomain::class.simpleName} size=${combDomain.values.size} " +
                "1M contains=$combElapsed hits=$combHits",
        )
    }

    private class IntBuilder(initialCapacity: Int = 16) {
        private var values = IntArray(initialCapacity)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): IntArray = values.copyOf(size)
    }
}
