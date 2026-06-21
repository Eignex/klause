package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.factor.table.internals.TableLsState
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap

/** LS invariant for [Table]. Constructed by [Table.asInvariant]. */
internal class TableInvariant(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val xs: IntArray,
    private val tuples: IntArray,
    private val arity: Int,
    private val numTuples: Int,
    private val singleColumnByVar: IntIntMap,
    private val multiColumnsByVar: Map<Int, IntArray>,
) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as TableLsState).minDist > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        (state.refPayload[factorId] as TableLsState).minDist

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val dist = IntArray(numTuples)
        var minD = arity
        for (row in 0 until numTuples) {
            val base = row * arity
            var d = 0
            for (col in 0 until arity) {
                if (state.assignment.intValue(xs[col]) != tuples[base + col]) d++
            }
            dist[row] = d
            if (d < minD) minD = d
        }
        state.refPayload[factorId] = TableLsState(dist, minD)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        data class Scored(val row: Int, val distance: Int)
        val scored = ArrayList<Scored>(numTuples)
        for (row in 0 until numTuples) {
            var dist = 0
            for (col in 0 until arity) {
                if (state.assignment.intValue(xs[col]) != tuples[row * arity + col]) dist++
            }
            scored.add(Scored(row, dist))
        }
        scored.sortBy { it.distance }
        val topK = minOf(2, scored.size)
        for (k in 0 until topK) {
            val row = scored[k].row
            for (col in 0 until arity) {
                val target = tuples[row * arity + col]
                val cur = state.assignment.intValue(xs[col])
                if (target != cur && target in state.problem.intDomains[xs[col]]) {
                    sink.addChannelingIntSet(
                        state,
                        xs[col],
                        target,
                    )
                }
            }
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (numTuples < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < TABLE_STRUCTURED_JUMP_CAP &&
            attempts < TABLE_STRUCTURED_JUMP_CAP * TABLE_JUMP_ATTEMPT_STRIDE
        ) {
            attempts++
            val row = state.rng.nextInt(numTuples)
            val parts = tableBuildTupleMove(state, xs, tuples, arity, row) ?: continue
            if (parts.isEmpty()) continue
            sink.addCompound(parts)
            emitted++
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (row in 0 until numTuples) {
            val base = row * arity
            var usable = true
            for (col in 0 until arity) {
                val v = xs[col]
                val target = tuples[base + col]
                if (target !in state.problem.intDomains[v]) {
                    usable = false
                    break
                }
                if (state.assumptions.isFrozenInt(
                        v,
                    ) && state.assignment.intValue(v) != target
                ) {
                    usable = false
                    break
                }
                var conflict = false
                for (prev in 0 until col) {
                    if (xs[prev] == v && tuples[base + prev] != target) {
                        conflict = true
                        break
                    }
                }
                if (conflict) {
                    usable = false
                    break
                }
            }
            if (!usable) continue
            for (col in 0 until arity) {
                val v = xs[col]
                if (!state.assumptions.isFrozenInt(v)) state.assignment.setInt(v, tuples[base + col])
            }
            return true
        }
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as TableLsState
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        return tableRescanForChange(
            s, tuples, arity, numTuples, singleColumnByVar, multiColumnsByVar, intVar, old, newValue, commit = false,
        ) - s.minDist
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as TableLsState
        val newVal = state.assignment.intValue(intVar)
        if (newVal == oldValue) return 0
        val before = s.minDist
        val minD = tableRescanForChange(
            s, tuples, arity, numTuples, singleColumnByVar, multiColumnsByVar, intVar, oldValue, newVal, commit = true,
        )
        s.minDist = minD
        return minD - before
    }
}

private const val TABLE_STRUCTURED_JUMP_CAP: Int = 4
private const val TABLE_JUMP_ATTEMPT_STRIDE: Int = 4

/** Recompute the minimum Hamming distance after changing [intVar] from [oldV] to [newV].
 *  If [commit] is true the per-row distances in [s] are updated in place. */
internal fun tableRescanForChange(
    s: TableLsState,
    tuples: IntArray,
    arity: Int,
    numTuples: Int,
    singleColumnByVar: IntIntMap,
    multiColumnsByVar: Map<Int, IntArray>,
    intVar: Int,
    oldV: Int,
    newV: Int,
    commit: Boolean,
): Int {
    var minD = arity
    val col = singleColumnByVar[intVar]
    if (col >= 0) {
        for (row in 0 until numTuples) {
            val t = tuples[row * arity + col]
            val d = s.dist[row] + (if (newV != t) 1 else 0) - (if (oldV != t) 1 else 0)
            if (commit) s.dist[row] = d
            if (d < minD) minD = d
        }
    } else {
        val cols = multiColumnsByVar.getValue(intVar)
        for (row in 0 until numTuples) {
            val base = row * arity
            var d = s.dist[row]
            for (c in cols) {
                val t = tuples[base + c]
                d += (if (newV != t) 1 else 0) - (if (oldV != t) 1 else 0)
            }
            if (commit) s.dist[row] = d
            if (d < minD) minD = d
        }
    }
    return minD
}

/** Build the set-moves that land [xs] exactly on tuple [row], or null if unreachable. */
internal fun tableBuildTupleMove(
    state: LocalSearchState,
    xs: IntArray,
    tuples: IntArray,
    arity: Int,
    row: Int,
): List<Move>? {
    val base = row * arity
    for (col in 0 until arity) {
        val v = xs[col]
        val target = tuples[base + col]
        if (target !in state.problem.intDomains[v]) return null
        for (prev in 0 until col) {
            if (xs[prev] == v && tuples[base + prev] != target) return null
        }
    }
    val parts = ArrayList<Move>(arity)
    for (col in 0 until arity) {
        val v = xs[col]
        var dup = false
        for (prev in 0 until col) {
            if (xs[prev] == v) {
                dup = true
                break
            }
        }
        if (dup) continue
        val target = tuples[base + col]
        if (state.assignment.intValue(v) != target) parts.add(Move.IntSet(v, target))
    }
    return parts
}

/** Build the var→column lookup structures. */
internal fun tableColumnMaps(xs: IntArray, arity: Int): Pair<IntIntMap, Map<Int, IntArray>> {
    val occ = HashMap<Int, IntArrayList>()
    for (c in 0 until arity) occ.getOrPut(xs[c]) { IntArrayList() }.add(c)
    val singleKeys = IntArrayList()
    val singleVals = IntArrayList()
    val multi = HashMap<Int, IntArray>()
    for ((v, cols) in occ) {
        if (cols.size == 1) {
            singleKeys.add(v)
            singleVals.add(cols[0])
        } else {
            multi[v] = cols.toIntArray()
        }
    }
    return Pair(
        IntIntMap.build(singleKeys.toIntArray(), singleVals.toIntArray(), absent = -1),
        multi,
    )
}
