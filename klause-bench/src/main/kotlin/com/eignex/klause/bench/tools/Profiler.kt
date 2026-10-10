package com.eignex.klause.bench.tools

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.io.File
import java.time.Duration
import java.util.Locale

/** What to sample. CPU/WALL both map to JFR `jdk.ExecutionSample` (JFR samples on-CPU Java
 *  stacks and does not cleanly separate the two); ALLOC additionally aggregates
 *  `jdk.ObjectAllocationSample` weights. */
internal enum class ProfileEvent { CPU, WALL, ALLOC }

/** Which region of the run to profile. SOLVE wraps only the measurement, so corpus fetch +
 *  format parse + MiniZinc compile (the resolve step) are discounted; ALL wraps everything,
 *  matching the whole-JVM gradle `-PasyncProfiler` agent. */
internal enum class ProfileScope { SOLVE, ALL }

internal data class ProfileConfig(
    val event: ProfileEvent = ProfileEvent.CPU,
    val scope: ProfileScope = ProfileScope.SOLVE,
    val topN: Int = 40,
    val outFile: File = File("build/bench-prof.jfr"),
)

/**
 * In-harness profiler built on Java Flight Recorder. Wraps a block in a JFR recording and
 * prints a flat top-method table on completion, then leaves the raw `.jfr` under `build/` for
 * deeper inspection in JMC / `jfr print`.
 *
 * This is the *baked-in* profiling path selected with the `profile=` bench filter. It differs
 * from the gradle `-PasyncProfiler` agent in two ways: it needs no native library, and it can
 * be scoped to the solve region only ([ProfileScope.SOLVE]) so parsing/setup is excluded from
 * the sample set. Sampling is statistical — short solves yield few samples, so pair `profile=`
 * with a long-running selection (e.g. a fixed-budget search target) for a meaningful table.
 */
