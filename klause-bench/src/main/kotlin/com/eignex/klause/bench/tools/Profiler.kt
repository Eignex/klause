package com.eignex.klause.bench.tools

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
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
 * Java Flight Recorder profiler for the `profile=` filter. Records the selected region,
 * prints top methods and writes a `.jfr` under `build/`. [ProfileScope.SOLVE] excludes
 * parsing and setup. Short runs may yield too few samples for a useful profile.
 */
internal object Profiler {
    private const val CLI_CALLER_DEPTH = 3
    private const val MODEL_CALLER_DEPTH = 4
    private val mechanismPackages = listOf(
        "com.eignex.klause.util.",
        "com.eignex.klause.bench.",
        "com.eignex.klause.lp.engine.",
        "com.eignex.klause.lp.lattice.",
        "com.eignex.klause.simplex.",
        "com.eignex.klause.arithmetic.",
    )

    private val contextEntries = linkedMapOf(
        "open-theory entry" to listOf(
            "com.eignex.klause.solver.pipeline.ResumableOpenTheory.runSlice",
            "com.eignex.klause.solver.pipeline.OpenTheoryEngine.OpenTheoryRun.advance",
        ),
        "retained-query entry" to listOf(
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.check",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.decide",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.prepared",
            "com.eignex.klause.solver.pipeline.RetainedLinearQueries.prepareBooleanCenter",
        ),
        "neighborhood entry" to listOf(
            "com.eignex.klause.solver.pipeline.ExactNeighborhood.advance",
            "com.eignex.klause.solver.pipeline.OpenExactNeighborhood.check",
        ),
        "presolve probe entry" to listOf(
            "com.eignex.klause.lp.LpProbeSolver.probe",
        ),
        "source preparation entry" to listOf(
            "com.eignex.klause.theory.qflra.QfLiraSolverKt.sharedExactLiraSource",
            "com.eignex.klause.solver.pipeline.PreparedSearchComponents.prepareSource",
        ),
    )

    fun summarizeCli(file: File) = summarizeCli(file, System.out)

