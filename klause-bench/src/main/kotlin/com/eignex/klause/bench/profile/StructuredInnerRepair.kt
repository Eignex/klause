package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.ImplicitSeeding
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An existing catalog recipe replayed as a cold inner repair owner with the acquisition walk's definition sweep,
 * per-move invariants, implicit seeding switch, gradient view and linear shaping. [build] returns a fresh recipe
 * because strategies carry per-search state.
 */
internal class StructuredInner private constructor(
    val recipe: String,
    val build: () -> LocalSearchRecipe,
    val sweep: DefinitionalSweep?,
    val gradient: IncrementalObjective?,
    private val network: InvariantNetwork?,
    private val implicitSeeds: List<IntArray>,
    val preparation: JsonObject,
) {
    val owner: String get() = "ls:$recipe"

    /** Ownership and definition-closure facts of one captured scope; nothing is released to admit a move. */
    fun mismatches(freedBools: IntArray, freedInts: IntArray, maintained: Boolean, seeded: Boolean) =
        buildJsonObject {
            val bools = freedBools.toHashSet()
            val ints = freedInts.toHashSet()
            var pinnedOutputs = 0
            var fixedOutputs = 0
            var freedOutputs = 0
            val activeNetwork = network?.takeIf { maintained }
            if (activeNetwork != null) for (index in 0 until activeNetwork.size) {
                val node = activeNetwork.node(index)
                val outputFree = if (node.outIsBool) node.out in bools else node.out in ints
                val inputFree = node.intInputs.any { it in ints } || node.boolInputs.any { it in bools }
                if (outputFree) freedOutputs++
                if (!outputFree && inputFree) pinnedOutputs++
                if (outputFree && !inputFree) fixedOutputs++
            }
            put("maintainedDefinitions", activeNetwork?.size ?: 0)
            put("freedDefinedOutputs", freedOutputs)
            put("pinnedOutputsReadingFreedInputs", pinnedOutputs)
            put("freedOutputsWithOnlyPinnedInputs", fixedOutputs)
            put("implicitSeedingOnRestart", seeded)
            put("implicitOwnersAtCenter", "none: a warm start seeds no owner until a restart")
            put("implicitSeedScopes", buildJsonArray {
                if (seeded) for (scope in implicitSeeds) {
                    val free = scope.count { it in ints }
                    if (free == 0) continue
                    add(buildJsonObject {
                        put("size", scope.size)
                        put("freed", free)
                        put("partial", free < scope.size)
                        put("singleFreed", free == 1)
                    })
                }
            })
        }

    /** Outcome of one cold inner repair. */
    class Run(
        val sample: Sample?, val reason: TerminationReason?, val stats: SolveStats, val preparationNs: Long,
        val report: JsonObject,
    )

    /** Repair one captured scope with [seed] under [budget], spending at most [allowance] decision equivalents. */
    fun repair(
        problem: BakedProblem, objective: LinearObjective, center: Sample, pins: Assumptions, freedBools: IntArray,
        freedInts: IntArray, stop: Cancellation, budget: NodeBudget, seed: Long, allowance: Long,
    ): Run {
        val started = System.nanoTime()
        val completion = if (problem.numRealVars == 0) null else
            Class.forName("com.eignex.klause.portfolio.LeafRealCompletion").constructors
                .single { it.parameterCount == 2 }.newInstance(problem, objective) as CandidateCompletion
        val selected = build()
        val maintained = sweep != null && selected.perMoveInvariants
        val solver = LocalSearchSolver(problem, strategy = selected.strategy,
            optimizeStrategy = selected.optimizeStrategy, definitionalSweep = sweep, perMoveInvariants = maintained,
            seedImplicitOnRestart = selected.seedImplicitOnRestart, completion = completion)
        val tally = Class.forName(TALLY).getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val engine = solver.javaClass.methods.single {
            it.name.substringBefore('$') == "getEngine" && it.parameterCount == 0
        }.invoke(solver)
        engine.javaClass.methods.single { it.name.substringBefore('$') == "setCompoundTally" }.invoke(engine, tally)
        val preparation = System.nanoTime() - started
        val available = minOf(allowance, (budget.limit - budget.spent).coerceAtLeast(0L))
        val params = LocalSearchParams(assumptions = pins, initialAssignment = center, cancellation = stop,
            nodeBudget = budget, randomSeed = seed, maxInstructions = available / 5 * 4 + available % 5 * 4 / 5,
            costShaping = CostShaping.Linear(lambda = 1.0), lsObjective = gradient, normalizeWeightsByClass = true)
        var best: Sample? = null
        var slices = 0L
        var verdict: MinimizeResult? = null
        val stats = solver.resumable(objective, params).use { run ->
            while (verdict == null && !stop()) {
                slices++
                verdict = run.runSlice(stop, Long.MAX_VALUE, -1L) { best = it.sample }
            }
            run.stats
        }
        val result = verdict
        val reason = when (result) {
            null -> if (stop.deadline()?.hasPassedNow() == true) TerminationReason.Timeout
                else TerminationReason.Cancelled
            is MinimizeResult.BestFound -> result.reason
            is MinimizeResult.Unknown -> result.reason
            is MinimizeResult.Unbounded -> TerminationReason.Unsupported
            else -> TerminationReason.SearchExhausted
        }
        val sample = result?.assignment ?: best
        return Run(sample, reason, stats, preparation, buildJsonObject {
            put("recipe", recipe)
            put("catalogLabel", selected.label)
            put("initialization", "captured center; caller pins re-applied; definitions reconciled under pins")
            put("randomSeed", seed)
            put("costShaping", "linear 1")
            put("gradientView", gradient != null)
            put("normalizeWeightsByClass", true)
            put("perMoveInvariants", maintained)
            put("slices", slices)
            put("closed", true)
            put("restarts", stats.search.restarts.sum)
            put("moves", stats.ls.moves.sum)
            put("compounds", buildJsonObject {
                for (name in listOf("Offered", "PinRejected", "Trimmed", "Collapsed", "Admitted", "Applied")) {
                    put(name.replaceFirstChar(Char::lowercase), tally.javaClass.getMethod("get$name")
                        .invoke(tally) as Long)
                }
                put("scope", "strategy candidate sinks before Boolean closure, and committed moves")
            })
            put("mismatches", mismatches(freedBools, freedInts, maintained, selected.seedImplicitOnRestart))
        })
    }

    companion object {
        private const val TALLY = "com.eignex.klause.localsearch.CompoundTally"

        /** Prepare [recipe] once per working model; preparation costs are retained, not charged to repairs. */
        fun prepare(
            recipe: String, build: () -> LocalSearchRecipe, problem: BakedProblem, sweep: DefinitionalSweep?,
            gradient: IncrementalObjective?, sharedPreparation: JsonObject,
        ): StructuredInner {
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            val thread = Thread.currentThread().threadId()
            val started = System.nanoTime()
            val allocation = bean.getThreadAllocatedBytes(thread)
            val network = sweep?.network(problem.numIntVars, problem.numBoolVars)
            val seeding = ImplicitSeeding(problem)
            val seeds = seeding.implicitSeedFactors.map { problem.factors[it].intVars.copyOf() }
            return StructuredInner(recipe, build, sweep, gradient, network, seeds, buildJsonObject {
                put("recipe", recipe)
                put("sharedAcquisitionPreparation", sharedPreparation)
                put("analysisElapsedNs", System.nanoTime() - started)
                put("analysisThreadBytes", bean.getThreadAllocatedBytes(thread) - allocation)
                put("maintainedDefinitions", network?.size ?: 0)
                put("electedImplicitGlobals", seeding.electedImplicit.size)
                put("implicitSeedGlobals", seeds.size)
                put("chargedToOwnerAllowance", false)
            })
        }
    }
}