internal object Profiler {
    fun summarizeCli(file: File) {
        val cpu = HashMap<String, Long>()
        val phases = HashMap<String, Long>()
        val allocation = HashMap<String, Long>()
        RecordingFile(file.toPath()).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                when (event.eventType.name) {
                    "jdk.ExecutionSample" -> {
                        topMethod(event)?.let { cpu.merge(it, 1L, Long::plus) }
                        phases.merge(cliPhase(event), 1L, Long::plus)
                    }
                    "jdk.ObjectAllocationSample" -> {
                        val weight = event.getLong("weight")
                        allocation.merge(cliPhase(event), weight, Long::plus)
                    }
                }
            }
        }
        printTable("whole CLI: CPU samples by phase (stack attribution)", phases, 40)
        printTable("whole CLI: CPU self-samples", cpu, 60)
        printTable("whole CLI: sampled allocation weight by phase (estimated bytes)", allocation, 40)
    }

    private fun cliPhase(event: RecordedEvent): String {
        val methods = event.stackTrace?.frames.orEmpty().map { "${it.method.type.name}.${it.method.name}" }
        val localState = methods.any { it.contains("LocalSearchState.<init>") }
        val initializing = methods.any {
            it.contains("LocalSearchEngine.newMinimizeState") || it.contains("LocalSearchEngine.newSatisfyState") ||
                it.contains("LocalSearchEngine.recomputeInitial")
        }
        val phase = when {
            methods.any {
                it.contains("LocalSearchProblem.<init>") || it.contains("LocalSearchPreparation.get") ||
                    (it.contains("LocalSearchProblem") && it.contains("preparation"))
            } -> "local-search projection preparation"
            localState -> "local-search state allocation"
            methods.any { it.contains("LocalSearchEngine.installInvariants") } -> "local-search invariant setup"
            initializing -> "local-search seeding"
            methods.any { it.contains("LocalSearchEngine.restartAndRepair") } -> "local-search restart seeding"
            methods.any { it.contains("SourceDrivenStrategy.pickMove") } -> "local-search move selection"
            methods.any { it.contains("LocalSearchState.apply") } -> "local-search move application"
            methods.any { it.contains("LocalSearchEngine") } -> "local-search loop"
            methods.any { it.contains(".presolve.") } -> "presolve"
            methods.any { it.contains("BacktrackSolver") || it.contains(".backtrack.") } -> backtrackPhase(methods)
            else -> "other or unclassified"
        }
        val context = when {
            methods.any { it.contains("Alns.bootstrapIncumbent") } -> "ALNS bootstrap: "
            methods.any { it.contains("RepairOperator") || it.contains("Repair.repair") } -> "ALNS repair: "
            else -> ""
        }
        return context + phase
    }

    private fun backtrackPhase(methods: List<String>): String = when {
        methods.any { it.contains("PropagationProblem") } -> "backtrack projection preparation"
        methods.any { it.contains("PropagationState.<init>") || it.contains("NativeSatState.<init>") } ->
            "backtrack state allocation"
        methods.any { it.contains("LpEngine.<init>") } -> "backtrack LP preparation"
        methods.any { it.contains("PropagationSession.<init>") } -> "backtrack root propagation"
        methods.any {
            it.contains("ResumableMinimize.<init>") || it.contains("ResumableSatisfaction.<init>") ||
                it.contains("CpSatisfactionTraversal.<init>")
        } -> "backtrack initialization"
        else -> "backtrack search"
    }

    fun <T> record(cfg: ProfileConfig, block: () -> T): T {
        val recording = Recording()
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1))
        if (cfg.event == ProfileEvent.ALLOC) recording.enable("jdk.ObjectAllocationSample")
        recording.start()
        try {
            return block()
        } finally {
            recording.stop()
            cfg.outFile.parentFile?.mkdirs()
            recording.dump(cfg.outFile.toPath())
            recording.close()
            summarize(cfg)
        }
    }

    private fun summarize(cfg: ProfileConfig) {
        val cpu = HashMap<String, Long>()
        val alloc = HashMap<String, Long>()
        RecordingFile(cfg.outFile.toPath()).use { rf ->
            while (rf.hasMoreEvents()) {
                val e = rf.readEvent()
                when (e.eventType.name) {
                    "jdk.ExecutionSample" -> topMethod(e)?.let { cpu.merge(it, 1L, Long::plus) }

                    "jdk.ObjectAllocationSample" -> if (cfg.event == ProfileEvent.ALLOC) {
                        val w = if (e.hasField("weight")) e.getLong("weight") else 1L
                        topMethod(e)?.let { alloc.merge(it, w, Long::plus) }
                    }
                }
            }
        }
        println()
        printTable(
            "profile: top methods by self-samples (${cfg.event.name.lowercase()}, scope=${cfg.scope.name.lowercase()})",
            cpu,
            cfg.topN,
        )
        if (cfg.event == ProfileEvent.ALLOC) {
            printTable(
                "profile: allocation weight by top frame (bytes)",
                alloc,
                cfg.topN,
            )
        }
        println("wrote ${cfg.outFile.path} (open in JMC or `jfr print`)")
    }

    /** Top Java frame of an event's stack, as `Type.method`, or null when no Java frame. */
    private fun topMethod(e: RecordedEvent): String? {
        val frame = e.stackTrace?.frames?.firstOrNull { it.isJavaFrame } ?: return null
        return "${frame.method.type.name}.${frame.method.name}"
    }

    private fun printTable(title: String, counts: Map<String, Long>, topN: Int) {
        val total = counts.values.sum()
        println("=== $title ===")
        if (total == 0L) {
            println("  (no samples)")
            return
        }
        counts.entries.sortedByDescending { it.value }.take(topN).forEach { (method, n) ->
            val pct = n.toDouble() * 100.0 / total
            println("  ${"%5.1f%%".format(Locale.ROOT, pct)}  ${"%9d".format(Locale.ROOT, n)}  $method")
        }
    }
}
