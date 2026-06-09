package com.eignex.klause.compile

import com.eignex.klause.ast.AllDifferent
import com.eignex.klause.ast.AllDifferentExceptExpr
import com.eignex.klause.ast.And
import com.eignex.klause.ast.ArgSortExpr
import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.CostMddExpr
import com.eignex.klause.ast.CostRegularExpr
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntElement
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntScale
import com.eignex.klause.ast.IntSum
import com.eignex.klause.ast.MddExpr
import com.eignex.klause.ast.Or
import com.eignex.klause.ast.TableConstraint
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.ArgSort
import com.eignex.klause.solver.factor.Mdd

/*
 * Decompositions for the "newer" globals. Each [decomposeXxx] returns a [BoolExpr] in
 * terms of existing primitives (And/Or/IntCompare/AllDifferent/Table). Top-level
 * [assertExpr] handles it via the normal pipeline; reified contexts go through
 * [lowerToLit]. Decompositions that need aux integer variables allocate via
 * [newAuxIntVar] and return a [BoolExpr] referring to those new names.
 *
 * Globals that fundamentally require fresh int aux vars per layer (MDD, cost_regular,
 * cost_mdd, path, tree) are routed through dedicated `assertXxx` helpers since the
 * aux-state machinery doesn't compose cleanly with reified lowering; calling them inside
 * a reified context raises an error.
 */

// ----------------------------------------------------------------------------
//  alldifferent_except
// ----------------------------------------------------------------------------

/** Top-level entry: emit the native [AllDifferentExcept]
 *  factor when every operand lifts to a bare [IntRef]. Otherwise fall back to the
 *  BoolExpr decomposition routed through [assertExpr]. */
internal fun Compiler.Build.assertAllDifferentExcept(expr: AllDifferentExceptExpr) {
    val lifted = expr.terms.map { lift(it) }
    if (lifted.all { it is IntRef } && expr.except.isNotEmpty()) {
        val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        if (ids.toSet().size == ids.size) {
            factors += AllDifferentExcept(
                xs = ids,
                except = expr.except.toIntArray(),
            )
            return
        }
    }
    assertExpr(decomposeAllDifferentExcept(expr))
}

internal fun Compiler.Build.decomposeAllDifferentExcept(expr: AllDifferentExceptExpr): BoolExpr {
    val xs = expr.terms
    val except = expr.except
    if (except.isEmpty()) return AllDifferent(xs)
    val clauses = mutableListOf<BoolExpr>()
    val inExceptCache = HashMap<Int, BoolExpr>()
    fun inExcept(idx: Int): BoolExpr = inExceptCache.getOrPut(idx) {
        if (except.size == 1) {
            IntCompare(xs[idx], IntCmpOp.EQ, IntLit(except[0]))
        } else {
            Or(except.map { e -> IntCompare(xs[idx], IntCmpOp.EQ, IntLit(e)) })
        }
    }
    for (i in xs.indices) {
        for (j in i + 1 until xs.size) {
            clauses += Or(
                listOf(
                    inExcept(i),
                    inExcept(j),
                    IntCompare(xs[i], IntCmpOp.NE, xs[j]),
                ),
            )
        }
    }
    return if (clauses.size == 1) clauses[0] else And(clauses)
}

// ----------------------------------------------------------------------------
//  arg_sort
// ----------------------------------------------------------------------------

/** Top-level entry: emit the [ArgSort] factor when both
 *  arrays lift to bare [IntRef]s. Also emit the AST decomposition so the bit-blast path
 *  (which skips propagation-only factors) still enforces the constraint. */
internal fun Compiler.Build.assertArgSort(expr: ArgSortExpr) {
    val liftedValues = expr.values.map { lift(it) }
    val liftedPerm = expr.perm.map { lift(it) }
    if (liftedValues.all { it is IntRef } && liftedPerm.all { it is IntRef }) {
        val valueIds = IntArray(liftedValues.size) { intVarOf((liftedValues[it] as IntRef).name) }
        val permIds = IntArray(liftedPerm.size) { intVarOf((liftedPerm[it] as IntRef).name) }
        if (valueIds.toSet().size == valueIds.size && permIds.toSet().size == permIds.size) {
            factors += ArgSort(
                values = valueIds,
                perm = permIds,
                permOffset = expr.permOffset,
            )
            assertExpr(decomposeArgSort(expr))
            return
        }
    }
    assertExpr(decomposeArgSort(expr))
}

