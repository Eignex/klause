package com.eignex.klause.compile

import com.eignex.klause.ast.AllDifferent
import com.eignex.klause.ast.AllDifferentExceptExpr
import com.eignex.klause.ast.And
import com.eignex.klause.ast.ArgSortExpr
import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.CostMddExpr
import com.eignex.klause.ast.CostRegularExpr
import com.eignex.klause.ast.GeostExpr
import com.eignex.klause.ast.Iff
import com.eignex.klause.ast.Implies
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntElement
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntScale
import com.eignex.klause.ast.IntSum
import com.eignex.klause.ast.MddExpr
import com.eignex.klause.ast.NetworkFlowCostExpr
import com.eignex.klause.ast.NetworkFlowExpr
import com.eignex.klause.ast.Not
import com.eignex.klause.ast.Or
import com.eignex.klause.ast.PathExpr
import com.eignex.klause.ast.TableConstraint
import com.eignex.klause.ast.TreeExpr
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.ArgSort
import com.eignex.klause.solver.factor.Geost
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.MinCostFlow
import com.eignex.klause.solver.factor.Path
import com.eignex.klause.solver.factor.Tree

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
//  network_flow / network_flow_cost
// ----------------------------------------------------------------------------

/** Top-level entry: emit the [MinCostFlow] factor when
 *  every flow term lifts to a bare [IntRef]. Falls back to the linear-per-node decomposition. */
internal fun Compiler.Build.assertNetworkFlow(expr: NetworkFlowExpr) {
    val lifted = expr.flow.map { lift(it) }
    if (lifted.all { it is IntRef }) {
        val flowIds = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        factors += MinCostFlow(
            numNodes = expr.numNodes,
            arcFrom = expr.arcFrom.toIntArray(),
            arcTo = expr.arcTo.toIntArray(),
            balance = expr.balance.toIntArray(),
            flow = flowIds,
            weight = null,
            cost = -1,
            nodeOffset = expr.nodeOffset,
        )
        return
    }
    assertExpr(decomposeNetworkFlow(expr))
}

internal fun Compiler.Build.assertNetworkFlowCost(expr: NetworkFlowCostExpr) {
    val lifted = expr.flow.map { lift(it) }
    val liftedCost = lift(expr.cost)
    if (lifted.all { it is IntRef } && liftedCost is IntRef) {
        val flowIds = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        val costId = intVarOf(liftedCost.name)
        factors += MinCostFlow(
            numNodes = expr.numNodes,
            arcFrom = expr.arcFrom.toIntArray(),
            arcTo = expr.arcTo.toIntArray(),
            balance = expr.balance.toIntArray(),
            flow = flowIds,
            weight = expr.weight.toIntArray(),
            cost = costId,
            nodeOffset = expr.nodeOffset,
        )
        // Also emit the cost equality as a Linear factor for tight bound propagation.
        // (The MinCostFlow factor only does interval-arithmetic bounds.)
        val sumTerms = mutableListOf<IntExpr>()
        for (a in expr.flow.indices) {
            sumTerms += if (expr.weight[a] == 1) expr.flow[a] else IntScale(expr.weight[a], expr.flow[a])
        }
        sumTerms += IntScale(-1, expr.cost)
        assertExpr(IntCompare(IntSum(sumTerms), IntCmpOp.EQ, IntLit(0)))
        return
    }
    assertExpr(decomposeNetworkFlowCost(expr))
}

internal fun Compiler.Build.decomposeNetworkFlow(expr: NetworkFlowExpr): BoolExpr {
    val nNodes = expr.numNodes
    val off = expr.nodeOffset
    val inArcs = Array(nNodes) { mutableListOf<Int>() }
    val outArcs = Array(nNodes) { mutableListOf<Int>() }
    for (a in expr.arcFrom.indices) {
        outArcs[expr.arcFrom[a] - off].add(a)
        inArcs[expr.arcTo[a] - off].add(a)
    }
    val clauses = mutableListOf<BoolExpr>()
    for (n in 0 until nNodes) {
        // inflow − outflow = balance[n].
        val terms = mutableListOf<IntExpr>()
        for (a in inArcs[n]) terms += expr.flow[a]
        for (a in outArcs[n]) terms += IntScale(-1, expr.flow[a])
        val lhs = if (terms.isEmpty()) {
            IntLit(0)
        } else if (terms.size == 1) {
            terms[0]
        } else {
            IntSum(terms)
        }
        clauses += IntCompare(lhs, IntCmpOp.EQ, IntLit(expr.balance[n]))
    }
    return if (clauses.size == 1) clauses[0] else And(clauses)
}

