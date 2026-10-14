package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import kotlinx.serialization.Serializable
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random

/** Measures NValue proposals at a CP incumbent, separately from end-to-end solving. */
object NValueNeighborhoodProfile {
    private val completionType = Class.forName("com.eignex.klause.factor.global.NValueClauseCompletion")
    private val completionConstructor = completionType.getConstructor(
        LocalSearchState::class.java, MoveSink::class.java, IntArray::class.java,
    )
    private val completionMethod = completionType.getMethod("complete", Move::class.java)

    @Serializable
    private data class CompletionObservation(val cost: Long, val parts: List<String>, val residuals: Map<String, Int>)

    @Serializable
    private data class RepairObservation(
        val cost: Long, val move: String, val residuals: Map<String, Int>, val clauses: Map<Int, List<String>>,
    )

    @Serializable
    private data class Proposal(
        val distinct: Int,
        val count: Long,
        val cost: Long,
        val objective: Double,
        val residuals: Map<String, Int>,
        val parts: Int,
        val clauseDependencies: Map<Int, List<String>>,
        val repairTrace: List<RepairObservation> = emptyList(),
        val scratchCompletion: CompletionObservation? = null,
    )

    @Serializable
    private data class FactorRun(
        val factor: Int,
        val maintained: Boolean,
        val maintainedCount: Boolean,
        val maintainedInputs: Int,
        val maintainedPresence: Int,
        val distinct: Int,
        val calls: Int,
        val proposalNs: Long,
        val proposalBytes: Long?,
        val emitted: Int,
        val reductions: List<Proposal>,
    )

