package com.eignex.klause.bench.tools

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.io.File
import java.io.PrintStream
import java.util.Locale

/**
 * Phase and caller tables for a klause-cli JFR recording. A lab `profileCli` case sets `KLAUSE_LAB_PROFILE_DIR`,
 * and [com.eignex.klause.bench.metric.SolverInvocation] prints these tables for the `cli.jfr` the CLI wrote there.
 */
internal object CliProfile {
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

    fun summarizeCli(file: File, output: PrintStream = System.out, label: String = "whole CLI") {
        val cpu = HashMap<String, Long>()
        val phases = HashMap<String, Long>()
        val allocation = HashMap<String, Long>()
        val cpuCallers = HashMap<String, Long>()
        val allocationCallers = HashMap<String, Long>()
        val cpuModelCallers = HashMap<String, Long>()
        val allocationModelCallers = HashMap<String, Long>()
        val cpuContexts = HashMap<String, Long>()
        val allocationContexts = HashMap<String, Long>()
        val cpuPolynomialCheckpoints = HashMap<String, Long>()
        RecordingFile(file.toPath()).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                when (event.eventType.name) {
                    "jdk.ExecutionSample" -> {
                        topMethod(event)?.let { cpu.merge(it, 1L, Long::plus) }
                        phases.merge(cliPhase(event), 1L, Long::plus)
                        cpuCallers.merge(cliOperationCallers(event), 1L, Long::plus)
                        cpuModelCallers.merge(cliModelCallers(event), 1L, Long::plus)
                        cpuContexts.merge(cliContextCallers(event), 1L, Long::plus)
                        val stack = event.stackTrace?.frames.orEmpty()
                            .map { "${it.method.type.name}.${it.method.name}" }
                        cpuPolynomialCheckpoints.merge(cliPolynomialCheckpointCallers(stack), 1L, Long::plus)
                    }
                    "jdk.ObjectAllocationSample" -> {
                        val weight = event.getLong("weight")
                        allocation.merge(cliPhase(event), weight, Long::plus)
                        allocationCallers.merge(cliOperationCallers(event), weight, Long::plus)
                        allocationModelCallers.merge(cliModelCallers(event), weight, Long::plus)
                        allocationContexts.merge(cliContextCallers(event), weight, Long::plus)
                    }
                }
            }
        }
        printTable("$label: CPU samples by phase (stack attribution)", phases, 40, output)
        printTable("$label: CPU self-samples", cpu, 60, output)
        printTable("$label: CPU samples by captured callers", cpuCallers, 60, output)
        printTable("$label: CPU samples by captured polynomial checkpoint callers",
            cpuPolynomialCheckpoints, Int.MAX_VALUE, output)
        printTable("$label: sampled allocation weight by phase (estimated bytes)", allocation, 40, output)
        printTable("$label: sampled allocation weight by captured callers (estimated bytes)",
            allocationCallers, 60, output)
        printTable("$label: CPU samples by captured model-facing callers", cpuModelCallers, 60, output)
        printTable("$label: sampled allocation weight by captured model-facing callers (estimated bytes)",
            allocationModelCallers, 60, output)
        printTable("$label: CPU samples by captured context markers and model-facing callers",
            cpuContexts, Int.MAX_VALUE, output)
        printTable(
            "$label: sampled allocation weight by captured context markers and " +
                "model-facing callers (estimated bytes)",
            allocationContexts, Int.MAX_VALUE, output,
        )
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

    internal fun cliPolynomialCheckpointCallers(stack: List<String>): String {
        val checkpoint = stack.indexOf("com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic.step")
        if (checkpoint < 0) return "unknown: no captured polynomial checkpoint"
        return stack.asSequence().drop(checkpoint + 1)
            .filter {
                it.startsWith("com.eignex.klause.") &&
                    !it.startsWith("com.eignex.klause.util.") && !it.startsWith("com.eignex.klause.bench.")
            }
            .take(MODEL_CALLER_DEPTH)
            .joinToString(" <- ")
            .ifEmpty { "unknown: no captured caller below polynomial checkpoint" }
    }

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

    /** Top Java frame of an event's stack, as `Type.method`, or null when no Java frame. */
    private fun topMethod(e: RecordedEvent): String? {
        val frame = e.stackTrace?.frames?.firstOrNull { it.isJavaFrame } ?: return null
        return "${frame.method.type.name}.${frame.method.name}"
    }

    private fun printTable(title: String, counts: Map<String, Long>, topN: Int, output: PrintStream) {
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
