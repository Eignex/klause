package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.table.Element
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.randomValue
import com.eignex.klause.solver.objective.LinearObjective
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random

fun main(args: Array<String>) {
    val input = args.firstOrNull() ?: "synthetic"
    val count = args.getOrNull(1)?.toInt() ?: 2048
    val seed = args.getOrNull(2)?.toInt() ?: 3
    val problem: Problem
    val sweep: DefinitionalSweep?
    if (input == "synthetic") {
        val width = 256
        val factors = ArrayList<Factor>()
        factors.add(Element(0, 4, longArrayOf(1, 2, 3), true, 0))
        for (i in 0 until width) factors.add(Product(if (i == 0) 4 else i + 4, 3, i + 5))
        factors.add(Linear(intArrayOf(1), intArrayOf(width + 4), LinearOp.EQ, 0))
        problem = Problem(0, width + 5, Array(width + 5) { IntDomain(0, 2) }, factors.toTypedArray())
        sweep = DefinitionalSweep.infer(problem, intArrayOf(4))
    } else {
        val parsed = Xcsp3.parse(File(input).readText())
        problem = parsed.problem
        sweep = DefinitionalSweep.infer(problem, parsed.definedVars)
    }
    val network = checkNotNull(sweep) { "the input has no definitions" }.network(problem.numIntVars, problem.numBoolVars)
    val model = LocalSearchModel.open(problem)
    val projection = LocalSearchProblem(problem)
    val objectives = LinearObjective(
        boolWeights = LongArray(problem.numBoolVars) { (it % 7).toLong() },
        intCoefficients = LongArray(problem.numIntVars) { (it % 7).toLong() },
    )
    val searched = (0 until problem.numIntVars).filter { !network.isDefinedInt(it) && model.domains[it].min != model.domains[it].max }
    check(searched.isNotEmpty())
    val allocation = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    for (round in 0..6) {
        val state = LocalSearchState(model, Random(seed), projection = projection)
        state.invariants = network
        state.restart()
        state.weights.factorWeights.fill(1.25)
        val rng = Random(seed + 1)
        val moves = List(128) {
            val first = searched[rng.nextInt(searched.size)]
            val second = searched[rng.nextInt(searched.size)]
            Move.Compound(listOf(
                Move.IntSet(first, state.rootDomains[first].randomValue(rng)),
                Move.IntSet(second, state.rootDomains[second].randomValue(rng)),
            ))
        }
        for (move in moves) {
            val sample = state.assignment.snapshot()
            val degrees = state.factorDegree.copyOf()
            val payload = state.longPayload.copyOf()
            val cost = state.cost
            val best = state.bestCostSeen
            val touches = state.tabu.touchCount.copyOf()
            val configuration = state.intConfChange.copyOf()
            val boolConfiguration = state.boolConfChange.copyOf()
            val breaks = state.boolBreakCount.copyOf()
            val makes = state.boolMakeCount.copyOf()
            val active = BooleanArray(problem.numFactors) { state.violated.contains(it) }
            state.netDelta(move)
            state.weightedNetDelta(move)
            state.objectiveDelta(objectives, move)
            check(sample == state.assignment.snapshot())
            check(degrees.contentEquals(state.factorDegree))
            check(payload.contentEquals(state.longPayload))
            check(touches.contentEquals(state.tabu.touchCount))
            check(configuration.contentEquals(state.intConfChange))
            check(boolConfiguration.contentEquals(state.boolConfChange))
            check(breaks.contentEquals(state.boolBreakCount) && makes.contentEquals(state.boolMakeCount))
            check(active.contentEquals(BooleanArray(problem.numFactors) { state.violated.contains(it) }))
            check(cost == state.cost && best == state.bestCostSeen)
        }
        var digest = 1L
        var probes = 0L
        fun mix(value: Long) { digest = digest * 31L + value }
        val startBytes = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())
        val start = System.nanoTime()
        repeat(count) { index ->
            val move = moves[index % moves.size]
            mix(state.netDelta(move))
            mix(state.weightedNetDelta(move).toRawBits())
            mix(checkNotNull(state.objectiveDelta(objectives, move)).toRawBits())
            mix(state.breakScore(move).toLong())
            mix(state.makeScore(move).toLong())
            probes += 5
            if (index % 16 == 0) state.apply(move)
        }
        val ns = System.nanoTime() - start
        val bytes = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - startBytes
        val degrees = state.factorDegree.copyOf()
        val cost = state.cost
        for (i in 0 until problem.numIntVars) mix(state.assignment.intValue(i))
        for (b in 0 until problem.numBoolVars) mix(if (state.assignment.boolValue(b)) 1 else 0)
        for (d in degrees) mix(d.toLong())
        for (i in 0 until problem.numFactors) mix(if (state.violated.contains(i)) 1 else 0)
        for (value in state.longPayload) mix(value)
        for (value in state.intPayload) mix(value.toLong())
        for (value in state.doublePayload) mix(value.toRawBits())
        for (value in state.boolBreakCount) mix(value.toLong())
        for (value in state.boolMakeCount) mix(value.toLong())
        for (value in state.intConfChange) mix(if (value) 1 else 0)
        for (value in state.boolConfChange) mix(if (value) 1 else 0)
        for (value in state.tabu.lastTouched) mix(value)
        for (weight in state.weights.factorWeights) mix(weight.toRawBits())
        for (touch in state.tabu.touchCount) mix(touch.toLong())
        mix(state.cost)
        mix(state.bestCostSeen)
        mix(state.step)
        val rngState = state.rng.nextLong()
        state.recompute()
        check(cost == state.cost && degrees.contentEquals(state.factorDegree))
        println("DEFINITION_PROBE round=$round count=$count probes=$probes definitions=${network.size} ns=$ns bytes=$bytes digest=$digest rng=$rngState")
    }
}
