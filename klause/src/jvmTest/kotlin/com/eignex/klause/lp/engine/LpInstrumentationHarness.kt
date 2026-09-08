package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class LpWave0Workload(
    val name: String,
    val model: LpModel,
    private val settings: LpReplaySettings,
    private val events: List<LpReplayEvent>,
) {
    val capture: LpCapture get() = LpCapture.capture(model, settings, events)
}

internal object LpWave0ReplaySlice {
    const val LABEL = "w0.4-replay-v1"
    const val SEED = 0x4c50573034L

    fun workloads(): List<LpWave0Workload> {
        val settings = LpReplaySettings(
            label = LABEL,
            seed = SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT,
            pivotLimit = 0,
            workLimit = 0L,
            trackDegeneracy = false,
        )
        val empty = LpBuilder().build(Sense.MINIMIZE)

        val feasible = LpBuilder().apply {
            val x = addVar(0L, 10L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)

        val infeasible = LpBuilder().apply {
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)

        val continuous = LpBuilder().apply {
            val x = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 3.0)
        }.build(Sense.MINIMIZE)

        val boundedSettings = LpReplaySettings(
            label = LABEL,
            seed = SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            pivotLimit = 1,
            refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT,
        )
        val cancelSettings = LpReplaySettings(
            label = LABEL,
            seed = SEED,
            solverKind = LpReplaySolverKind.PERSISTENT,
            componentSplit = false,
            cancellationPollLimit = 1,
            refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT,
        )
        return listOf(
            LpWave0Workload("empty", empty, settings, listOf(LpReplayEvent.Solve())),
            LpWave0Workload("feasible-bounded", feasible, settings, listOf(LpReplayEvent.Solve())),
            LpWave0Workload(
                "infeasible-bounded",
                infeasible,
                settings,
                listOf(
                    LpReplayEvent.Solve(),
                    LpReplayEvent.ResolveGated(booleanArrayOf(false)),
                    LpReplayEvent.ResolveGated(booleanArrayOf(true)),
                ),
            ),
            LpWave0Workload("cancelled", cancellationModel(), cancelSettings, listOf(LpReplayEvent.Solve())),
            LpWave0Workload("continuous-witness", continuous, settings, listOf(LpReplayEvent.Solve())),
            LpWave0Workload("pivot-bounded", coveringModel(), boundedSettings, listOf(LpReplayEvent.Solve())),
        )
    }

    private fun cancellationModel(): LpModel {
        val builder = LpBuilder()
        val columns = IntArray(32) { builder.addVar(0L, 9L, cost = (it % 5 + 1).toLong()) }
        repeat(16) { row ->
            builder.addRow(
                IntArray(4) { columns[(row * 3 + it) % columns.size] },
                LongArray(4) { 1L },
                Relation.GE,
                (row % 6 + 2).toLong(),
            )
        }
        return builder.build(Sense.MINIMIZE)
    }

    private fun coveringModel(): LpModel {
        val builder = LpBuilder()
        val variables = IntArray(4) { builder.addVar(0L, 3L, cost = 1L) }
        builder.addRow(intArrayOf(variables[0], variables[1]), longArrayOf(1L, 1L), Relation.GE, 3L)
        builder.addRow(intArrayOf(variables[1], variables[2]), longArrayOf(1L, 1L), Relation.GE, 4L)
        builder.addRow(intArrayOf(variables[2], variables[3]), longArrayOf(1L, 1L), Relation.GE, 5L)
        builder.addRow(intArrayOf(variables[0], variables[3]), longArrayOf(1L, 1L), Relation.GE, 2L)
        return builder.build(Sense.MINIMIZE)
    }
}

