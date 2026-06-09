package com.eignex.klause.solver.lp

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList

/**
 * An LP relaxation of a [Problem] at one search node, plus the metadata mapping each LP column
 * back to the CP variable it stands for. The mapping is what lets reduced-cost fixing (#21) turn
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
    /** Integer variable id → its LP column, or -1 if none. For separators to write cuts (#22). */
    val intColOf: IntArray,
    /** Boolean variable id → its LP column, or -1 if none. */
    val boolColOf: IntArray,
    /** Arc-indicator models of any Circuit factors, for the subtour-elimination separator. */
    val circuitArcs: List<CircuitArcModel> = emptyList(),
)

/**
 * Walks [Problem.factors] and emits an [LpModel] relaxation for the LP-emittable factor types,
 * pulling variable bounds live from the current search node (#19).
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
 *  handled by cut generation (#22) or Lagrangian relaxation (#23). Unrecognized factors are
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
) {
    private companion object {
        /** Above this node count the O(n²)-column circuit arc model is skipped. */
        const val MAX_NODES: Int = 24
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

        /** Emit `Σ coeffs[k]·x_{vars[k]} (+ auxCoeff·x_aux) rel rhs`; pass `auxCol = -1` for no aux. */
        private fun addIntRow(
            vars: IntArray,
            coeffs: IntArray,
            auxCol: Int,
            auxCoeff: Long,
            rel: Relation,
            rhs: Long,
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
            builder.addRow(cols, vals, rel, rhs)
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
         */
        private fun reifiedRows(rl: ReifiedLinear) {
            var lMin = 0L
            var lMax = 0L
            for (k in rl.vars.indices) {
                val c = rl.coeffs[k].toLong()
                val dom = session.intDomain(rl.vars[k])
                val lo = dom.min.toLong()
                val hi = dom.max.toLong()
                if (c >= 0L) {
                    lMin = addExact(lMin, mulExact(c, lo))
                    lMax = addExact(lMax, mulExact(c, hi))
                } else {
                    lMin = addExact(lMin, mulExact(c, hi))
                    lMax = addExact(lMax, mulExact(c, lo))
                }
            }
            val a = boolColumn(rl.auxBoolVar)
            val bound = rl.bound.toLong()
            val boundUp = addExact(bound, 1L) // L ≥ bound + 1 is the integer negation of L ≤ bound
            val boundDown = subExact(bound, 1L)

            fun row(auxCoeff: Long, rel: Relation, rhs: Long) = addIntRow(rl.vars, rl.coeffs, a, auxCoeff, rel, rhs)

            when (rl.op) {
                LinearOp.LE -> {
                    val m1 = maxOf(0L, subExact(lMax, bound)) // aux=1 ⇒ L ≤ bound
                    row(m1, Relation.LE, addExact(bound, m1))
                    val m2 = maxOf(0L, subExact(boundUp, lMin)) // aux=0 ⇒ L ≥ bound+1
                    row(m2, Relation.GE, boundUp)
                }

                LinearOp.GE -> {
                    val m1 = maxOf(0L, subExact(bound, lMin)) // aux=1 ⇒ L ≥ bound
                    row(-m1, Relation.GE, subExact(bound, m1))
                    val m2 = maxOf(0L, subExact(lMax, boundDown)) // aux=0 ⇒ L ≤ bound-1
                    row(-m2, Relation.LE, boundDown)
                }

                LinearOp.EQ -> {
                    val mHi = maxOf(0L, subExact(lMax, bound)) // aux=1 ⇒ L ≤ bound
                    row(mHi, Relation.LE, addExact(bound, mHi))
                    val mLo = maxOf(0L, subExact(bound, lMin)) // aux=1 ⇒ L ≥ bound
                    row(-mLo, Relation.GE, subExact(bound, mLo))
                }

                LinearOp.NE -> {
                    val mHi = maxOf(0L, subExact(lMax, bound)) // aux=0 ⇒ L ≤ bound
                    row(-mHi, Relation.LE, bound)
                    val mLo = maxOf(0L, subExact(bound, lMin)) // aux=0 ⇒ L ≥ bound
                    row(mLo, Relation.GE, bound)
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
                for (factor in problem.factors) {
                    when (factor) {
                        is AllDifferent -> for (v in factor.vars) intColumn(v)

                        is GlobalCardinality -> if (factor.closed && factor.presents.isEmpty()) {
                            for (v in factor.xs) intColumn(v)
                        }

                        else -> Unit
                    }
                }
            }
            if (circuitArcs) {
                for (factor in problem.factors) if (factor is Circuit) buildCircuitArcs(factor)
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
                    builder.addRow(cut.cols, cut.coeffs, cut.rel, cut.rhs)
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