internal fun Compiler.Build.decomposeArgSort(expr: ArgSortExpr): BoolExpr {
    val n = expr.values.size
    val perm = expr.perm
    val values = expr.values
    val off = expr.permOffset

    fun permIndex(i: Int): IntExpr = if (off == 0) {
        perm[i]
    } else {
        IntSum(listOf(perm[i], IntLit(-off)))
    }

    // Each consecutive pair must be ascending in value, with ties broken by index.
    val clauses = mutableListOf<BoolExpr>()
    for (i in 0 until n - 1) {
        val a = IntElement(permIndex(i), values)
        val b = IntElement(permIndex(i + 1), values)
        clauses += Or(
            listOf(
                IntCompare(a, IntCmpOp.LT, b),
                And(
                    listOf(
                        IntCompare(a, IntCmpOp.EQ, b),
                        IntCompare(perm[i], IntCmpOp.LT, perm[i + 1]),
                    ),
                ),
            ),
        )
    }
    // perm is a permutation of [off, off+n−1]: allDifferent + each in range.
    clauses += AllDifferent(perm)
    for (i in 0 until n) {
        clauses += IntCompare(perm[i], IntCmpOp.GE, IntLit(off))
        clauses += IntCompare(perm[i], IntCmpOp.LE, IntLit(off + n - 1))
    }
    return And(clauses)
}

// ----------------------------------------------------------------------------
//  MDD / cost_mdd / cost_regular — table-based state-channel decompositions
// ----------------------------------------------------------------------------

/** Helper: build the [Mdd] factor and emit it when
 *  `seq` is all bare IntRefs. Falls back to the table-based decomposition. */
internal fun Compiler.Build.assertMddNative(
    seqExpr: List<IntExpr>,
    numStatesPerLayer: List<Int>,
    layerStarts: List<Int>,
    transitions: List<Int>,
    initial: Int,
    accepting: List<Int>,
    recordStride: Int,
    costRef: IntRef? = null,
): Boolean {
    val lifted = seqExpr.map { lift(it) }
    if (lifted.all { it is IntRef }) {
        val seqIds = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        val costId = if (costRef != null) intVarOf(costRef.name) else -1
        factors += Mdd(
            seq = seqIds,
            numStatesPerLayer = numStatesPerLayer.toIntArray(),
            layerStarts = layerStarts.toIntArray(),
            transitions = transitions.toIntArray(),
            initial = initial,
            accepting = accepting.toIntArray(),
            recordStride = recordStride,
            cost = costId,
        )
        return true
    }
    return false
}

internal fun Compiler.Build.assertMdd(expr: MddExpr) {
    if (assertMddNative(
            expr.seq,
            expr.numStatesPerLayer,
            expr.layerStarts,
            expr.transitions,
            expr.initial,
            expr.accepting,
            recordStride = 3,
        )
    ) {
        assertMddDecomposed(expr)
        return
    }
    assertMddDecomposed(expr)
}

internal fun Compiler.Build.assertMddDecomposed(expr: MddExpr) {
    val n = expr.seq.size
    // Allocate per-layer state vars: state[0..n]. state[0] = initial; state[n] ∈ accepting.
    val stateRefs = Array(n + 1) { i ->
        val ns = expr.numStatesPerLayer[i]
        require(ns >= 1) { "mdd: numStatesPerLayer[$i] must be ≥ 1" }
        IntRef(newAuxIntVar(IntDomain(0, ns - 1)))
    }

    // state[0] = initial.
    assertExpr(IntCompare(stateRefs[0], IntCmpOp.EQ, IntLit(expr.initial)))
    // state[n] ∈ accepting.
    if (expr.accepting.isEmpty()) {
        assertExpr(IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(1))) // UNSAT
    } else {
        assertExpr(Or(expr.accepting.map { a -> IntCompare(stateRefs[n], IntCmpOp.EQ, IntLit(a)) }))
    }

    // Per-layer transitions as table((state[i], seq[i], state[i+1]), allowed).
    for (i in 0 until n) {
        val start = expr.layerStarts[i]
        val end = expr.layerStarts[i + 1]
        val tuples = mutableListOf<List<Int>>()
        var k = start
        while (k < end) {
            tuples += listOf(expr.transitions[k], expr.transitions[k + 1], expr.transitions[k + 2])
            k += 3
        }
        if (tuples.isEmpty()) {
            assertExpr(IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(1))) // UNSAT
        } else {
            assertExpr(
                TableConstraint(
                    terms = listOf(stateRefs[i], expr.seq[i], stateRefs[i + 1]),
                    tuples = tuples,
                ),
            )
        }
    }
}

internal fun Compiler.Build.assertCostMdd(expr: CostMddExpr) {
    val liftedCost = lift(expr.cost)
    if (liftedCost is IntRef && assertMddNative(
            expr.seq,
            expr.numStatesPerLayer,
            expr.layerStarts,
            expr.transitions,
            expr.initial,
            expr.accepting,
            recordStride = 4,
            costRef = liftedCost,
        )
    ) {
        assertCostMddDecomposed(expr)
        return
    }
    assertCostMddDecomposed(expr)
}