/** The declared descent each checked improvement continues with; its costs stay outside the owner allowances. */
internal class RepairDescent(val sweep: DefinitionalSweep?, val directory: File) {
    fun continueFrom(
        problem: BakedProblem, objective: LinearObjective, repaired: Sample, seed: Long, owner: String, center: Int,
        verify: (Sample, Cancellation) -> RepairOwnerReplay.PointCheck,
    ): JsonObject {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val started = System.nanoTime()
        val allocation = bean.getThreadAllocatedBytes(thread)
        val budget = NodeBudget(DESCENT_WORK)
        val stop = Cancellation.after(2000.milliseconds)
        directory.mkdirs()
        val name = owner.replace(Regex("[^A-Za-z0-9-]"), "_")
        val descent = FiniteConflictSelection.descend(problem, objective, repaired, seed, sweep, stop, budget,
            directory.resolve("$name-$center.jsonl"))
        val elapsed = System.nanoTime() - started
        val bytes = bean.getThreadAllocatedBytes(thread) - allocation
        val checked = verify(descent.sample, Cancellation.after(2000.milliseconds))
        return buildJsonObject {
            put("declaredDescentRule", FiniteConflictSelection.DESCENT_RULE)
            put("maxDescentMoves", FiniteConflictSelection.DESCENT_MOVES)
            put("unpinned", true)
            put("endpoint", descent.endpoint)
            put("policyLocalEndpoint", descent.endpoint in setOf("PolicyLocalOptimum", "FeasibleStall"))
            put("moves", descent.moves)
            put("workAllowance", DESCENT_WORK)
            put("workSpent", budget.spent)
            put("objective", objective.evaluateExact(descent.sample).toString())
            put("furtherWorkingImprovement", objective.evaluateExact(descent.sample) <
                objective.evaluateExact(repaired))
            put("nativeSourceAccepted", checked.accepted)
            put("sourceObjective", checked.objective?.toString())
            put("changedWorkingCoordinates", (0 until repaired.numIntVars).count {
                repaired.exactIntValue(it) != descent.sample.exactIntValue(it)
            } + (0 until repaired.numBoolVars).count { repaired.boolValue(it) != descent.sample.boolValue(it) })
            put("elapsedNs", elapsed)
            put("threadBytes", bytes)
            put("includedInOwnerAllowance", false)
            put("independentWitnessCheck", false)
        }
    }

    private companion object {
        const val DESCENT_WORK = 2_000L
    }
}
