package com.eignex.klause.lp

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem

// The root-propagation fold once the bake has run, the declaration before it: presolve relaxes a source
// model whose bake is deferred. Node-invariant; a per-node bound comes from the propagation session, and
// reading a root box where the live one is meant loses every decision above the node. Whether the
// endpoints are the model's own is [statesLowerBound] / [statesUpperBound].
internal fun Problem.rootDomainOf(v: Int): IntDomain = finiteIntDomain(v)

// Copies the whole table; read one column through [rootDomainOf].
internal fun Problem.rootDomainsOf(): Array<IntDomain> = finiteIntDomains()

// A side closed by an invented box bounds the search, not the model, so a row or cut leaning on it cannot
// be published as globally valid. A bound proved by the root fold or presolve counts as stated.
// `Problem.withIntDomains` cannot tell invention from proof: it boxes a wide column while keeping the wide
// bound, and separating those needs provenance the source model does not carry.
internal fun Problem.statesLowerBound(v: Int): Boolean = intBounds.hasLower(v)

internal fun Problem.statesUpperBound(v: Int): Boolean = intBounds.hasUpper(v)

internal fun Problem.statesBothBounds(v: Int): Boolean = statesLowerBound(v) && statesUpperBound(v)

internal fun Problem.statesBothBounds(vars: IntArray): Boolean = vars.all { statesBothBounds(it) }

// A `{0, 1}` box over a column the source left open is a search restriction, not a binary declaration.
internal fun Problem.statesBinary(v: Int): Boolean =
    statesBothBounds(v) && rootDomainOf(v).let { it.min == 0L && it.max == 1L }

// For derivations whose validity rests on a sign rather than on the endpoint's value.
internal fun Problem.statesLowerBoundAtLeast(v: Int, floor: Long): Boolean =
    statesLowerBound(v) && rootDomainOf(v).min >= floor

// A size estimate must decline exactly where the build declines, so the box and the stated-bounds reading
// travel together. Built once per model and shared across its factors, since the table read copies.
internal class RootBoxes(
    private val problem: Problem,
    private val domains: Array<IntDomain> = problem.rootDomainsOf(),
) {

    fun domain(v: Int): IntDomain = domains[v]

    fun statesBothBounds(v: Int): Boolean = problem.statesBothBounds(v)

    fun statesBothBounds(vars: IntArray): Boolean = problem.statesBothBounds(vars)
}

internal suspend fun SequenceScope<Unit>.prepareRootBoxes(problem: Problem): RootBoxes {
    val domains = arrayOfNulls<IntDomain>(problem.numIntVars)
    yield(Unit)
    for (v in domains.indices) {
        domains[v] = problem.rootDomainOf(v)
        if ((v + 1) % 256 == 0) yield(Unit)
    }
    @Suppress("UNCHECKED_CAST")
    return RootBoxes(problem, domains as Array<IntDomain>)
}

internal suspend fun SequenceScope<Unit>.prepareStatesBothBounds(boxes: RootBoxes, vars: IntArray): Boolean {
    for ((position, v) in vars.withIndex()) {
        if (!boxes.statesBothBounds(v)) return false
        if ((position + 1) % 256 == 0) yield(Unit)
    }
    return true
}

internal suspend fun SequenceScope<Unit>.prepareStatesBothBounds(problem: Problem, vars: IntArray): Boolean {
    for ((position, v) in vars.withIndex()) {
        if (!problem.statesBothBounds(v)) return false
        if ((position + 1) % 256 == 0) yield(Unit)
    }
    return true
}
