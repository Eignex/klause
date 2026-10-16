package com.eignex.klause.bench.profile

import java.lang.reflect.Field

internal class LinearQueryInputProbe(component: Any, samples: LinearQueryInputSamples<Any>) {
    enum class Skip {
        UNINITIALIZED,
        NO_OWNER,
        NO_SOLVER,
        UNSUPPORTED_SOLVER,
        NO_INPUT,
        STALE,
        UNCACHED,
        INVALID_STORAGE,
    }

    private val loader = component.javaClass.classLoader
    private val delegate = field("theory.qflra.ExactLiraSearchComponent", "lpDelegate").get(component) as Lazy<*>
    private val lpOwner = field("lp.bounding.LpPropagator", "owner")
    private val scopedSolver = field("lp.engine.LpScopedSolver", "solver")
    private val scopedTrail = field("lp.engine.LpScopedSolver", "trail")
    private val trailState = field("lp.engine.LpBoundTrail", "state")
    private val solverClass = type("lp.engine.RevisedSimplex")
    private val solvedState = field("lp.engine.RevisedSimplex", "solvedExactState")
    private val stateModel = field("lp.engine.LpExactState", "model")
    private val modelStorage = field("lp.engine.ExactLpModel", "storageCache")
    private val modelUnits = field("lp.engine.LpLayoutStorage", "total")
    private val trailUnits = field("lp.engine.LpExactState", "trailStorageUnits")
    private val skipped = LongArray(Skip.entries.size)
    private val gate = LinearQueryInputGate(samples, ::cachedWeight)
    val matchingReads: Long get() = gate.matchingReads

    fun observe() {
        if (!delegate.isInitialized()) return skip(Skip.UNINITIALIZED)
        val owner = lpOwner.get(delegate.value) ?: return skip(Skip.NO_OWNER)
        val solver = scopedSolver.get(owner) ?: return skip(Skip.NO_SOLVER)
        if (solver.javaClass !== solverClass) return skip(Skip.UNSUPPORTED_SOLVER)
        val input = solvedState.get(solver)
        gate.observe(input, trailState.get(scopedTrail.get(owner)))
    }

    private fun cachedWeight(input: Any): Long? {
        val storage = modelStorage.get(stateModel.get(input)) ?: return null
        val model = modelUnits.getLong(storage)
        val trail = trailUnits.getLong(input)
        return if (model < 0L || trail < 0L || model > Long.MAX_VALUE - trail) -1L else model + trail
    }

    fun coverage(): Map<String, Long> = Skip.entries.take(4).associate { it.name to skipped[it.ordinal] } +
        gate.coverage()

    private fun skip(reason: Skip) {
        if (skipped[reason.ordinal] < Long.MAX_VALUE) skipped[reason.ordinal]++
    }

    private fun type(name: String): Class<*> = Class.forName("com.eignex.klause.$name", false, loader)
    private fun field(type: String, name: String): Field = this.type(type).getDeclaredField(name).apply {
        isAccessible = true
    }
}

internal class LinearQueryInputGate(
    private val samples: LinearQueryInputSamples<Any>,
    private val readCachedWeight: (Any) -> Long?,
) {
    private val skipped = LongArray(4)
    var matchingReads = 0L
        private set

    fun observe(input: Any?, current: Any?) {
        if (input == null) return skip(0)
        if (input !== current) return skip(1)
        if (matchingReads < Long.MAX_VALUE) matchingReads++
        val units = readCachedWeight(input)
        if (units == null) {
            skip(2)
        } else if (units < 0L) {
            skip(3)
        }
        samples.observe(input, units)
    }

    fun coverage(): Map<String, Long> = listOf("NO_INPUT", "STALE", "UNCACHED", "INVALID_STORAGE")
        .mapIndexed { index, name -> name to skipped[index] }.toMap()

    private fun skip(index: Int) {
        if (skipped[index] < Long.MAX_VALUE) skipped[index]++
    }
}