    fun summarizeCli(file: File, output: PrintStream) {
        val appendContexts = CliAppendContexts()
        val cpu = HashMap<String, Long>()
        val phases = HashMap<String, Long>()
        val allocation = HashMap<String, Long>()
        val cpuCallers = HashMap<String, Long>()
        val allocationCallers = HashMap<String, Long>()
        val cpuModelCallers = HashMap<String, Long>()
        val allocationModelCallers = HashMap<String, Long>()
        val cpuContexts = HashMap<String, Long>()
        val allocationContexts = HashMap<String, Long>()
        RecordingFile(file.toPath()).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                when (event.eventType.name) {
                    "jdk.ExecutionSample" -> {
                        val frames = event.stackTrace?.frames.orEmpty()
                        appendContexts.record(frames.size) {
                            frames.map { "${it.method.type.name}.${it.method.name}" }
                        }
                        topMethod(event)?.let { cpu.merge(it, 1L, Long::plus) }
                        phases.merge(cliPhase(event), 1L, Long::plus)
                        cpuCallers.merge(cliOperationCallers(event), 1L, Long::plus)
                        cpuModelCallers.merge(cliModelCallers(event), 1L, Long::plus)
                        cpuContexts.merge(cliContextCallers(event), 1L, Long::plus)
                    }
                    "jdk.ObjectAllocationSample" -> {
                        val weight = event.getLong("weight")
                        val frames = event.stackTrace?.frames.orEmpty()
                        appendContexts.record(frames.size, weight) {
                            frames.map { "${it.method.type.name}.${it.method.name}" }
                        }
                        allocation.merge(cliPhase(event), weight, Long::plus)
                        allocationCallers.merge(cliOperationCallers(event), weight, Long::plus)
                        allocationModelCallers.merge(cliModelCallers(event), weight, Long::plus)
                        allocationContexts.merge(cliContextCallers(event), weight, Long::plus)
                    }
                }
            }
        }
        printTable("whole CLI: CPU samples by phase (stack attribution)", phases, 40, output)
        printTable("whole CLI: CPU self-samples", cpu, 60, output)
        printTable("whole CLI: CPU samples by captured callers", cpuCallers, 60, output)
        printTable("whole CLI: sampled allocation weight by phase (estimated bytes)", allocation, 40, output)
        printTable("whole CLI: sampled allocation weight by captured callers (estimated bytes)",
            allocationCallers, 60, output)
        printTable("whole CLI: CPU samples by captured model-facing callers", cpuModelCallers, 60, output)
        printTable("whole CLI: sampled allocation weight by captured model-facing callers (estimated bytes)",
            allocationModelCallers, 60, output)
        printTable("whole CLI: CPU samples by captured context markers and model-facing callers",
            cpuContexts, Int.MAX_VALUE, output)
        printTable(
            "whole CLI: sampled allocation weight by captured context markers and " +
                "model-facing callers (estimated bytes)",
            allocationContexts, Int.MAX_VALUE, output,
        )
        val appendix = ByteArrayOutputStream()
        PrintStream(appendix, false, Charsets.UTF_8).use { stream ->
            for ((title, counts) in appendContexts.tables) printTable(title, counts, Int.MAX_VALUE, stream)
        }
        check(appendix.size() <= 256 * 1024) { "Append context output exceeded its limit" }
        output.write(appendix.toByteArray())
    }

    private fun cliContextCallers(event: RecordedEvent): String {
        val stack = event.stackTrace?.frames.orEmpty().map { "${it.method.type.name}.${it.method.name}" }
        return "${cliContextMarkers(stack)} | ${cliModelCallers(stack)}"
    }

    internal fun cliContextMarkers(stack: List<String>): String {
        val methods = stack.map { it.replace('$', '.') }
        val markers = contextEntries.filterValues { entries ->
            entries.any { entry -> methods.any { it == entry || it.startsWith("$entry.") } }
        }.keys
        return when (markers.size) {
            0 -> "unknown: no captured context marker"
            1 -> "observed: ${markers.single()}"
            else -> "multiple observed: ${markers.joinToString(" + ")}"
        }
    }

    private fun cliOperationCallers(event: RecordedEvent): String =
        cliOperationCallers(event.stackTrace?.frames.orEmpty().map { "${it.method.type.name}.${it.method.name}" })

    internal fun cliOperationCallers(stack: List<String>): String = stack.asSequence()
        .filter {
            it.startsWith("com.eignex.klause.") &&
                !it.startsWith("com.eignex.klause.util.") && !it.startsWith("com.eignex.klause.bench.")
        }
        .take(CLI_CALLER_DEPTH)
        .joinToString(" <- ")
        .ifEmpty { "no captured Klause operation" }

    private fun cliModelCallers(event: RecordedEvent): String =
        cliModelCallers(event.stackTrace?.frames.orEmpty().map { "${it.method.type.name}.${it.method.name}" })

    internal fun cliModelCallers(stack: List<String>): String = stack.asSequence()
        .filter { frame ->
            frame.startsWith("com.eignex.klause.") && mechanismPackages.none { prefix -> frame.startsWith(prefix) }
        }
        .take(MODEL_CALLER_DEPTH)
        .joinToString(" <- ")
        .ifEmpty { "no captured model-facing caller" }

    private fun cliPhase(event: RecordedEvent): String =
        cliPhase(event.stackTrace?.frames.orEmpty().map { "${it.method.type.name}.${it.method.name}" })

    internal fun cliPhase(stack: List<String>): String {
        val methods = stack.map { it.replace('$', '.') }
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
            methods.any {
                it.contains(".GreedyInit.") || it.contains("LocalSearchEngine.greedyRepairPass")
            } -> "local-search seeding"
            methods.any { it.contains("SourceDrivenStrategy.pickMove") } -> "local-search move selection"
            methods.any { it.contains("LocalSearchState.apply") } -> "local-search move application"
            methods.any { it.contains("LocalSearchEngine") } -> "local-search loop"
            methods.any { it.contains(".presolve.") } -> "presolve"
            methods.any { it.contains("BacktrackSolver") || it.contains(".backtrack.") } -> backtrackPhase(methods)
            methods.any {
                it.contains("PreparedSearchComponents.<init>") ||
                    it.contains("PreparedSearchComponents.prepareSource") ||
                    it.contains("QfLiraSolverKt.sharedExactLiraSource") ||
                    it.contains("ComponentPlanKt.componentPlan") ||
                    it.contains("PreparedSourcePlanKt.prepareExecutionPlan")
            } -> "shared source preparation"
            methods.any {
                it.contains("ExactLiraSearchComponent.installSource") ||
                    it.contains("ExactLiraSearchComponent.assertSource")
            } -> "shared theory source installation"
            methods.any {
                it.contains("SearchSession.initialize") || it.contains("SearchSession.prepareInitialization") ||
                    it.contains("ClauseSearchComponent.initialize") ||
                    it.contains("ExactLiraSearchComponent.initialize")
            } -> "shared root initialization"
            methods.any { it.endsWith("SearchSession.check") } -> "shared model check"
            methods.any { it.contains("SearchSession.propagate") } -> "shared propagation"
            methods.any { it.contains("PreparedSearchRegistrations.open") } -> "shared session opening"
            methods.any {
                it.contains("SearchRun.next") || it.contains("SearchRun.advanceFrame") ||
                    it.contains("SearchRun.backtrack")
            } -> "shared search"
            else -> "other or unclassified"
        }
        val context = when {
            methods.any { it.contains("Alns") && it.endsWith(".bootstrapIncumbent") } -> "ALNS bootstrap: "
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
        methods.any {
            it.contains("PropagationSession.<init>") || it.contains("PropagationState.advanceRootFixpoint") ||
                it.contains("PropagationState.beginRootFixpoint")
        } -> "backtrack root propagation"
        methods.any {
            it.contains("ResumableMinimize.<init>") || it.contains("ResumableSatisfaction.<init>") ||
                it.contains("CpSatisfactionTraversal.<init>") || it.contains("PropagationPreparation.advance")
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

    private fun printTable(title: String, counts: Map<String, Long>, topN: Int, output: PrintStream = System.out) {
        val total = counts.values.sum()
        output.println("=== $title ===")
        if (total == 0L) {
            output.println("  (no samples)")
            return
        }
        counts.entries.sortedByDescending { it.value }.take(topN).forEach { (method, n) ->
            val pct = n.toDouble() * 100.0 / total
            output.println("  ${"%5.1f%%".format(Locale.ROOT, pct)}  ${"%9d".format(Locale.ROOT, n)}  $method")
        }
    }
}
