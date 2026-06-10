package com.eignex.klause.solver.lp

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList

/**
 * An LP relaxation of a [Problem] at one search node, plus the metadata mapping each LP column
 * back to the CP variable it stands for. The mapping is what lets reduced-cost fixing turn
 * an LP column reduction into a domain reduction on the right `(kind, varId)`.
 *
 * The LP objective is over the column costs only; the true objective is
 * `lpObjective + objectiveConstant`. Branch-and-bound must add [objectiveConstant] before
 * comparing the LP bound to the incumbent.
 */
internal class LpRelaxation(
    val model: LpModel,
    /** Structural LP column → origin CP variable id. */
    val colVarId: IntArray,
    /** Structural LP column → true if it is a Boolean variable, false if an integer variable. */
    val colIsBool: BooleanArray,
    /** Constant term of the objective, omitted from the LP and re-added to its bound. */
    val objectiveConstant: Long,
    /** Integer variable id → its LP column, or -1 if none. For separators to write cuts. */
    val intColOf: IntArray,
    /** Boolean variable id → its LP column, or -1 if none. */
    val boolColOf: IntArray,
    /** Arc-indicator models of any Circuit factors, for the subtour-elimination separator. */
    val circuitArcs: List<CircuitArcModel> = emptyList(),
)

/**
 * Walks [Problem.factors] and emits an [LpModel] relaxation for the LP-emittable factor types,
 * pulling variable bounds live from the current search node.
 *
 * ## What is encoded
 *  - [Linear]: one row, `LE`/`GE`/`EQ` mapped directly; `NE` is not LP-relaxable and is skipped.
 *  - [Cardinality], [Clause], [PseudoBoolean]: linear rows over the Boolean fan-in. A positive
 *    literal contributes `x_b`, a negative literal `1 − x_b`; the constant folds into the row's
 *    right-hand side.
 *  - [ReifiedLinear]: indicator rows via tight big-M (see `reifiedRows`).
 *  - [ArrayMinMax]: the extremum's envelope — `result ≥ xs[i]` for `max`, `result ≤ xs[i]` for
 *    `min`, one row per operand. Sound; the tight face (result equals some operand) is not a
 *    single LP cut, so only the envelope side is emitted.
 *  - The [LinearObjective] (always minimization): a cost on each variable's column. Every variable
 *    with a nonzero objective coefficient gets a column even if no constraint mentions it, so the
 *    LP objective is the complete relaxed objective and its optimum is a valid bound.
 *
 * ## What is skipped
 *  Hard globals (AllDifferent, Cumulative, Element, Circuit, …) are not encoded here; they are
 *  handled by cut generation or Lagrangian relaxation. Unrecognized factors are
 *  silently skipped — a missing constraint only loosens the relaxation, it never makes the bound
 *  unsound.
 *
 * ## Live bounds
 *  Integer columns take `[min, max]` from [PropagationSession.intDomain]; a Boolean column takes
 *  `[1, 1]` / `[0, 0]` when its variable is already pinned this node, else `[0, 1]`. Pinning a
 *  Boolean column collapses every big-M indicator that mentions it to the exact constraint.
 */
