package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpCapture
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpReplayEvent
import com.eignex.klause.lp.engine.LpReplaySettings
import com.eignex.klause.lp.engine.LpReplaySolverKind
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.certifyLpResult
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import java.lang.management.ManagementFactory
import java.security.MessageDigest

private const val EXPECTED_CAPTURE = "deb7c8bb9b3c744e71b31893b7abd1c1f1b8511d8edeecda8f965566e6d4c648"
private const val COMPARISON_WORK_LIMIT = 1_000_000L
private val xLower = SearchDecision.IntAtLeast(0, 0)
private val yLower = SearchDecision.IntAtLeast(1, 2)
private val xUpper = SearchDecision.IntAtMost(0, 2)

private data class State(val path: List<SearchDecision>, val action: Action)
private enum class Action { ROOT, PUSH_X_LOWER, PUSH_Y_LOWER, POP_ONE, PUSH_X_UPPER, POP_ROOT }
private data class Profile(
    val refactor: Int,
    val iterations: Int,
    val work: Long,
    val degeneracy: Boolean,
    val pricing: LpPricingOptions,
)
private data class Measurement(val nanos: Long, val allocation: Long?)
private data class Observed(val lowerX: Long, val upperX: Long, val lowerY: Long, val upperY: Long, val model: LpModel)

private class Owners : LpEngineFactory by ProductionLpEngineFactory {
    var created = 0
    var closed = 0
    val profiles = ArrayList<Profile>()
    val requestedWork = ArrayList<Long>()

    override fun newPersistentSolver(
        model: LpModel,
        cancellation: Cancellation,
        refactorUpdateLimit: Int,
        iterationLimit: Int,
        workLimit: Long,
        trackDegeneracy: Boolean,
        pricing: LpPricingOptions,
    ): PersistentLpSolver {
        requestedWork += workLimit
        profiles += Profile(refactorUpdateLimit, iterationLimit, COMPARISON_WORK_LIMIT, trackDegeneracy, pricing)
        created++
        val delegate = ProductionLpEngineFactory.newPersistentSolver(
            model, cancellation, refactorUpdateLimit, iterationLimit, COMPARISON_WORK_LIMIT,
            trackDegeneracy, pricing,
        )
        return object : PersistentLpSolver by delegate {
            override fun resolveBounds(allowance: LpFloatAllowance?) =
                delegate.resolveBounds(allowance?.copy(work = COMPARISON_WORK_LIMIT))

            override fun close() {
                delegate.close()
                closed++
            }
        }
    }
}

private class Consumer(val owners: Owners) : AutoCloseable {
    private val problem = Problem(
        0, 2, Array(2) { IntDomain(-3, 7) },
        arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
    )
    val engine = LpEngine(
        problem,
        LinearObjective(intCoefficients = longArrayOf(2, 1), constant = 5),
        LpParams(lpPlan = LpPlan(bounding = true)),
        SolveStatsSink(backend = "lp-lifecycle"),
        LpSolveContext(owners),
    )
    val cp = CpSearchComponent(PropagationSession(problem))
    val search: SearchSession

    init {
        engine.cpAdapter.attach(cp.session, feasibility = false)
        search = SearchSession(listOf(cp, engine.propagator))
        check(search.initialize() is com.eignex.klause.solver.search.ComponentResult.Consistent)
    }

    fun replay(path: List<SearchDecision>) {
        path.forEach { decision ->
            check(search.push(decision) is com.eignex.klause.solver.search.ComponentResult.Consistent)
        }
    }

    fun advance(action: Action) {
        when (action) {
            Action.ROOT -> Unit
            Action.PUSH_X_LOWER -> replay(listOf(xLower))
            Action.PUSH_Y_LOWER -> replay(listOf(yLower))
            Action.POP_ONE -> search.popTo(1)
            Action.PUSH_X_UPPER -> replay(listOf(xUpper))
            Action.POP_ROOT -> search.popTo(0)
        }
    }