internal fun Compiler.Build.decomposeNetworkFlowCost(expr: NetworkFlowCostExpr): BoolExpr {
    val balanceClauses = decomposeNetworkFlow(
        NetworkFlowExpr(
            numNodes = expr.numNodes,
            arcFrom = expr.arcFrom,
            arcTo = expr.arcTo,
            balance = expr.balance,
            flow = expr.flow,
            nodeOffset = expr.nodeOffset,
        ),
    )
    // cost = Σ weight[a] · flow[a].
    val terms = mutableListOf<IntExpr>()
    for (a in expr.flow.indices) {
        terms += if (expr.weight[a] == 1) expr.flow[a] else IntScale(expr.weight[a], expr.flow[a])
    }
    terms += IntScale(-1, expr.cost)
    val lhs = if (terms.isEmpty()) {
        IntLit(0)
    } else if (terms.size == 1) {
        terms[0]
    } else {
        IntSum(terms)
    }
    val costEq = IntCompare(lhs, IntCmpOp.EQ, IntLit(0))
    return And(listOf(balanceClauses, costEq))
}

// ----------------------------------------------------------------------------
//  geost
// ----------------------------------------------------------------------------

/** Top-level entry: emit [Geost] when every origin is
 *  a bare [IntRef]. */
internal fun Compiler.Build.assertGeost(expr: GeostExpr) {
    val lifted = expr.origin.map { lift(it) }
    if (lifted.all { it is IntRef }) {
        val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        factors += Geost(
            numDims = expr.numDims,
            numObjects = expr.numObjects,
            origin = ids,
            length = expr.length.toIntArray(),
        )
        // Also emit the pairwise OR-decomposition so multi-free-dim cases still propagate.
        assertExpr(decomposeGeost(expr))
        return
    }
    assertExpr(decomposeGeost(expr))
}

internal fun Compiler.Build.decomposeGeost(expr: GeostExpr): BoolExpr {
    val d = expr.numDims
    val n = expr.numObjects
    // For each pair (i, j), at least one separating axis: in some dim k,
    //   origin[i,k] + size[i,k] ≤ origin[j,k]  ∨  origin[j,k] + size[j,k] ≤ origin[i,k].
    val pairs = mutableListOf<BoolExpr>()
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val perDim = mutableListOf<BoolExpr>()
            for (k in 0 until d) {
                val oi = expr.origin[i * d + k]
                val oj = expr.origin[j * d + k]
                val si = expr.length[i * d + k]
                val sj = expr.length[j * d + k]
                // origin[i] + si ≤ origin[j]  ⟺  origin[i] − origin[j] ≤ −si.
                perDim += IntCompare(
                    IntSum(listOf(oi, IntScale(-1, oj))),
                    IntCmpOp.LE,
                    IntLit(-si),
                )
                perDim += IntCompare(
                    IntSum(listOf(oj, IntScale(-1, oi))),
                    IntCmpOp.LE,
                    IntLit(-sj),
                )
            }
            pairs += Or(perDim)
        }
    }
    return if (pairs.isEmpty()) {
        IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(0))
    } else if (pairs.size == 1) {
        pairs[0]
    } else {
        And(pairs)
    }
}

// ----------------------------------------------------------------------------
//  path / tree — top-level only (aux flow vars). Reified callers raise.
// ----------------------------------------------------------------------------

private fun Compiler.Build.allocAuxBoundedInt(lo: Int, hi: Int): IntRef {
    val name = newAuxIntVar(IntDomain(lo, hi))
    return IntRef(name)
}

