package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.assertEquals

/*
 * Move-set equivalence harness (epic #710). Every extraction of a generator into a MoveSource
 * lands behind this gate: for a fixed seed and state, the new source must emit the same multiset
 * of candidate moves as the generator it replaces. Multiset — not list — because the candidate
 * order within a pick is not observable behaviour (the strategy scores the whole pool and the
 * noise draw indexes uniformly), but duplicates matter (they weight the noise draw), so counts
 * are compared, order is not.
 *
 * The harness builds each side from a freshly-constructed LocalSearchState seeded identically,
 * so both runs draw the same RNG sequence — the precondition that makes "same draws ⇒ same
 * multiset" the actual equivalence being asserted rather than an accident of ordering.
 */

/** An order-independent, count-sensitive bag of [Move]s. [Move] subtypes are data classes, so
 *  structural equality and hashing make the backing map exact. */
class MoveMultiset private constructor(private val counts: Map<Move, Int>) {

    val size: Int get() = counts.values.sum()

    val isEmpty: Boolean get() = counts.isEmpty()

    override fun equals(other: Any?): Boolean = other is MoveMultiset && other.counts == counts

    override fun hashCode(): Int = counts.hashCode()

    override fun toString(): String = if (counts.isEmpty()) {
        "{}"
    } else {
        counts.entries
            .sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") { (m, c) -> if (c == 1) "$m" else "$m ×$c" }
    }

    /** The elements present in this multiset but missing (or under-counted) relative to [other],
     *  rendered for an assertion message. */
    fun diff(other: MoveMultiset): String {
        val onlyHere = counts.entries.mapNotNull { (m, c) ->
            val d = c - (other.counts[m] ?: 0)
            if (d > 0) "+$m ×$d" else null
        }
        val onlyThere = other.counts.entries.mapNotNull { (m, c) ->
            val d = c - (counts[m] ?: 0)
            if (d > 0) "-$m ×$d" else null
        }
        return (onlyHere + onlyThere).sorted().joinToString(", ").ifEmpty { "(none)" }
    }

    companion object {
        fun of(moves: List<Move>): MoveMultiset {
            val counts = HashMap<Move, Int>()
            for (m in moves) counts[m] = (counts[m] ?: 0) + 1
            return MoveMultiset(counts)
        }
    }
}

/** Build a fresh [LocalSearchState] over [problem] seeded with [seed], recomputed to a consistent
 *  initial violation state — the standard starting point both sides of an equivalence run share. */
fun freshState(problem: Problem, seed: Long): LocalSearchState =
    LocalSearchState(problem, Random(seed)).also { it.recompute() }

/** Capture the multiset a [fill] closure emits into a fresh sink built from [state]'s assumptions
 *  and invariants (so the sink's frozen/defined filtering matches the production path). */
fun captureFromSink(state: LocalSearchState, fill: (MoveSink) -> Unit): MoveMultiset {
    val sink = MoveSink(state.assumptions)
    sink.setInvariants(state.invariants)
    sink.clear()
    fill(sink)
    return MoveMultiset.of(sink.list)
}

/**
 * Assert that running [source] over a fresh state equals running [reference] (the old generator)
 * over a separate, identically-seeded fresh state. [build] reconstructs the problem for each side
 * so neither run observes the other's RNG advance or sink mutations.
 */
fun assertSourceMatchesGenerator(
    build: () -> Problem,
    seed: Long,
    source: MoveSource,
    prepare: (LocalSearchState) -> Unit = {},
    reference: (LocalSearchState, MoveSink) -> Unit,
) {
    // Reference and source each get their own freshly-seeded state so the RNG sequences align.
    val refState = freshState(build(), seed).also(prepare)
    val expected = captureFromSink(refState) { sink -> reference(refState, sink) }
    val srcState = freshState(build(), seed).also(prepare)
    val actual = captureFromSink(srcState) { sink -> source.generate(srcState, sink) }
    val detail = actual.diff(expected)
    assertEquals(
        expected,
        actual,
        "MoveSource '${source.id}' diverged from its generator (seed=$seed): $detail",
    )
}