    fun observe(): Observed {
        val x = cp.session.intDomain(0)
        val y = cp.session.intDomain(1)
        val relaxation = engine.nodeRelaxation(requireNotNull(engine.lpRelaxer), cp.session)
        check(relaxation.objectiveConstant == 5L)
        return Observed(x.min, x.max, y.min, y.max, relaxation.model)
    }

    override fun close() = engine.close()
}

private fun states(): List<State> = buildList {
    add(State(emptyList(), Action.ROOT))
    repeat(4) {
        add(State(listOf(xLower), Action.PUSH_X_LOWER))
        add(State(listOf(xLower, yLower), Action.PUSH_Y_LOWER))
        add(State(listOf(xLower), Action.POP_ONE))
        add(State(listOf(xLower, xUpper), Action.PUSH_X_UPPER))
        add(State(emptyList(), Action.POP_ROOT))
    }
}

private fun threadBytes(): Long? {
    val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return null
    return if (bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled) {
        bean.getThreadAllocatedBytes(Thread.currentThread().threadId()).takeIf { it >= 0L }
    } else null
}

private inline fun <T> measured(block: () -> T): Pair<T, Measurement> {
    val startBytes = threadBytes()
    val start = System.nanoTime()
    val result = block()
    val nanos = System.nanoTime() - start
    val endBytes = threadBytes()
    return result to Measurement(nanos, if (startBytes != null && endBytes != null && endBytes >= startBytes) {
        endBytes - startBytes
    } else null)
}