internal fun Compiler.Build.assertPath(expr: PathExpr) {
    val n = expr.numNodes
    val m = expr.from.size
    val off = expr.nodeOffset
    val nodeP = expr.nodePresent
    val edgeP = expr.edgePresent

    // Dedicated Path factor for reachability propagation (added on top of the
    // degree/flow decomposition below).
    val srcId = run {
        val l = lift(expr.source)
        require(l is IntRef) { "path: source must lift to bare var" }
        intVarOf(l.name)
    }
    val sinkId = run {
        val l = lift(expr.sink)
        require(l is IntRef) { "path: sink must lift to bare var" }
        intVarOf(l.name)
    }
    val nodeBoolIds = IntArray(n) { i ->
        val lit = lowerToLit(nodeP[i])
        require(Lit.isPositive(lit)) { "path: nodePresent[$i] must be a bare BoolRef" }
        Lit.variable(lit)
    }
    val edgeBoolIds = IntArray(m) { e ->
        val lit = lowerToLit(edgeP[e])
        require(Lit.isPositive(lit)) { "path: edgePresent[$e] must be a bare BoolRef" }
        Lit.variable(lit)
    }
    factors += Path(
        numNodes = n,
        from = expr.from.toIntArray(),
        to = expr.to.toIntArray(),
        source = srcId,
        sink = sinkId,
        nodePresent = nodeBoolIds,
        edgePresent = edgeBoolIds,
        nodeOffset = off,
    )

    val inArcs = Array(n) { mutableListOf<Int>() }
    val outArcs = Array(n) { mutableListOf<Int>() }
    for (e in 0 until m) {
        outArcs[expr.from[e] - off].add(e)
        inArcs[expr.to[e] - off].add(e)
    }

    // For each node, in-degree and out-degree (as Σ edge_present) defined as ints.
    val inDeg = IntArray(n) { -1 }
    val outDeg = IntArray(n) { -1 }
    for (v in 0 until n) {
        inDeg[v] = intVarOf(
            materializeIntFromSumOfBools("__path_in_${v}_${factors.size}", inArcs[v].map { edgeP[it] }).name,
        )
        outDeg[v] = intVarOf(
            materializeIntFromSumOfBools("__path_out_${v}_${factors.size}", outArcs[v].map { edgeP[it] }).name,
        )
    }

    // Source ↔ in_deg = 0 ∧ out_deg = 1 ∧ present;
    // Sink   ↔ in_deg = 1 ∧ out_deg = 0 ∧ present;
    // Other present node has in_deg = 1, out_deg = 1.
    // Absent node has in_deg = 0, out_deg = 0.
    for (v in 0 until n) {
        val isSource = IntCompare(expr.source, IntCmpOp.EQ, IntLit(v + off))
        val isSink = IntCompare(expr.sink, IntCmpOp.EQ, IntLit(v + off))
        val present = nodeP[v]
        val inV = IntRef(intVarNameById(inDeg[v]))
        val outV = IntRef(intVarNameById(outDeg[v]))

        // present ↔ (in_deg + out_deg ≥ 1) OR is_source OR is_sink. Simpler:
        // ¬present → in_deg = 0 ∧ out_deg = 0.
        assertExpr(
            Implies(
                Not(present),
                And(
                    listOf(
                        IntCompare(inV, IntCmpOp.EQ, IntLit(0)),
                        IntCompare(outV, IntCmpOp.EQ, IntLit(0)),
                    ),
                ),
            ),
        )
        // (present ∧ is_source) → in_deg = 0 ∧ out_deg = 1.
        assertExpr(
            Implies(
                And(listOf(present, isSource)),
                And(
                    listOf(
                        IntCompare(inV, IntCmpOp.EQ, IntLit(0)),
                        IntCompare(outV, IntCmpOp.EQ, IntLit(1)),
                    ),
                ),
            ),
        )
        // (present ∧ is_sink) → in_deg = 1 ∧ out_deg = 0.
        assertExpr(
            Implies(
                And(listOf(present, isSink)),
                And(
                    listOf(
                        IntCompare(inV, IntCmpOp.EQ, IntLit(1)),
                        IntCompare(outV, IntCmpOp.EQ, IntLit(0)),
                    ),
                ),
            ),
        )
        // (present ∧ ¬is_source ∧ ¬is_sink) → in_deg = 1 ∧ out_deg = 1.
        assertExpr(
            Implies(
                And(listOf(present, Not(isSource), Not(isSink))),
                And(
                    listOf(
                        IntCompare(inV, IntCmpOp.EQ, IntLit(1)),
                        IntCompare(outV, IntCmpOp.EQ, IntLit(1)),
                    ),
                ),
            ),
        )
        // Source and sink must be present.
        assertExpr(Implies(isSource, present))
        assertExpr(Implies(isSink, present))
    }
    // Edge present → both endpoints present.
    for (e in 0 until m) {
        assertExpr(Implies(edgeP[e], nodeP[expr.from[e] - off]))
        assertExpr(Implies(edgeP[e], nodeP[expr.to[e] - off]))
    }
    // Source and sink are valid node indices.
    assertExpr(IntCompare(expr.source, IntCmpOp.GE, IntLit(off)))
    assertExpr(IntCompare(expr.source, IntCmpOp.LE, IntLit(off + n - 1)))
    assertExpr(IntCompare(expr.sink, IntCmpOp.GE, IntLit(off)))
    assertExpr(IntCompare(expr.sink, IntCmpOp.LE, IntLit(off + n - 1)))

    // Subtour elimination via single-commodity flow rooted at source. Allocate flow
    // variables on each arc, capped by edge_present; source has supply = #present_nodes - 1,
    // sink has demand of the same magnitude (we let it be n − 1 worst-case; slack absorbs).
    val flowVars = IntArray(m) { allocAuxBoundedInt(0, n).let { aux -> intVarOf(aux.name) } }
    // flow ≤ n · edge_present[e]   →  flow − n · ep ≤ 0.
    for (e in 0 until m) {
        assertExpr(Implies(Not(edgeP[e]), IntCompare(IntRef(intVarNameById(flowVars[e])), IntCmpOp.EQ, IntLit(0))))
    }
    // Per-node flow conservation: nodes other than source/sink balance (out − in = 0);
    // source has out − in = (Σ present) − 1; sink has out − in = − ((Σ present) − 1).
    // We collapse to: at each non-source non-sink present node, the inflow ≥ 1 if present
    // (since each present non-source node must be reached); equivalently we require the
    // flow on its single incoming edge to be ≥ 1 when present.
    // Simpler robust formulation: at every present non-source node, inflow ≥ 1.
    for (v in 0 until n) {
        val inflow = IntSum(inArcs[v].map { e -> IntRef(intVarNameById(flowVars[e])) } + IntLit(0))
        val isSource = IntCompare(expr.source, IntCmpOp.EQ, IntLit(v + off))
        assertExpr(Implies(And(listOf(nodeP[v], Not(isSource))), IntCompare(inflow, IntCmpOp.GE, IntLit(1))))
    }
}