    /** Use suite, problem, seed, timeout and calls; writes diagnostic JSON under OUT. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it) { "Expected key=value" }
            it.substringBefore('=') to it.substringAfter('=')
        }
        require((options.keys - setOf("suite", "problem", "seed", "timeout", "calls")).isEmpty())
        val suite = requireNotNull(options["suite"])
        val name = requireNotNull(options["problem"])
        val seed = options["seed"]?.toLong() ?: 17L
        val timeout = options["timeout"]?.toLong() ?: 10000L
        val calls = options["calls"]?.toInt() ?: 32
        require(timeout in 1..60000 && calls in 1..256)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(File(out, "provenance.json").path, InstalledBuild.reference)
        val ref = Catalog.uncapped(suite).problems.single { it.name == name }
        val entry = Runners.resolve(ref)
        val problem = entry.problem
        require(problem.numRealVars == 0)
        val objective = requireNotNull(entry.objective)
        val baked = problem.bake()
        val deadline = System.nanoTime() + timeout * 1000000
        val params = (entry.searchParams ?: BacktrackPresets.conflictDriven()).copy(
            randomSeed = seed,
            cancellation = Cancellation { System.nanoTime() >= deadline },
        )
        val result = BacktrackSolver(baked).minimize(objective, params)
        val sample = result.assignment
        File(out, "settings.txt").writeText(
            "revision=${Reports.readGitSha()}\nsuite=$suite\nproblem=$name\nseed=$seed\n" +
                "timeout=$timeout\ncalls=$calls\nobjective=${result.objectiveValue}\n" +
                "jvm=${ManagementFactory.getRuntimeMXBean().inputArguments}\n",
        )
        if (sample == null) {
            File(out, "no-incumbent.txt").writeText(result.toString())
            return
        }
        Reports.writeJson(
            File(out, "incumbent-ints.json").path,
            LongArray(problem.numIntVars) { sample.intValue(it) },
        )
        Reports.writeJson(
            File(out, "incumbent-bools.json").path,
            BooleanArray(problem.numBoolVars) { sample.boolValue(it) },
        )
        val allocation = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported }
        val thread = Thread.currentThread().threadId()
        val runs = ArrayList<FactorRun>()
        val model = LocalSearchModel.of(baked)
        val pins = requireNotNull(model.pinsUnder(Assumptions.None))
        for (maintained in listOf(false, true)) {
            val state = LocalSearchState(model, Random(seed), assumptions = pins)
            if (maintained) {
                state.invariants = entry.definitionalSweep?.network(problem.numIntVars, problem.numBoolVars)
                state.moveSink.setInvariants(state.invariants)
            }
            restore(state, sample)
            check(state.cost == 0L) { "CP incumbent fails original local-search factors" }
            for ((fid, factor) in problem.factors.withIndex()) {
                if (factor !is NValue) continue
                val originalDistinct = distinct(state, factor)
                val sink = MoveSink(pins).also { it.setInvariants(state.invariants) }
                var ns = 0L
                var bytes = 0L
                var emitted = 0
                val reductions = LinkedHashSet<Proposal>()
                repeat(calls) { _ ->
                    sink.clear()
                    sink.proposer = fid
                    val beforeBytes = allocation?.getThreadAllocatedBytes(thread)
                    val start = System.nanoTime()
                    state.factors[fid].proposeStructuredMoves(state, fid, sink)
                    ns += System.nanoTime() - start
                    if (beforeBytes != null) {
                        bytes += checkNotNull(allocation).getThreadAllocatedBytes(thread) - beforeBytes
                    }
                    check(
                        (0 until problem.numIntVars).all {
                            state.assignment.intValue(it) == sample.intValue(it)
                        } && (0 until problem.numBoolVars).all {
                            state.assignment.boolValue(it) == sample.boolValue(it)
                        },
                    ) { "Proposal changed the incumbent" }
                    val moves = sink.list
                    emitted += moves.size
                    for (move in moves) {
                        state.apply(move)
                        val nextDistinct = distinct(state, factor)
                        if (nextDistinct < originalDistinct) {
                            val residuals = (0 until problem.numFactors)
                                .filter { state.factors[it].isViolated(state, it) }
                                .groupingBy { problem.factors[it]::class.simpleName ?: "Factor" }.eachCount()
                            val proposal = Proposal(
                                nextDistinct, state.assignment.intValue(factor.n), state.cost,
                                objective.evaluate(state.assignment), residuals,
                                if (move is Move.Compound) move.parts.size else 1,
                                clauseDependencies(state),
                            )
                            reductions += proposal.copy(
                                repairTrace = clauseRepairTrace(state, factor),
                                scratchCompletion = scratchCompletion(state, factor, move, sample),
                            )
                        }
                        restore(state, sample)
                    }
                }
                val network = state.invariants
                runs += FactorRun(
                    fid, maintained, network?.isDefinedInt(factor.n) == true,
                    factor.xs.distinct().count { network?.isDefinedInt(it) == true },
                    factor.presents.map(Lit::variable).distinct().count { network?.isDefinedBool(it) == true },
                    originalDistinct, calls, ns, allocation?.let { bytes }, emitted, reductions.toList(),
                )
            }
        }
        Reports.writeJson(File(out, "neighborhoods.json").path, runs)
    }

    private fun restore(state: LocalSearchState, sample: Sample) {
        for (v in 0 until state.problem.numIntVars) state.assignment.setInt(v, sample.intValue(v))
        for (v in 0 until state.problem.numBoolVars) state.assignment.setBool(v, sample.boolValue(v))
        state.resetStepCounters()
        state.recompute()
    }

    private fun clauseDependencies(state: LocalSearchState): Map<Int, List<String>> = buildMap {
        for (fid in 0 until state.problem.numFactors) {
            val clause = state.problem.factors[fid] as? Clause ?: continue
            if (!state.factors[fid].isViolated(state, fid)) continue
            val descriptions = arrayListOf("literals=${clause.literals.contentToString()}")
            val network = state.invariants
            if (network != null) {
                for (i in 0 until network.size) {
                    val node = network.node(i)
                    if (!node.outIsBool || node.out !in clause.boolVars) continue
                    descriptions += "b${node.out}=${state.assignment.boolValue(node.out)} " +
                        "${node::class.simpleName} boolInputs=${node.boolInputs.contentToString()} " +
                        "intInputs=${node.intInputs.contentToString()}"
                    for (b in node.boolInputs) for (rowId in state.projection.boolOccurrences[b]) {
                        val row = state.problem.factors[rowId] as? ReifiedLinear ?: continue
                        if (row.auxBoolVar != b) continue
                        val constants = row.integerConstants ?: continue
                        descriptions += "b$b=${state.assignment.boolValue(b)} ${row.op} " +
                            "vars=${row.vars.contentToString()} bound=${constants.bound} " +
                            "coeffs=${row.vars.indices.map { constants.coeff(it) }} " +
                            "values=${row.vars.map { state.assignment.intValue(it) }}"
                    }
                }
            }
            put(fid, descriptions)
        }
    }

    private fun clauseRepairTrace(state: LocalSearchState, factor: NValue): List<RepairObservation> {
        if (state.cost == 0L) return emptyList()
        val diagnostic = LocalSearchState(state.model, Random(0), state.assumptions, state.projection)
        diagnostic.invariants = state.invariants
        restore(diagnostic, state.assignment.snapshot())
        var pins = diagnostic.assumptions
        for (v in factor.xs + intArrayOf(factor.n)) pins = pins.withInt(v, diagnostic.assignment.intValue(v))
        val repairs = MoveSink(pins).also {
            it.setInvariants(diagnostic.invariants)
            it.setOwners(state.seeding.ownerInt)
        }
        val trace = ArrayList<RepairObservation>()
        repeat(2) { _ ->
            val fid = (0 until diagnostic.problem.numFactors).firstOrNull { id ->
                diagnostic.problem.factors[id] is Clause && diagnostic.factors[id].isViolated(diagnostic, id)
            } ?: return trace
            repairs.clear()
            diagnostic.factors[fid].proposeRepairMoves(diagnostic, fid, repairs)
            val next = repairs.list.minByOrNull { diagnostic.netDelta(it) } ?: return trace
            diagnostic.apply(next)
            trace += RepairObservation(diagnostic.cost, next.toString(), residuals(diagnostic),
                clauseDependencies(diagnostic))
        }
        return trace
    }

    private fun scratchCompletion(
        state: LocalSearchState, factor: NValue, move: Move, incumbent: Sample,
    ): CompletionObservation? {
        if (state.invariants == null || state.cost == 0L) return null
        val diagnostic = LocalSearchState(state.model, Random(0), state.assumptions, state.projection)
        diagnostic.invariants = state.invariants
        restore(diagnostic, incumbent)
        val eligibility = MoveSink(diagnostic.assumptions).also { it.setInvariants(diagnostic.invariants) }
        // The component stays internal to the library; the JVM probe follows its constructor ABI.
        val helper = completionConstructor.newInstance(diagnostic, eligibility, factor.xs + intArrayOf(factor.n))
        val completion = completionMethod.invoke(helper, move) as Move
        diagnostic.apply(completion)
        val parts = if (completion is Move.Compound) completion.parts else listOf(completion)
        return CompletionObservation(diagnostic.cost, parts.map { it.toString() }, residuals(diagnostic))
    }

    private fun residuals(state: LocalSearchState): Map<String, Int> = (0 until state.problem.numFactors)
        .filter { state.factors[it].isViolated(state, it) }
        .groupingBy { state.problem.factors[it]::class.simpleName ?: "Factor" }.eachCount()

    private fun distinct(state: LocalSearchState, factor: NValue): Int = factor.xs.indices.filter {
        factor.presents.isEmpty() || Lit.evaluate(
            factor.presents[it],
            state.assignment.boolValue(Lit.variable(factor.presents[it])),
        )
    }.map { state.assignment.intValue(factor.xs[it]) }.distinct().size
}