internal class CpToLpRelaxation(
    private val problem: Problem,
    private val objective: LinearObjective?,
    /** When true, materialize columns for variables of cut-eligible globals (AllDifferent) so a
     *  [CutSeparator] can write cuts over them, even when no other factor references the variable. */
    private val generateCuts: Boolean = false,
    /** When true, build the arc-indicator relaxation of each Circuit (degree + channelling rows) so
     *  [CircuitSeparator] can separate subtour-elimination cuts. Adds O(n²) columns, so it is gated. */
    private val circuitArcs: Boolean = false,
    /** When true, linearize each constant-array Element with a one-hot selector model (its exact
     *  convex hull). Adds O(len) columns, so it is gated; variable arrays are skipped. */
    private val elementHull: Boolean = false,
    /** When true, linearize each Table with one selector column per allowed tuple — its exact convex
     *  hull. Adds O(numTuples) columns, so it is gated. */
    private val tableHull: Boolean = false,
) {
    private companion object {
        /** Above this node count the O(n²)-column circuit arc model is skipped. */
        const val MAX_NODES: Int = 24

        /** Above this array length the O(len)-column Element selector model is skipped. */
        const val MAX_ELEM: Int = 256

        /** Above this tuple count the O(numTuples)-column Table hull is skipped. */
        const val MAX_TUPLES: Int = 1024
    }

    /** Build the relaxation, optionally appending separator-produced [extraCuts] as extra rows. */
    fun build(session: PropagationSession, extraCuts: List<Cut> = emptyList()): LpRelaxation =
        Assembler(session).assemble(extraCuts)

    private fun intCost(i: Int): Long = objective?.intCoefficients?.getOrElse(i) { 0L } ?: 0L

    private fun boolCost(b: Int): Long = objective?.boolWeights?.getOrElse(b) { 0L } ?: 0L

    /** Per-build mutable state: the builder, the column maps, and the row emitters. */
    private inner class Assembler(private val session: PropagationSession) {
        private val builder = LpBuilder()
        private val intCol = IntArray(problem.numIntVars) { -1 }
        private val boolCol = IntArray(problem.numBoolVars) { -1 }
        private val colVarId = IntArrayList()
        private val colIsBool = IntArrayList() // 0 = int, 1 = bool; densified at the end
        private val circuitModels = ArrayList<CircuitArcModel>()

        /** Auxiliary LP column with no backing CP variable (tag/colVarId = -1) — e.g. a circuit arc. */
        private fun auxColumn(lo: Long, hi: Long): Int {
            val c = builder.addVar(lo, hi, cost = 0L, tag = -1)
            colVarId.add(-1)
            colIsBool.add(0)
            return c
        }

        /** Column for integer variable `i`, created on first use with its live domain bounds. */
        private fun intColumn(i: Int): Int {
            var c = intCol[i]
            if (c == -1) {
                val dom = session.intDomain(i)
                c = builder.addVar(dom.min.toLong(), dom.max.toLong(), intCost(i), tag = i)
                intCol[i] = c
                colVarId.add(i)
                colIsBool.add(0)
            }
            return c
        }

        /** Column for Boolean variable `b`; bounds collapse to a point if it is pinned this node. */
        private fun boolColumn(b: Int): Int {
            var c = boolCol[b]
            if (c == -1) {
                val pinned = session.boolValue(b)
                val lo = if (pinned == true) 1L else 0L
                val hi = if (pinned == false) 0L else 1L
                c = builder.addVar(lo, hi, boolCost(b), tag = b)
                boolCol[b] = c
                colVarId.add(b)
                colIsBool.add(1)
            }
            return c
        }

        /** Emit `Σ coeffs[k]·x_{vars[k]} (+ auxCoeff·x_aux) rel rhs`; pass `auxCol = -1` for no aux.
         *  [global] marks whether the row holds at every solution (see [LpModel.rowGlobal]); only
         *  rows whose constants baked in live domain bounds pass `false`. */
        @Suppress("LongParameterList")
        private fun addIntRow(
            vars: IntArray,
            coeffs: IntArray,
            auxCol: Int,
            auxCoeff: Long,
            rel: Relation,
            rhs: Long,
            global: Boolean = true,
            premises: LpRowPremises? = null,
        ) {
            val extra = if (auxCol >= 0) 1 else 0
            val cols = IntArray(vars.size + extra)
            val vals = LongArray(vars.size + extra)
            for (k in vars.indices) {
                cols[k] = intColumn(vars[k])
                vals[k] = coeffs[k].toLong()
            }
            if (auxCol >= 0) {
                cols[vars.size] = auxCol
                vals[vars.size] = auxCoeff
            }
            builder.addRow(cols, vals, rel, rhs, global, premises)
        }

        /**
         * Emit `Σ weights[k]·literal[k] rel rhs` over Boolean literals. A positive literal counts
         * `+w·x_b`, a negative literal `+w·(1 − x_b) = w − w·x_b`; the `+w` constants accumulate
         * and move to the right-hand side. [weights] of `null` means unit weights (cardinality).
         */
        private fun addBoolRow(literals: IntArray, weights: IntArray?, rel: Relation, rhs: Long) {
            val cols = IntArray(literals.size)
            val vals = LongArray(literals.size)
            var constant = 0L
            for (k in literals.indices) {
                val lit = literals[k]
                val w = (weights?.get(k) ?: 1).toLong()
                cols[k] = boolColumn(Lit.variable(lit))
                if (Lit.isPositive(lit)) {
                    vals[k] = w
                } else {
                    vals[k] = -w
                    constant = addExact(constant, w)
                }
            }
            builder.addRow(cols, vals, rel, subExact(rhs, constant))
        }

        /**
         * Indicator rows for `auxBoolVar ↔ (L op bound)` via big-M, where `L = Σ coeffs·vars`. The
         * big-Ms are the tightest possible from the live range `[lMin, lMax]` of `L`, and the
         * `¬(L op bound)` side uses integrality (`¬(L ≤ bound) ⇔ L ≥ bound + 1`) so the rows are as
         * strong as a single indicator allows.
         *
         * For `EQ` only the `aux = 1 ⇒ L = bound` direction is emitted, and for `NE` only the
         * `aux = 0 ⇒ L = bound` direction: the complementary side is the disjunction `L ≠ bound`,
         * whose convex hull is the whole interval, so it yields no valid LP cut and is dropped.
         *
         * A live big-M bakes branch-tightened bounds into the row's constants, so the row only holds
         * inside the node's box. Each row is therefore marked global exactly when its M equals the
         * M the *declared* range `[lMinD, lMaxD]` would give — then the relaxed face spans the whole
         * declared box and the row holds at every solution (see [LpModel.rowGlobal]). A non-global
         * row records the live bounds its M rests on as [LpRowPremises] — the lMax-side rows cite
         * each variable's M-relevant live bound (`≤ max` for positive coefficients, `≥ min` for
         * negative; mirrored for lMin-side rows) — so a certificate that leans on the row can cite
         * those atoms instead of being withheld (see [LpExplanation]).
         */
        private fun reifiedRows(rl: ReifiedLinear) {
            var lMin = 0L
            var lMax = 0L
            var lMinD = 0L
            var lMaxD = 0L
            for (k in rl.vars.indices) {
                val c = rl.coeffs[k].toLong()
                val dom = session.intDomain(rl.vars[k])
                val dec = problem.intDomains[rl.vars[k]]
                if (c >= 0L) {
                    lMin = addExact(lMin, mulExact(c, dom.min.toLong()))
                    lMax = addExact(lMax, mulExact(c, dom.max.toLong()))
                    lMinD = addExact(lMinD, mulExact(c, dec.min.toLong()))
                    lMaxD = addExact(lMaxD, mulExact(c, dec.max.toLong()))
                } else {
                    lMin = addExact(lMin, mulExact(c, dom.max.toLong()))
                    lMax = addExact(lMax, mulExact(c, dom.min.toLong()))
                    lMinD = addExact(lMinD, mulExact(c, dec.max.toLong()))
                    lMaxD = addExact(lMaxD, mulExact(c, dec.min.toLong()))
                }
            }
            val a = boolColumn(rl.auxBoolVar)
            val bound = rl.bound.toLong()
            val boundUp = addExact(bound, 1L) // L ≥ bound + 1 is the integer negation of L ≤ bound
            val boundDown = subExact(bound, 1L)

            // The live bounds the lMax side (maxSide = true) or lMin side of the M rests on; only
            // bounds tighter than declared are cited (the rest hold everywhere).
            fun sidePremises(maxSide: Boolean): LpRowPremises {
                val pv = IntArrayList()
                val pt = IntArrayList()
                val pu = ArrayList<Boolean>()
                for (k in rl.vars.indices) {
                    val c = rl.coeffs[k]
                    if (c == 0) continue
                    val v = rl.vars[k]
                    val dom = session.intDomain(v)
                    val dec = problem.intDomains[v]
                    if ((c >= 0) == maxSide) {
                        if (dom.max != dec.max) {
                            pv.add(v)
                            pu.add(true)
                            pt.add(dom.max)
                        }
                    } else if (dom.min != dec.min) {
                        pv.add(v)
                        pu.add(false)
                        pt.add(dom.min)
                    }
                }
                return LpRowPremises(pv.toIntArray(), BooleanArray(pu.size) { pu[it] }, pt.toIntArray())
            }

            fun row(auxCoeff: Long, rel: Relation, rhs: Long, global: Boolean, maxSide: Boolean) = addIntRow(
                rl.vars,
                rl.coeffs,
                a,
                auxCoeff,
                rel,
                rhs,
                global,
                premises = if (global) null else sidePremises(maxSide),
            )

            when (rl.op) {
                LinearOp.LE -> {
                    val m1 = maxOf(0L, subExact(lMax, bound)) // aux=1 ⇒ L ≤ bound
                    row(m1, Relation.LE, addExact(bound, m1), m1 == maxOf(0L, subExact(lMaxD, bound)), maxSide = true)
                    val m2 = maxOf(0L, subExact(boundUp, lMin)) // aux=0 ⇒ L ≥ bound+1
                    row(m2, Relation.GE, boundUp, m2 == maxOf(0L, subExact(boundUp, lMinD)), maxSide = false)
                }

                LinearOp.GE -> {
                    val m1 = maxOf(0L, subExact(bound, lMin)) // aux=1 ⇒ L ≥ bound
                    row(
                        -m1,
                        Relation.GE,
                        subExact(bound, m1),
                        m1 == maxOf(0L, subExact(bound, lMinD)),
                        maxSide = false,
                    )
                    val m2 = maxOf(0L, subExact(lMax, boundDown)) // aux=0 ⇒ L ≤ bound-1
                    row(
                        -m2,
                        Relation.LE,
                        boundDown,
                        m2 == maxOf(0L, subExact(lMaxD, boundDown)),
                        maxSide = true,
                    )
                }

                LinearOp.EQ -> {
                    val mHi = maxOf(0L, subExact(lMax, bound)) // aux=1 ⇒ L ≤ bound
                    row(
                        mHi,
                        Relation.LE,
                        addExact(bound, mHi),
                        mHi == maxOf(0L, subExact(lMaxD, bound)),
                        maxSide = true,
                    )
                    val mLo = maxOf(0L, subExact(bound, lMin)) // aux=1 ⇒ L ≥ bound
                    row(
                        -mLo,
                        Relation.GE,
                        subExact(bound, mLo),
                        mLo == maxOf(0L, subExact(bound, lMinD)),
                        maxSide = false,
                    )
                }

                LinearOp.NE -> {
                    val mHi = maxOf(0L, subExact(lMax, bound)) // aux=0 ⇒ L ≤ bound
                    row(-mHi, Relation.LE, bound, mHi == maxOf(0L, subExact(lMaxD, bound)), maxSide = true)
                    val mLo = maxOf(0L, subExact(bound, lMin)) // aux=0 ⇒ L ≥ bound
                    row(mLo, Relation.GE, bound, mLo == maxOf(0L, subExact(bound, lMinD)), maxSide = false)
                }
            }
        }

        fun assemble(extraCuts: List<Cut>): LpRelaxation {
            // Materialize objective-only variables first so the relaxed objective is complete.
            objective?.let { obj ->
                for (i in obj.intCoefficients.indices) if (obj.intCoefficients[i] != 0L) intColumn(i)
                for (b in obj.boolWeights.indices) if (obj.boolWeights[b] != 0L) boolColumn(b)
            }
            // Cut generation needs columns for the globals' variables even when nothing else
            // references them, so a separator has something to write the cut over.
            if (generateCuts) {
                // The all-different family (AllDifferent, SymmetricAllDifferent, both Inverse sides)
                // feeds the Hall-sum and assignment cuts; closed GlobalCardinality feeds the GCC cut.
                for (group in allDifferentGroups(problem)) for (v in group) intColumn(v)
                for (factor in problem.factors) {
                    if (factor is GlobalCardinality && factor.closed && factor.presents.isEmpty()) {
                        for (v in factor.xs) intColumn(v)
                    }
                }
            }
            if (circuitArcs) {
                for (factor in problem.factors) if (factor is Circuit) buildCircuitArcs(factor)
            }
            if (elementHull) {
                for (factor in problem.factors) if (factor is Element) buildElementHull(factor)
            }
            if (tableHull) {
                for (factor in problem.factors) if (factor is Table) buildTableHull(factor)
            }

            for (factor in problem.factors) {
                when (factor) {
                    is Linear -> linearRow(factor.op, factor.vars, factor.coeffs, factor.bound.toLong())

                    is ReifiedLinear -> reifiedRows(factor)

                    is Cardinality -> {
                        addBoolRow(factor.literals, null, Relation.GE, factor.min.toLong())
                        addBoolRow(factor.literals, null, Relation.LE, factor.max.toLong())
                    }

                    is Clause -> addBoolRow(factor.literals, null, Relation.GE, 1L)

                    // result = max(xs) / min(xs) — the extremum is the envelope of its operands:
                    // result ≥ xs[i] for max, result ≤ xs[i] for min. One row per operand, sound
                    // (the opposite face, result tight to some operand, is not a single LP cut).
                    is ArrayMinMax -> {
                        val rel = if (factor.max) Relation.GE else Relation.LE
                        for (x in factor.xs) {
                            addIntRow(
                                intArrayOf(factor.result),
                                intArrayOf(1),
                                auxCol = intColumn(x),
                                auxCoeff = -1L,
                                rel = rel,
                                rhs = 0L,
                            )
                        }
                    }

                    is PseudoBoolean -> {
                        val rel = when (factor.op) {
                            PbOp.LE -> Relation.LE
                            PbOp.GE -> Relation.GE
                            PbOp.EQ -> Relation.EQ
                        }
                        addBoolRow(factor.literals, factor.weights, rel, factor.bound.toLong())
                    }

                    else -> Unit // hard globals and unrecognized factors: handled elsewhere or skipped
                }
            }

            // Separator-produced cuts, over already-created columns. A cut referencing an absent
            // column is dropped (defensive — separators should only emit over existing columns).
            for (cut in extraCuts) {
                if (cut.cols.all { it in 0 until builder.varCount }) {
                    builder.addRow(cut.cols, cut.coeffs, cut.rel, cut.rhs, cut.global)
                }
            }

            val model = builder.build(Sense.MINIMIZE)
            val kinds = BooleanArray(colIsBool.size) { colIsBool[it] == 1 }
            return LpRelaxation(
                model = model,
                colVarId = IntArray(colVarId.size) { colVarId[it] },
                colIsBool = kinds,
                objectiveConstant = objective?.constant ?: 0L,
                intColOf = intCol.copyOf(),
                boolColOf = boolCol.copyOf(),
                circuitArcs = circuitModels,
            )
        }

        /**
         * Arc-indicator relaxation of one [Circuit] over `succ[0..n)`: a column `y_ij ∈ [0,1]` per
         * candidate arc (`j` in the declared domain of `succ[i]`, `j ≠ i` — circuit forbids self
         * loops), pinned to 0 when `j` left the live domain. Rows: out-degree `Σ_j y_ij = 1`,
         * in-degree `Σ_i y_ij = 1`, and channelling `Σ_j j·y_ij = succ[i]` tying arcs to the integer
         * column. Integer solutions are then permutations; [CircuitSeparator] removes the subtours.
         * The column *layout* uses the declared domain so it is identical across nodes (warm-start
         * safe). Circuits with more than [MAX_NODES] nodes are skipped (the O(n²) LP would dominate).
         */
        private fun buildCircuitArcs(factor: Circuit) {
            val succ = factor.succ
            val n = succ.size
            if (n < 2 || n > MAX_NODES) return
            val arcCol = Array(n) { IntArray(n) { -1 } }
            // Out-degree and channelling rows, building the arc columns on the way.
            for (i in 0 until n) {
                val live = session.intDomain(succ[i])
                val outCols = IntArrayList()
                val chanCols = IntArrayList()
                val chanCoef = IntArrayList()
                problem.intDomains[succ[i]].forEach { j ->
                    if (j == i || j < 0 || j >= n) return@forEach
                    val present = live.contains(j)
                    val col = auxColumn(0L, if (present) 1L else 0L)
                    arcCol[i][j] = col
                    outCols.add(col)
                    chanCols.add(col)
                    chanCoef.add(j)
                }
                if (outCols.isEmpty()) return // degenerate: no candidate successor — leave to propagation
                builder.addRow(outCols.toIntArray(), LongArray(outCols.size) { 1L }, Relation.EQ, 1L)
                // Σ_j j·y_ij − succ[i] = 0.
                val cCols = IntArray(chanCols.size + 1)
                val cVals = LongArray(chanCols.size + 1)
                for (k in 0 until chanCols.size) {
                    cCols[k] = chanCols[k]
                    cVals[k] = chanCoef[k].toLong()
                }
                cCols[chanCols.size] = intColumn(succ[i])
                cVals[chanCols.size] = -1L
                builder.addRow(cCols, cVals, Relation.EQ, 0L)
            }
            // In-degree rows: Σ_i y_ij = 1 for each node j that is some arc's head.
            for (j in 0 until n) {
                val inCols = IntArrayList()
                for (i in 0 until n) if (arcCol[i][j] >= 0) inCols.add(arcCol[i][j])
                if (!inCols.isEmpty()) {
                    builder.addRow(inCols.toIntArray(), LongArray(inCols.size) { 1L }, Relation.EQ, 1L)
                }
            }
            circuitModels.add(CircuitArcModel(n, arcCol))
        }

        /**
         * Convex-hull linearization of one [Table] `(xs) ∈ tuples`: a selector column `y_t ∈ [0,1]`
         * per allowed tuple, with `Σ_t y_t = 1` and a per-column channel `xs[j] = Σ_t tuple_t[j]·y_t`.
         * The projection onto `xs` is exactly the convex hull of the allowed tuples — the strongest
         * linear relaxation of the table. A tuple's column exists when every entry is in the declared
         * domain of its variable (layout stable across nodes), and is pinned to 0 when any entry has
         * left the live domain. Tables with more than [MAX_TUPLES] rows are skipped.
         */
        private fun buildTableHull(factor: Table) {
            val numTuples = factor.numTuples
            if (numTuples > MAX_TUPLES) return
            val arity = factor.arity
            val declared = Array(arity) { c -> problem.intDomains[factor.xs[c]] }
            val live = Array(arity) { c -> session.intDomain(factor.xs[c]) }
            val selCols = IntArrayList()
            val rows = IntArrayList()
            for (t in 0 until numTuples) {
                var declaredFeasible = true
                var liveFeasible = true
                for (col in 0 until arity) {
                    val v = factor.tuples[t * arity + col]
                    if (v !in declared[col]) {
                        declaredFeasible = false
                        break
                    }
                    if (v !in live[col]) liveFeasible = false
                }
                if (!declaredFeasible) continue
                selCols.add(auxColumn(0L, if (liveFeasible) 1L else 0L))
                rows.add(t)
            }
            val k = selCols.size
            if (k == 0) return // no tuple feasible under the declared domains — leave it to propagation
            builder.addRow(selCols.toIntArray(), LongArray(k) { 1L }, Relation.EQ, 1L)
            // xs[col] − Σ_t tuple_t[col]·y_t = 0 for each column.
            for (col in 0 until arity) {
                val cols = IntArray(k + 1)
                val vals = LongArray(k + 1)
                for (s in 0 until k) {
                    cols[s] = selCols[s]
                    vals[s] = -factor.tuples[rows[s] * arity + col].toLong()
                }
                cols[k] = intColumn(factor.xs[col])
                vals[k] = 1L
                builder.addRow(cols, vals, Relation.EQ, 0L)
            }
        }

        /**
         * One-hot selector linearization of one [Element] `result = arr[idx − indexOffset]` over a
         * *constant* array — the exact convex hull. A selector column `y_p ∈ [0,1]` for each position
         * `p` whose index value `p + indexOffset` is in `idx`'s declared domain (layout stable across
         * nodes; pinned to 0 when that value left the live domain). Rows: `Σ_p y_p = 1`, index channel
         * `Σ_p (p + off)·y_p = idx`, and result channel `Σ_p arr[p]·y_p = result`. Arrays longer than
         * [MAX_ELEM] are skipped (the added columns would dominate). Variable arrays are not handled
         * here — their channel is bilinear, so a sound big-M form is a separate follow-up.
         */
        private fun buildElementHull(factor: Element) {
            if (factor.arrIsVars) return
            val len = factor.arr.size
            if (len > MAX_ELEM) return
            val off = factor.indexOffset
            val declared = problem.intDomains[factor.idx]
            val live = session.intDomain(factor.idx)
            val selCols = IntArrayList()
            val positions = IntArrayList()
            for (p in 0 until len) {
                val idxVal = p + off
                if (idxVal !in declared) continue
                selCols.add(auxColumn(0L, if (idxVal in live) 1L else 0L))
                positions.add(p)
            }
            val k = selCols.size
            if (k == 0) return
            builder.addRow(selCols.toIntArray(), LongArray(k) { 1L }, Relation.EQ, 1L)
            // Σ_p (p + off)·y_p − idx = 0.
            val idxCols = IntArray(k + 1)
            val idxVals = LongArray(k + 1)
            for (t in 0 until k) {
                idxCols[t] = selCols[t]
                idxVals[t] = (positions[t] + off).toLong()
            }
            idxCols[k] = intColumn(factor.idx)
            idxVals[k] = -1L
            builder.addRow(idxCols, idxVals, Relation.EQ, 0L)
            // Σ_p arr[p]·y_p − result = 0: the exact convex hull of the constant table.
            val resCols = IntArray(k + 1)
            val resVals = LongArray(k + 1)
            for (t in 0 until k) {
                resCols[t] = selCols[t]
                resVals[t] = factor.arr[positions[t]].toLong()
            }
            resCols[k] = intColumn(factor.result)
            resVals[k] = -1L
            builder.addRow(resCols, resVals, Relation.EQ, 0L)
        }

        private fun linearRow(op: LinearOp, vars: IntArray, coeffs: IntArray, bound: Long) {
            val rel = when (op) {
                LinearOp.LE -> Relation.LE
                LinearOp.GE -> Relation.GE
                LinearOp.EQ -> Relation.EQ
                LinearOp.NE -> return // not LP-relaxable
            }
            addIntRow(vars, coeffs, auxCol = -1, auxCoeff = 0L, rel = rel, rhs = bound)
        }
    }
}