internal fun Compiler.Build.assertCostMddDecomposed(expr: CostMddExpr) {
    val n = expr.seq.size
    val stateRefs = Array(n + 1) { i ->
        val ns = expr.numStatesPerLayer[i]
        IntRef(newAuxIntVar(IntDomain(0, ns - 1)))
    }
    assertExpr(IntCompare(stateRefs[0], IntCmpOp.EQ, IntLit(expr.initial)))
    if (expr.accepting.isEmpty()) {
        assertExpr(IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(1)))
        return
    }
    assertExpr(Or(expr.accepting.map { a -> IntCompare(stateRefs[n], IntCmpOp.EQ, IntLit(a)) }))

    // Allocate per-layer edge-weight var w[i] = weight of the chosen transition.
    val allWeights = mutableListOf<Int>()
    var idx = 0
    while (idx < expr.transitions.size) {
        allWeights += expr.transitions[idx + 3]
        idx += 4
    }
    val wLo = (allWeights.minOrNull() ?: 0)
    val wHi = (allWeights.maxOrNull() ?: 0)
    val weightRefs = Array(n) { IntRef(newAuxIntVar(IntDomain(wLo, wHi))) }

    for (i in 0 until n) {
        val wRef = weightRefs[i]
        val start = expr.layerStarts[i]
        val end = expr.layerStarts[i + 1]
        val tuples = mutableListOf<List<Int>>()
        var k = start
        while (k < end) {
            tuples += listOf(
                expr.transitions[k],
                expr.transitions[k + 1],
                expr.transitions[k + 2],
                expr.transitions[k + 3],
            )
            k += 4
        }
        if (tuples.isEmpty()) {
            assertExpr(IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(1)))
            return
        }
        assertExpr(
            TableConstraint(
                terms = listOf(stateRefs[i], expr.seq[i], stateRefs[i + 1], wRef),
                tuples = tuples,
            ),
        )
    }
    // cost = Σ w[i].
    val sumTerms = mutableListOf<IntExpr>()
    for (i in 0 until n) sumTerms += weightRefs[i]
    sumTerms += IntScale(-1, expr.cost)
    assertExpr(IntCompare(IntSum(sumTerms), IntCmpOp.EQ, IntLit(0)))
}

internal fun Compiler.Build.assertCostRegular(expr: CostRegularExpr) {
    val n = expr.seq.size
    val numStates = expr.numStates
    val numSymbols = expr.numSymbols
    val off = expr.symbolOffset

    // Try the native MDD path first — expand uniform DFA transitions into per-layer tables.
    val liftedSeq = expr.seq.map { lift(it) }
    val liftedCost = lift(expr.cost)
    if (liftedSeq.all { it is IntRef } && liftedCost is IntRef) {
        // Build a single layer's transition rows then replicate per layer.
        val baseRows = mutableListOf<Int>()
        for (q in 0 until numStates) {
            for (s in 0 until numSymbols) {
                val dst = expr.transitions[q * numSymbols + s]
                if (dst == 0) continue
                baseRows += q
                baseRows += s + off
                baseRows += dst - 1
                baseRows += expr.weights[q * numSymbols + s]
            }
        }
        if (baseRows.isNotEmpty()) {
            val flatTrans = ArrayList<Int>()
            val starts = IntArray(n + 1)
            for (i in 0 until n) {
                starts[i] = flatTrans.size
                flatTrans.addAll(baseRows)
            }
            starts[n] = flatTrans.size
            assertMddNative(
                expr.seq,
                List(n + 1) { numStates },
                starts.toList(),
                flatTrans,
                expr.initial,
                expr.accepting,
                recordStride = 4,
                costRef = liftedCost,
            )
        }
    }

    // Decomposition path: always emitted so the bit-blast pipeline (which skips the
    // propagation-only Mdd factor) still sees the constraint as primitive Table + Linear.
    val stateRefs = Array(n + 1) { IntRef(newAuxIntVar(IntDomain(0, numStates - 1))) }
    assertExpr(IntCompare(stateRefs[0], IntCmpOp.EQ, IntLit(expr.initial)))
    if (expr.accepting.isEmpty()) {
        assertExpr(IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(1)))
        return
    }
    assertExpr(Or(expr.accepting.map { a -> IntCompare(stateRefs[n], IntCmpOp.EQ, IntLit(a)) }))

    // Build the transition table as tuples (src, sym, dst, weight) — same shape across layers.
    val tuples = mutableListOf<List<Int>>()
    for (q in 0 until numStates) {
        for (s in 0 until numSymbols) {
            val dst = expr.transitions[q * numSymbols + s]
            if (dst == 0) continue // 0 means no transition (matches FlatZinc's `regular` convention)
            tuples += listOf(q, s + off, dst - 1, expr.weights[q * numSymbols + s])
        }
    }
    val wLo = tuples.minOfOrNull { it[3] } ?: 0
    val wHi = tuples.maxOfOrNull { it[3] } ?: 0
    val weightRefs = Array(n) { IntRef(newAuxIntVar(IntDomain(wLo, wHi))) }
    for (i in 0 until n) {
        val wRef = weightRefs[i]
        if (tuples.isEmpty()) {
            assertExpr(IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(1)))
            return
        }
        assertExpr(
            TableConstraint(
                terms = listOf(stateRefs[i], expr.seq[i], stateRefs[i + 1], wRef),
                tuples = tuples,
            ),
        )
    }
    val sumTerms = mutableListOf<IntExpr>()
    for (i in 0 until n) sumTerms += weightRefs[i]
    sumTerms += IntScale(-1, expr.cost)
    assertExpr(IntCompare(IntSum(sumTerms), IntCmpOp.EQ, IntLit(0)))
}
