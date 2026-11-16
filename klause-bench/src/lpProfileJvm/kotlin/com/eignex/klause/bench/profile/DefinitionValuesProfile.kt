package com.eignex.klause.bench.profile

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.localsearch.DefinitionValues
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.util.collections.Bits
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.random.Random

/** Paired JVM timing probe for the validity flags in the maintained definition cache. */
object DefinitionValuesProfile {
    private const val BATCH = 32_768
    private const val WARMUP_NANOS = 250_000_000L
    private const val SAMPLE_NANOS = 20_000_000L
    private const val PAIRS = 7

    @Volatile
    private var consumed = 0L

    /** Run three fresh JVM forks, or one child fork selected with `fork=N`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val fork = args.singleOrNull()?.removePrefix("fork=")?.toInt()
        if (fork == null) {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            for (child in 0 until 3) {
                val process = ProcessBuilder(java, "-Xms512m", "-Xmx512m", "-XX:ActiveProcessorCount=1",
                    "-cp", System.getProperty("java.class.path"), DefinitionValuesProfile::class.java.name,
                    "fork=$child").inheritIO().start()
                check(process.waitFor() == 0) { "DefinitionValues fork $child failed" }
            }
            return
        }
        println(buildJsonObject {
            put("kind", "runtime")
            put("fork", fork)
            put("java", System.getProperty("java.runtime.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
            put("cpu", System.getenv("PROCESSOR_IDENTIFIER") ?: "see lab placement")
            put("scope", "JVM cache microbenchmark; no solver throughput claim")
        })
        for (size in intArrayOf(64, 4096, 65_536)) {
            val fixture = Fixture(size)
            for (randomAccess in listOf(false, true)) {
                for (workload in Workload.entries) compare(fixture, workload, randomAccess, fork)
            }
        }
    }

    private fun compare(fixture: Fixture, workload: Workload, randomAccess: Boolean, fork: Int) {
        for (packed in listOf(false, true)) {
            val until = System.nanoTime() + WARMUP_NANOS
            do {
                fixture.prepare()
                consumed = run(fixture, workload, randomAccess, packed, 1)
            } while (System.nanoTime() < until)
        }
        fixture.prepare()
        var started = System.nanoTime()
        consumed = run(fixture, workload, randomAccess, false, 1)
        val rounds = (SAMPLE_NANOS / (System.nanoTime() - started).coerceAtLeast(1)).toInt().coerceIn(1, 256)
        for (pair in 0 until PAIRS) {
            val elapsed = LongArray(2)
            val checksum = LongArray(2)
            val pending = arrayOfNulls<IntArray>(2)
            val values = arrayOfNulls<LongArray>(2)
            val first = (pair + fork) and 1
            for (offset in 0..1) {
                val arm = first xor offset
                fixture.prepare()
                started = System.nanoTime()
                checksum[arm] = run(fixture, workload, randomAccess, arm == 1, rounds)
                elapsed[arm] = System.nanoTime() - started
                consumed = checksum[arm]
                pending[arm] = if (arm == 0) fixture.array.pending.toIntArray() else fixture.packed.pending.toIntArray()
                values[arm] = LongArray(fixture.size) { index ->
                    if (arm == 0) fixture.array.eval(index, fixture.assignment, fixture.domains)
                    else fixture.packed.eval(index, fixture.assignment, fixture.domains)
                }
            }
            check(checksum[0] == checksum[1]) { "Cache evaluation mismatch" }
            check(pending[0].contentEquals(pending[1])) {
                "Cache pending-definition mismatch"
            }
            check(values[0].contentEquals(values[1])) { "Cache final-value mismatch" }
            println(buildJsonObject {
                put("kind", "pair")
                put("fork", fork)
                put("size", fixture.size)
                put("workload", workload.name)
                put("access", if (randomAccess) "random" else "sequential")
                put("pair", pair)
                put("first", if (first == 0) "array" else "packed")
                put("reads", BATCH.toLong() * rounds)
                put("arrayNanos", elapsed[0])
                put("packedNanos", elapsed[1])
                put("checksum", checksum[0])
                put("arrayFlagBytes", fixture.size)
                put("packedFlagBytes", ((fixture.size + 63L) / 64) * 8)
            })
        }
    }

    private fun run(f: Fixture, workload: Workload, random: Boolean, packed: Boolean, rounds: Int): Long =
        if (packed) exercise(f, workload, random, rounds,
            { f.packed.eval(it, f.assignment, f.domains) }, f.packed::intChanged,
            f.packed::boolChanged, f.packed::clear)
        else exercise(f, workload, random, rounds,
            { f.array.eval(it, f.assignment, f.domains) }, f.array::intChanged,
            f.array::boolChanged, f.array::clear)

    private inline fun exercise(
        f: Fixture,
        workload: Workload,
        random: Boolean,
        rounds: Int,
        eval: (Int) -> Long,
        intChanged: (Int) -> Unit,
        boolChanged: (Int) -> Unit,
        clear: () -> Unit,
    ): Long {
        var checksum = 0L
        for (round in 0 until rounds) for (step in 0 until BATCH) {
            val index = if (random) f.probes[step] else step and (f.size - 1)
            if (workload != Workload.CACHED && step and 15 == 0) {
                val variable = index / 8
                f.assignment.setInt(variable, ((step + round) % 1000).toLong())
                intChanged(variable)
                f.assignment.setBool(variable, !f.assignment.boolValue(variable))
                boolChanged(variable)
            }
            if ((workload == Workload.MIXED && step and 4095 == 0) ||
                (workload == Workload.RESET_HEAVY && step and 255 == 0)) clear()
            checksum += eval(index)
        }
        return checksum
    }

    private enum class Workload { CACHED, MIXED, RESET_HEAVY }

    private class Fixture(val size: Int) {
        val assignment = Assignment(size, size * 2)
        val domains = Array(size * 2) { IntDomain(0, 2000) }
        private val network = InvariantNetwork(List(size) { index ->
            if (index and 1 == 0) DefinitionalSweep.SweepNode.Bool2Int(size + index, index / 8)
            else DefinitionalSweep.SweepNode.IntDef(
                FunctionalObjective.Plus(size + index, FunctionalObjective.Operand.v(index / 8),
                    FunctionalObjective.Operand.c(1)), intArrayOf(index / 8))
        }, size * 2, size)
        val array = DefinitionValues(network)
        val packed = PackedDefinitionValues(network)
        val probes = Random(42).let { rng -> IntArray(BATCH) { rng.nextInt(size) } }

        fun prepare() {
            for (variable in 0 until size) {
                assignment.setInt(variable, (variable % 37 + 3).toLong())
                assignment.setBool(variable, variable and 1 == 0)
            }
            array.clear()
            packed.clear()
            for (index in 0 until size) {
                check(array.eval(index, assignment, domains) == packed.eval(index, assignment, domains))
            }
            array.pending.clear()
            packed.pending.clear()
        }
    }

    // Keep the cache operations identical to DefinitionValues; only validity storage differs.
    private class PackedDefinitionValues(private val network: InvariantNetwork) {
        private val valid = Bits(network.size)
        private val values = LongArray(network.size)
        val pending = Bits.full(network.size)

        fun clear() {
            valid.clear()
            pending.setRange(0, pending.size)
        }

        fun intChanged(variable: Int) {
            for (node in network.intReaders(variable)) invalidate(node)
            val writer = network.intWriter(variable)
            if (writer >= 0) pending.set(writer)
        }

        fun boolChanged(variable: Int) {
            for (node in network.boolReaders(variable)) invalidate(node)
            val writer = network.boolWriter(variable)
            if (writer >= 0) pending.set(writer)
        }

        private fun invalidate(node: Int) {
            valid.clear(node)
            pending.set(node)
        }

        fun eval(index: Int, assignment: Assignment, domains: Array<IntDomain>): Long {
            if (!valid[index]) {
                values[index] = network.node(index).eval(assignment, domains)
                valid.set(index)
            }
            return values[index]
        }
    }
}