private fun digest(model: LpModel): String {
    val bytes = LpCapture.capture(
        model,
        LpReplaySettings("wave2-model-fingerprint", 0L, LpReplaySolverKind.PERSISTENT, false),
        emptyList(),
    ).encode()
    return sha256(bytes)
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun field(value: Any?): String = value?.toString() ?: "NA"
private fun printRecord(
    arm: String,
    repetition: Int,
    index: Int,
    state: Observed,
    modelHash: String,
    certified: com.eignex.klause.lp.engine.CertifiedLpResult?,
    metrics: LpSolveMetrics?,
    factors: Int?,
    preparationWork: Long?,
    preparationRefactorizations: Long?,
    setup: Measurement?,
    transition: Measurement,
    assembly: Measurement,
    solve: Measurement,
    certification: Measurement,
    disposal: Measurement?,
) {
    val primal = certified?.exactPrimal
    val values = listOf(
        "LP_LIFECYCLE", arm, repetition, index, state.lowerX, state.upperX, state.lowerY, state.upperY,
        modelHash, certified?.verdict, certified?.lowerBound, primal?.getOrNull(0), primal?.getOrNull(1),
        certified?.witness?.objective, metrics?.workOps, metrics?.pivots,
        certified?.reconstruction?.work, certified?.basisVerification?.work,
        certified?.refinement?.work, certified?.continuation?.work,
        setup?.nanos, transition.nanos, assembly.nanos, solve.nanos, certification.nanos, disposal?.nanos,
        setup?.allocation, transition.allocation, assembly.allocation, solve.allocation,
        certification.allocation, disposal?.allocation, factors, preparationWork,
        preparationRefactorizations,
    )
    println(values.joinToString("|", transform = ::field))
}

private fun runArm(arm: String, repetition: Int, sequence: List<State>): List<Observed> {
    val owners = Owners()
    val observed = ArrayList<Observed>(sequence.size)
    var profile: Profile? = null
    if (arm == "retained") {
        val (consumer, setup) = measured { Consumer(owners) }
        try {
            sequence.forEachIndexed { index, state ->
                val (_, transition) = measured { consumer.advance(state.action) }
                val (snapshot, assembly) = measured { consumer.observe() }
                observed += snapshot
                val hash = digest(snapshot.model)
                val (attempt, solve) = measured { consumer.engine.solveNode(snapshot.model, null, Cancellation.Never) }
                val metrics = attempt?.first?.lastMetrics
                val factors = attempt?.first?.lastRefactorizations
                val preparationWork = consumer.engine.propagator.metrics?.preparationWork
                val preparationRefactors = consumer.engine.propagator.metrics?.preparationRefactorizations
                val (certified, certification) = measured {
                    attempt?.let { certifyLpResult(snapshot.model, it.first, it.second) }
                }
                profile = owners.profiles.singleOrNull() ?: profile
                printRecord(arm, repetition, index, snapshot, hash, certified,
                    metrics, factors, preparationWork, preparationRefactors,
                    if (index == 0) setup else null, transition, assembly,
                    solve, certification, null)
            }
        } finally {
            val (_, disposal) = measured { consumer.close() }
            println("LP_LIFECYCLE_DISPOSAL|$arm|$repetition|${disposal.nanos}|${field(disposal.allocation)}")
        }
    } else {
        sequence.forEachIndexed { index, state ->
            val (consumer, setup) = measured { Consumer(owners) }
            try {
                val (_, transition) = measured { consumer.replay(state.path) }
                val (snapshot, assembly) = measured { consumer.observe() }
                observed += snapshot
                val hash = digest(snapshot.model)
                val (attempt, solve) = measured { consumer.engine.solveNode(snapshot.model, null, Cancellation.Never) }
                val metrics = attempt?.first?.lastMetrics
                val factors = attempt?.first?.lastRefactorizations
                val preparationWork = consumer.engine.propagator.metrics?.preparationWork
                val preparationRefactors = consumer.engine.propagator.metrics?.preparationRefactorizations
                val (certified, certification) = measured {
                    attempt?.let { certifyLpResult(snapshot.model, it.first, it.second) }
                }
                val current = owners.profiles.lastOrNull()
                check(profile == null || profile == current) { "cold profile drift: $profile != $current" }
                profile = current
                printRecord(arm, repetition, index, snapshot, hash, certified,
                    metrics, factors, preparationWork, preparationRefactors,
                    setup, transition, assembly, solve, certification, null)
            } finally {
                val (_, disposal) = measured { consumer.close() }
                println("LP_LIFECYCLE_DISPOSAL|$arm|$repetition|${disposal.nanos}|${field(disposal.allocation)}")
            }
        }
    }
    check(owners.created == owners.closed) { "$arm owner leak: ${owners.created}/${owners.closed}" }
    check(profile != null) { "$arm never used the persistent LP route" }
    println("LP_LIFECYCLE_OWNERS|$arm|$repetition|${owners.created}|${owners.closed}|$profile|" +
        "requestedWork=${owners.requestedWork.minOrNull()}..${owners.requestedWork.maxOrNull()}")
    return observed
}

fun main() {
    val sequence = states()
    val reference = runArm("retained", -1, sequence)
    val events = listOf<LpReplayEvent>(LpReplayEvent.Solve()) + reference.drop(1).flatMap { state ->
        listOf(
            LpReplayEvent.Rebind(longArrayOf(state.lowerX, state.lowerY), longArrayOf(state.upperX, state.upperY)),
            LpReplayEvent.ResolveBounds(),
        )
    }
    val capture = LpCapture.capture(
        reference.first().model,
        LpReplaySettings("wave2-cp-producer-21", 21L, LpReplaySolverKind.PERSISTENT, false),
        events,
    )
    val captureHash = sha256(capture.encode())
    check(captureHash == EXPECTED_CAPTURE) { "consumer trace drift: $captureHash" }
    println("LP_LIFECYCLE_CAPTURE|$captureHash|${sequence.size}")
    val coldWarmup = runArm("cold", -1, sequence)
    check(reference.map(::bounds) == coldWarmup.map(::bounds))
    repeat(3) { repetition ->
        val first = if (repetition % 2 == 0) "retained" else "cold"
        val second = if (first == "retained") "cold" else "retained"
        val one = runArm(first, repetition, sequence)
        val two = runArm(second, repetition, sequence)
        check(one.map(::bounds) == two.map(::bounds))
    }
}

private fun bounds(state: Observed): List<Long> =
    listOf(state.lowerX, state.upperX, state.lowerY, state.upperY)