internal fun Compiler.Build.assertTree(expr: TreeExpr) {
    val n = expr.numNodes
    val m = expr.from.size
    val off = expr.nodeOffset
    val nodeP = expr.nodePresent
    val edgeP = expr.edgePresent

    val rootId = run {
        val l = lift(expr.root)
        require(l is IntRef) { "tree: root must lift to bare var" }
        intVarOf(l.name)
    }
    val nodeBoolIds = IntArray(n) { i ->
        val lit = lowerToLit(nodeP[i])
        require(Lit.isPositive(lit)) { "tree: nodePresent[$i] must be a bare BoolRef" }
        Lit.variable(lit)
    }
    val edgeBoolIds = IntArray(m) { e ->
        val lit = lowerToLit(edgeP[e])
        require(Lit.isPositive(lit)) { "tree: edgePresent[$e] must be a bare BoolRef" }
        Lit.variable(lit)
    }
    factors += Tree(
        numNodes = n,
        from = expr.from.toIntArray(),
        to = expr.to.toIntArray(),
        root = rootId,
        nodePresent = nodeBoolIds,
        edgePresent = edgeBoolIds,
        nodeOffset = off,
    )

    val inArcs = Array(n) { mutableListOf<Int>() }
    val outArcs = Array(n) { mutableListOf<Int>() }
    for (e in 0 until m) {
        outArcs[expr.from[e] - off].add(e)
        inArcs[expr.to[e] - off].add(e)
    }
    val inDeg =
        IntArray(
            n,
        ) { v ->
            intVarOf(
                materializeIntFromSumOfBools("__tree_in_${v}_${factors.size}", inArcs[v].map { edgeP[it] }).name,
            )
        }

    for (v in 0 until n) {
        val isRoot = IntCompare(expr.root, IntCmpOp.EQ, IntLit(v + off))
        val present = nodeP[v]
        val inV = IntRef(intVarNameById(inDeg[v]))

        // ¬present → in_deg = 0.
        assertExpr(Implies(Not(present), IntCompare(inV, IntCmpOp.EQ, IntLit(0))))
        // (present ∧ is_root) → in_deg = 0.
        assertExpr(Implies(And(listOf(present, isRoot)), IntCompare(inV, IntCmpOp.EQ, IntLit(0))))
        // (present ∧ ¬is_root) → in_deg = 1.
        assertExpr(Implies(And(listOf(present, Not(isRoot))), IntCompare(inV, IntCmpOp.EQ, IntLit(1))))
        // Root must be present.
        assertExpr(Implies(isRoot, present))
    }
    for (e in 0 until m) {
        assertExpr(Implies(edgeP[e], nodeP[expr.from[e] - off]))
        assertExpr(Implies(edgeP[e], nodeP[expr.to[e] - off]))
    }
    assertExpr(IntCompare(expr.root, IntCmpOp.GE, IntLit(off)))
    assertExpr(IntCompare(expr.root, IntCmpOp.LE, IntLit(off + n - 1)))

    // Acyclicity via topological-rank vars: rank[root] = 0; for every selected edge (u→v),
    // rank[v] > rank[u].
    val rank = IntArray(n) { allocAuxBoundedInt(0, n).let { aux -> intVarOf(aux.name) } }
    for (v in 0 until n) {
        val isRoot = IntCompare(expr.root, IntCmpOp.EQ, IntLit(v + off))
        assertExpr(Implies(isRoot, IntCompare(IntRef(intVarNameById(rank[v])), IntCmpOp.EQ, IntLit(0))))
    }
    for (e in 0 until m) {
        val u = expr.from[e] - off
        val v = expr.to[e] - off
        // edge_present[e] → rank[v] ≥ rank[u] + 1.
        val ru = IntRef(intVarNameById(rank[u]))
        val rv = IntRef(intVarNameById(rank[v]))
        assertExpr(
            Implies(
                edgeP[e],
                IntCompare(IntSum(listOf(rv, IntScale(-1, ru))), IntCmpOp.GE, IntLit(1)),
            ),
        )
    }
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

// ----------------------------------------------------------------------------
//  helpers
// ----------------------------------------------------------------------------

/** Look up the var-name for an int var id (O(1) via the reverse index, #97). */
internal fun Compiler.Build.intVarNameById(id: Int): String = idToIntName.getValue(id)

/**
 * Materialise `name = Σ bools[i] (cast to int)` as a fresh int var (range [0, k]) plus a
 * PseudoBoolean equality factor. Returns the IntRef.
 */
internal fun Compiler.Build.materializeIntFromSumOfBools(name: String, bools: List<BoolExpr>): IntRef {
    val k = bools.size
    val varName = "__sumb_${name}_${auxIntCounter++}"
    val id = newIntVar(IntDomain(0, k))
    bindIntName(varName, id)
    if (bools.isEmpty()) {
        // Force to 0.
        assertExpr(IntCompare(IntRef(varName), IntCmpOp.EQ, IntLit(0)))
    } else {
        // No direct BoolExpr → IntExpr cast: channel each b_i through an aux int in {0,1}
        // (b_i ↔ aux_i = 1), then post `var − Σ aux_i = 0` as a Linear factor.
        val terms = mutableListOf<IntExpr>(IntRef(varName))
        for (b in bools) {
            val aname = "__b2i_${auxIntCounter++}"
            val aid = newIntVar(IntDomain(0, 1))
            bindIntName(aname, aid)
            // b ↔ aux = 1.
            assertExpr(Iff(b, IntCompare(IntRef(aname), IntCmpOp.EQ, IntLit(1))))
            terms += IntScale(-1, IntRef(aname))
        }
        assertExpr(IntCompare(IntSum(terms), IntCmpOp.EQ, IntLit(0)))
    }
    return IntRef(varName)
}
