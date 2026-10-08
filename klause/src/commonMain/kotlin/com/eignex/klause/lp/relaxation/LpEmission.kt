package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.util.Cancellation

internal enum class LpEmissionKind { OBJECTIVE, CIRCUIT, ENERGETIC, TIME_INDEXED, FACTOR, BOOLEAN_RLT }

internal data class LpEmissionRegion(val kind: LpEmissionKind, val index: Int = -1)

internal class LpEmissionDependencies(
    integers: Map<Int, IntDomain>,
    booleans: Map<Int, Boolean?>,
    private val honorsOpenSides: Boolean,
) {
    private val integers = integers.toMap()
    private val booleans = booleans.toMap()

    fun matches(domains: RelaxationDomains): Boolean = domains.honorsOpenSides == honorsOpenSides &&
        integers.all { (variable, domain) -> domains.intDomain(variable) === domain } &&
        booleans.all { (variable, value) -> domains.boolValue(variable) == value }
}

internal class LpEmissionDomains(private val source: RelaxationDomains) : RelaxationDomains {
    private val integers = HashMap<Int, IntDomain>()
    private val booleans = HashMap<Int, Boolean?>()
    override val honorsOpenSides: Boolean get() = source.honorsOpenSides

    override fun intDomain(varId: Int): IntDomain = source.intDomain(varId).also { integers[varId] = it }
    override fun boolValue(varId: Int): Boolean? = source.boolValue(varId).also { booleans[varId] = it }

    fun dependencies(): LpEmissionDependencies = LpEmissionDependencies(integers, booleans, honorsOpenSides)
}

internal class LpEmission(
    val region: LpEmissionRegion,
    val relaxation: LpRelaxation,
    val dependencies: LpEmissionDependencies,
)

internal class LpEmissionUpdate(
    val emissions: List<LpEmission>,
    val changed: List<Int>,
    private val publish: () -> Unit,
) {
    fun commit() = publish()
}

internal class LpEmissionCache(private val relaxer: CpToLpRelaxation) {
    private class Change(val depth: Int, val index: Int, val previous: LpEmission?)

    private val current = arrayOfNulls<LpEmission>(relaxer.emissionRegions.size)
    private val trail = ArrayList<Change>()
    private var generation = 0L
    var depth: Int = 0
        private set
    var emittedRegions: Long = 0L
        private set

    fun retract(targetDepth: Int) {
        require(targetDepth in 0..depth)
        check(generation < Long.MAX_VALUE)
        while (trail.isNotEmpty() && trail.last().depth > targetDepth) {
            val change = trail.removeAt(trail.lastIndex)
            current[change.index] = change.previous
        }
        depth = targetDepth
        generation++
    }

    fun refresh(
        domains: RelaxationDomains,
        targetDepth: Int,
        cancellation: Cancellation = Cancellation.Never,
    ): LpEmissionUpdate = prepare(domains, targetDepth, cancellation).also { it.commit() }

    fun prepare(
        domains: RelaxationDomains,
        targetDepth: Int,
        cancellation: Cancellation = Cancellation.Never,
    ): LpEmissionUpdate {
        require(targetDepth >= 0)
        check(generation < Long.MAX_VALUE)
        val expectedGeneration = generation
        val next = current.copyOf()
        var retained = trail.size
        while (retained > 0 && trail[retained - 1].depth > targetDepth) {
            val change = trail[--retained]
            next[change.index] = change.previous
        }
        val saved = HashSet<Int>()
        for (index in retained - 1 downTo 0) {
            val change = trail[index]
            if (change.depth < targetDepth) break
            saved.add(change.index)
        }
        val changes = ArrayList<Change>()
        val changed = ArrayList<Int>()
        for (index in next.indices) {
            if (cancellation()) throw LpAssemblyCancelled()
            val previous = next[index]
            if (previous == null || !previous.dependencies.matches(domains)) {
                val emitted = relaxer.emitRegion(relaxer.emissionRegions[index], domains, cancellation)
                emittedRegions++
                if (targetDepth > 0 && saved.add(index)) changes.add(Change(targetDepth, index, previous))
                next[index] = emitted
            }
            if (next[index] !== current[index]) changed.add(index)
        }
        if (cancellation()) throw LpAssemblyCancelled()
        return LpEmissionUpdate(next.map { requireNotNull(it) }, changed) {
            check(generation == expectedGeneration) { "stale emission update" }
            while (trail.size > retained) trail.removeAt(trail.lastIndex)
            for (change in changes) {
                if (change.depth > 0) trail.add(change)
            }
            next.copyInto(current)
            depth = targetDepth
            generation++
        }
    }
}