class LpInstrumentationHarness {
    @Test
    fun `observer overhead stays below wave zero gate`() {
        if (System.getenv(ENABLE_ENV) != "1") return

        val workloads = LpWave0ReplaySlice.workloads()
        repeat(WARMUPS) {
            runObserverArm(workloads, OBSERVER_BATCH, active = false)
            runObserverArm(workloads, OBSERVER_BATCH, active = true)
        }
        val baseline = LongArray(REPETITIONS)
        val active = LongArray(REPETITIONS)
        val pairedDelta = DoubleArray(REPETITIONS)
        var expectedDigest: Long? = null
        var expectedWork: WorkSignature? = null
        var expectedObserverEvents: Int? = null
        repeat(REPETITIONS) { repetition ->
            val firstActive = repetition % 2 == 1
            val first = runObserverArm(workloads, OBSERVER_BATCH, active = firstActive)
            val second = runObserverArm(workloads, OBSERVER_BATCH, active = !firstActive)
            val baselineRun = if (firstActive) second else first
            val activeRun = if (firstActive) first else second
            assertEquals(baselineRun.digest, activeRun.digest)
            assertEquals(baselineRun.work, activeRun.work)
            expectedDigest?.let { assertEquals(it, activeRun.digest) }
            expectedWork?.let { assertEquals(it, activeRun.work) }
            expectedObserverEvents?.let { assertEquals(it, activeRun.observerEvents) }
            expectedDigest = activeRun.digest
            expectedWork = activeRun.work
            expectedObserverEvents = activeRun.observerEvents
            baseline[repetition] = baselineRun.elapsedNanos
            active[repetition] = activeRun.elapsedNanos
            pairedDelta[repetition] = percentage(
                activeRun.elapsedNanos - baselineRun.elapsedNanos,
                baselineRun.elapsedNanos,
            )
        }

        val overhead = median(pairedDelta)
        val pairedIqr = interquartileRange(pairedDelta)
        val baselineMedian = median(baseline)
        val activeMedian = median(active)
        println(
            "LP_INSTRUMENTATION label=$OBSERVER_LABEL seed=none engine=RevisedSimplex " +
                "factory=ProductionLpEngineFactory componentSplit=false observer=LpCertificationObserver " +
                "workloads=${workloads.joinToString(",") { it.name }} batch=$OBSERVER_BATCH " +
                "warmups=$WARMUPS repetitions=$REPETITIONS ordering=alternating-paired " +
                "statistic=median-paired-percent baselineNs=${summary(baseline)} activeNs=${summary(active)} " +
                "pairedDeltaPct=${summary(pairedDelta)} pairedIqrPct=${rounded(pairedIqr)} " +
                "gatePct=$MAX_OVERHEAD_PCT minArmNs=$MIN_ARM_NANOS maxPairedIqrPct=$MAX_PAIRED_IQR_PCT " +
                "pivots=${checkNotNull(expectedWork).pivots} workOps=${expectedWork.workOps} " +
                "observerEvents=${checkNotNull(
                    expectedObserverEvents,
                )} semanticDigest=${checkNotNull(expectedDigest)} " +
                "baselineSamplesNs=${baseline.contentToString()} activeSamplesNs=${active.contentToString()} " +
                "pairedDeltaSamplesPct=${
                    pairedDelta.joinToString(prefix = "[", postfix = "]") { rounded(it).toString() }
                }",
        )
        reportAuxiliaryCosts(workloads)
        assertTrue(baselineMedian >= MIN_ARM_NANOS, "baseline median $baselineMedian ns is below validity floor")
        assertTrue(activeMedian >= MIN_ARM_NANOS, "active median $activeMedian ns is below validity floor")
        assertTrue(
            pairedIqr <= MAX_PAIRED_IQR_PCT,
            "observer paired IQR $pairedIqr percentage points exceeds $MAX_PAIRED_IQR_PCT",
        )
        assertTrue(
            overhead <= MAX_OVERHEAD_PCT,
            "observer median paired overhead $overhead% exceeds $MAX_OVERHEAD_PCT%",
        )
    }

    private fun reportAuxiliaryCosts(workloads: List<LpWave0Workload>) {
        repeat(WARMUPS) {
            workloads.forEach { it.capture }
            workloads.forEach { LpCapture.decode(it.capture.encode()) }
            workloads.forEach {
                LpReplay.replay(it.capture, LpReplayHarnessTest.IndependentExactValidator)
            }
        }
        val capture = samples { repeat(CAPTURE_BATCH) { workloads.forEach { it.capture } } }
        val codec = samples {
            repeat(CODEC_BATCH) { workloads.forEach { LpCapture.decode(it.capture.encode()) } }
        }
        val validator = pairedSamples(
            baseline = { workloads.forEach { LpReplay.replay(it.capture) } },
            active = {
                workloads.forEach {
                    LpReplay.replay(it.capture, LpReplayHarnessTest.IndependentExactValidator)
                }
            },
        )
        println(
            "LP_AUXILIARY_COST label=$OBSERVER_LABEL kind=capture batch=$CAPTURE_BATCH " +
                "repetitions=$REPETITIONS statistic=median elapsedNs=${summary(capture)} " +
                "samplesNs=${capture.contentToString()} gate=excluded",
        )
        println(
            "LP_AUXILIARY_COST label=$OBSERVER_LABEL kind=codec-round-trip batch=$CODEC_BATCH " +
                "repetitions=$REPETITIONS statistic=median elapsedNs=${summary(codec)} " +
                "samplesNs=${codec.contentToString()} gate=excluded",
        )
        println(
            "LP_AUXILIARY_COST label=$OBSERVER_LABEL kind=independent-validator " +
                "batch=1 repetitions=$REPETITIONS statistic=median-paired-percent " +
                "pairedDeltaPct=${summary(validator)} " +
                "pairedDeltaSamplesPct=${
                    validator.joinToString(prefix = "[", postfix = "]") { rounded(it).toString() }
                } " +
                "gate=excluded",
        )
    }

    private fun runObserverArm(workloads: List<LpWave0Workload>, batch: Int, active: Boolean): ArmResult {
        val observer = if (active) CountingObserver() else null
        val metrics = MetricsSink()
        val context = LpSolveContext(engineFactory = MeasuringFactory(metrics))
        var digest = 0L
        val start = System.nanoTime()
        repeat(batch) {
            workloads.forEach { workload ->
                val result = solveAndCertify(
                    workload.model,
                    componentSplit = false,
                    observer = observer,
                    context = context,
                )
                digest = digest * 31L + result.verdict.ordinal
                digest = digest * 31L + (result.float?.objective?.toRawBits() ?: 0L)
                digest = digest * 31L + (result.exactLowerBound ?: 0L)
                digest = digest * 31L + (result.farkasRay?.size ?: 0)
                digest = digest * 31L + (result.exactPrimal?.size ?: 0)
            }
        }
        if (observer != null) {
            assertEquals(metrics.pivots, observer.pivots)
            assertEquals(metrics.workOps, observer.workOps)
        }
        return ArmResult(
            System.nanoTime() - start,
            digest,
            WorkSignature(metrics.pivots, metrics.workOps),
            observer?.events ?: 0,
        )
    }

    private fun samples(block: () -> Unit): LongArray = LongArray(REPETITIONS) {
        val start = System.nanoTime()
        block()
        System.nanoTime() - start
    }

    private fun pairedSamples(baseline: () -> Unit, active: () -> Unit): DoubleArray = DoubleArray(REPETITIONS) {
        val firstActive = it % 2 == 1
        val firstStart = System.nanoTime()
        if (firstActive) active() else baseline()
        val first = System.nanoTime() - firstStart
        val secondStart = System.nanoTime()
        if (firstActive) baseline() else active()
        val second = System.nanoTime() - secondStart
        val baselineNanos = if (firstActive) second else first
        val activeNanos = if (firstActive) first else second
        percentage(activeNanos - baselineNanos, baselineNanos)
    }

    private fun percentage(numerator: Long, denominator: Long): Double = numerator.toDouble() * 100.0 / denominator

    private fun median(values: DoubleArray): Double = values.sorted()[values.size / 2]

    private fun median(values: LongArray): Long = values.sorted()[values.size / 2]

    private fun interquartileRange(values: DoubleArray): Double {
        val sorted = values.sorted()
        return sorted[3 * sorted.size / 4] - sorted[sorted.size / 4]
    }

    private fun summary(values: LongArray): String {
        val sorted = values.sorted()
        return "${sorted[sorted.size / 2]}(${sorted.first()}..${sorted.last()})"
    }

    private fun summary(values: DoubleArray): String {
        val sorted = values.sorted()
        return "${rounded(sorted[sorted.size / 2])}(${rounded(sorted.first())}..${rounded(sorted.last())})"
    }

    private fun rounded(value: Double): Double = (value * 100.0).roundToLong() / 100.0

    private class CountingObserver : LpCertificationObserver {
        var pivots = 0
            private set
        var workOps = 0L
            private set
        var events = 0
            private set

        override fun observe(certifier: LpCertifier, success: Boolean) {
            events++
        }

        override fun observeExactInput(accepted: Boolean) {
            events++
        }

        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) {
            events++
            pivots += metrics.pivots
            workOps += metrics.workOps
        }
    }

    private class MetricsSink {
        var pivots = 0
        var workOps = 0L
    }

    private class MeasuringFactory(private val sink: MetricsSink) : LpEngineFactory by ProductionLpEngineFactory {
        override fun newGeneralSolver(model: LpModel, cancellation: Cancellation): LpSolver =
            MeasuringSolver(ProductionLpEngineFactory.newGeneralSolver(model, cancellation), sink)
    }

    private class MeasuringSolver(private val delegate: LpSolver, private val sink: MetricsSink) :
        LpSolver by delegate {
        override fun close() {
            sink.pivots += delegate.lastMetrics.pivots
            sink.workOps += delegate.lastMetrics.workOps
            delegate.close()
        }
    }

    private class ArmResult(val elapsedNanos: Long, val digest: Long, val work: WorkSignature, val observerEvents: Int)

    private data class WorkSignature(val pivots: Int, val workOps: Long)

    private companion object {
        const val ENABLE_ENV = "KLAUSE_LP_INSTRUMENTATION"
        const val OBSERVER_LABEL = "w0-instrumentation-v2"
        const val OBSERVER_BATCH = 5_000
        const val CAPTURE_BATCH = 100
        const val CODEC_BATCH = 50
        const val WARMUPS = 3
        const val REPETITIONS = 9
        const val MAX_OVERHEAD_PCT = 5.0
        const val MIN_ARM_NANOS = 300_000_000L
        const val MAX_PAIRED_IQR_PCT = 10.0
    }
}
