package com.eignex.klause.localsearch.movesource

import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableIntIntMap

/**
 * Implication-aware flip proposals. Seeds from Boolean variables of sampled violated factors; for
 * each it builds one atomic [Move.Compound] of the seed flip plus the literals that flip transitively
 * follows through the binary-implication graph
 * ([com.eignex.klause.localsearch.ImplicitSeeding.implicationGraph]), to a bounded
 * depth. Pinning `v = value` forces every literal in `graph[Lit.make(v, value)]`, so emitting the
 * forced flips together lands the move in an implication-consistent region instead of leaving a
 * cascade of single-variable violations the search must repair one flip at a time.
 *
 * Only forced literals that actually change their variable's current value enter the compound (a
 * literal already satisfied is a no-op part); a variable is pinned on first inclusion so the walk
 * never flips it twice, and a move that the graph would force in both polarities is dropped (a
 * contradiction has no consistent atomic realisation).
 *
 * [Pool.ScoreOnly] / [Phase.Infeasible] for the same reason as [EjectionChains]: a coordinated
 * multi-variable move is a score-picked plateau escape, and a destructive perturbation if taken by
 * the noise draw.
 */
class FlipAndPropagate(
    /** Cap on compounds produced per [generate] call. */
    private val cap: Int,
    /** Maximum implication-following depth past the seed flip. */
    private val maxDepth: Int,
) : MoveSource {
    init {
        require(cap >= 0) { "cap >= 0, got $cap" }
        require(maxDepth >= 1) { "maxDepth >= 1, got $maxDepth" }
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Infeasible
    override val pool: Pool = Pool.ScoreOnly

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (cap <= 0 || state.violated.isEmpty()) return
        val graph = state.seeding.implicationGraph
        var budget = cap
        repeat(minOf(cap, state.violated.size)) {
            if (budget <= 0) return
            val fid = state.violated.random(state.rng)
            val seeds = state.problem.factors[fid].boolVars
            if (seeds.isEmpty()) return@repeat
            val seedVar = seeds[state.rng.nextInt(seeds.size)]
            if (state.assumptions.isFrozenBool(seedVar)) return@repeat
            val parts = state.buildImplicationFlip(graph, seedVar, maxDepth)
            if (parts != null) {
                sink.addCompound(parts)
                budget--
            }
        }
    }

    /** Catalog identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("flip-propagate")
    }
}

// Null when the walk forces no extra variable (a lone flip is already in the normal pool) or forces a
// variable both ways.
internal fun LocalSearchState.buildImplicationFlip(graph: Array<IntArray>, seedVar: Int, maxDepth: Int): List<Move>? {
    val seedValue = !assignment.boolValue(seedVar)
    val parts = ArrayList<Move>(4)
    parts += Move.BoolFlip(seedVar)
    // Includes variables left unflipped because they already hold the forced value, so a no-op pin still
    // detects a contradicting force.
    val setPolarity = MutableIntIntMap()
    setPolarity.put(seedVar, asBit(seedValue))
    var frontier = IntArrayList()
    frontier.add(Lit.make(seedVar, seedValue))
    var depth = 0
    while (frontier.size > 0 && depth < maxDepth) {
        val next = IntArrayList()
        for (i in 0 until frontier.size) {
            val forced = graph[frontier[i]]
            for (lit in forced) {
                val v = Lit.variable(lit)
                val value = Lit.isPositive(lit)
                if (setPolarity.containsKey(v)) {
                    if (setPolarity.getOrDefault(v, -1) != asBit(value)) return null
                    continue
                }
                if (assumptions.isFrozenBool(v)) {
                    if (assignment.boolValue(v) != value) return null
                    continue
                }
                setPolarity.put(v, asBit(value))
                if (assignment.boolValue(v) != value) {
                    parts += Move.BoolFlip(v)
                    next.add(Lit.make(v, value))
                }
            }
        }
        frontier = next
        depth++
    }
    return if (parts.size >= 2) parts else null
}

private fun asBit(value: Boolean): Int = if (value) 1 else 0
