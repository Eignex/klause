package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.model.AllDifferent
import com.eignex.klause.model.AllDifferentOpt
import com.eignex.klause.model.And
import com.eignex.klause.model.AtLeast
import com.eignex.klause.model.AtMost
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.CardinalityExpr
import com.eignex.klause.model.CircuitExpr
import com.eignex.klause.model.CostMddExpr
import com.eignex.klause.model.CostRegularExpr
import com.eignex.klause.model.CumulativeExpr
import com.eignex.klause.model.CumulativeExprOpt
import com.eignex.klause.model.DiffnExpr
import com.eignex.klause.model.DisjunctiveExpr
import com.eignex.klause.model.DisjunctiveExprOpt
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.GccExprOpt
import com.eignex.klause.model.Iff
import com.eignex.klause.model.Implies
import com.eignex.klause.model.IncreasingExpr
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.InverseChannel
import com.eignex.klause.model.MddExpr
import com.eignex.klause.model.NValueExprOpt
import com.eignex.klause.model.NValueMode
import com.eignex.klause.model.NominalEq
import com.eignex.klause.model.Not
import com.eignex.klause.model.Or
import com.eignex.klause.model.PseudoBooleanExpr
import com.eignex.klause.model.RegularExpr
import com.eignex.klause.model.SetDisjoint
import com.eignex.klause.model.SetEq
import com.eignex.klause.model.SetIn
import com.eignex.klause.model.SetNominalIn
import com.eignex.klause.model.SetSubsetOf
import com.eignex.klause.model.SortExpr
import com.eignex.klause.model.SubcircuitExpr
import com.eignex.klause.model.SymmetricAllDifferent
import com.eignex.klause.model.TableConstraint
import com.eignex.klause.model.XorExpr
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import kotlin.math.ceil
import kotlin.math.floor
import com.eignex.klause.factor.circuit.Circuit as CircuitFactor
import com.eignex.klause.factor.global.AllDifferent as AllDifferentFactor
import com.eignex.klause.factor.global.GlobalCardinality as GccFactor
import com.eignex.klause.factor.global.Increasing as IncreasingFactor
import com.eignex.klause.factor.global.Inverse as InverseFactor
import com.eignex.klause.factor.global.NValue as NValueFactor
import com.eignex.klause.factor.global.Sort as SortFactor
import com.eignex.klause.factor.global.SymmetricAllDifferent as SymmetricAllDifferentFactor
import com.eignex.klause.factor.scheduling.Cumulative as CumulativeFactor
import com.eignex.klause.factor.scheduling.Diffn as DiffnFactor
import com.eignex.klause.factor.table.Regular as RegularFactor

/**
 * Top-level constraint assertion handlers for [Lowering]. The DSL drops a tree of
 * [BoolExpr] into [assertExpr]; this file owns the dispatch into per-shape emitters
 * ([assertAllDifferent], [assertCircuit], [assertCumulative], ...) and the integer-
 * comparison normalisation ([assertIntCompare] / [emitTopLevelCmp] / [emitSingleVar]).
 * Sub-expression-level lowering (`lowerToLit`, `reify*`, `tseitin*`) lives in
 * `CompilerLowering`; affine-fragment lift lives in `CompilerLift`.
 */
internal fun Lowering.assertExpr(expr: BoolExpr) {
    when (expr) {
        is And -> for (c in expr.children) assertExpr(c)

        is Implies -> assertExpr(Or(listOf(negate(expr.left), expr.right)))

        is Iff -> {
            assertExpr(Implies(expr.left, expr.right))
            assertExpr(Implies(expr.right, expr.left))
        }

        is Or -> {
            val lits = IntArray(expr.children.size)
            for (i in expr.children.indices) lits[i] = lowerToLit(expr.children[i])
            factors += Clause(lits)
        }

        is AtMost -> {
            val lits = lowerAllBool(expr.children)
            factors += Cardinality(lits, 0, expr.k)
        }

        is AtLeast -> {
            val lits = lowerAllBool(expr.children)
            factors += Cardinality(lits, expr.k, lits.size)
        }

        is CardinalityExpr -> {
            val lits = lowerAllBool(expr.children)
            factors += Cardinality(lits, expr.min, expr.max)
        }

        is Not, is BoolRef, is NominalEq -> {
            factors += Clause(intArrayOf(lowerToLit(expr)))
        }

        is IntCompare -> assertIntCompare(expr)

        is FloatLinearConstraint -> assertFloatLinear(expr)

        is AllDifferent -> assertAllDifferent(expr.terms)

        is SymmetricAllDifferent -> assertSymmetricAllDifferent(expr)

        is InverseChannel -> assertInverse(expr)

        is MddExpr -> assertMdd(expr)

        is CostMddExpr -> assertCostMdd(expr)

        is CostRegularExpr -> assertCostRegular(expr)

        is CircuitExpr -> assertCircuit(expr.succ, expr.valueOffset, sub = false)

        is SubcircuitExpr -> assertCircuit(expr.succ, expr.valueOffset, sub = true)

        is CumulativeExpr -> assertCumulative(expr)

        is DisjunctiveExpr -> assertDisjunctive(expr)

        is SortExpr -> assertSort(expr)

        is IncreasingExpr -> assertIncreasing(expr)

        is DiffnExpr -> assertDiffn(expr)

        is RegularExpr -> assertRegular(expr)

        is AllDifferentOpt -> assertAllDifferentOpt(expr)

        is CumulativeExprOpt -> assertCumulativeOpt(expr)

        is DisjunctiveExprOpt -> assertDisjunctiveOpt(expr)

        is NValueExprOpt -> assertNValueOpt(expr)

        is GccExprOpt -> assertGccOpt(expr)

        is SetIn -> assertSetIn(expr)

        is SetNominalIn -> assertSetNominalIn(expr)

        is SetSubsetOf -> assertSetSubsetOf(expr)

        is SetDisjoint -> assertSetDisjoint(expr)

        is SetEq -> assertSetEq(expr)

        is TableConstraint -> assertExpr(expandTable(expr))

        is PseudoBooleanExpr -> {
            val lits = lowerAllBool(expr.lits)
            factors += PseudoBoolean(
                weights = LongArray(expr.weights.size) { expr.weights[it].toLong() },
                literals = lits,
                op = expr.op,
                bound = expr.bound.toLong(),
            )
        }

        is XorExpr -> {
            val lits = lowerAllBool(expr.children)
            factors += Xor(lits, targetParity = 1)
        }
    }
}

internal fun Lowering.expandTable(t: TableConstraint): BoolExpr {
    val lifted = t.terms.map { lift(it) }
    val tuples = t.tuples.map { tup ->
        And(
            lifted.indices.map { i ->
                IntCompare(lifted[i], IntCmpOp.EQ, IntLit(tup[i]))
            },
        )
    }
    return if (t.negative) {
        And(tuples.map { Not(it) })
    } else {
        if (tuples.size == 1) tuples[0] else Or(tuples)
    }
}

/**
 * Lower a [FloatLinearConstraint] by bucketing each referenced float variable using its declared
 * `FloatSpec.buckets` and emitting a scaled-integer [Linear] factor — what the engines solve over.
 *
 * Scaling math (per float var `v` with interval `[lo, hi]` and `N` buckets, step
 * `step = (hi - lo) / (N - 1)`): substitute `v = lo + b · step` and rearrange to
 * `Σ (c_v · step_v) · b_v ⟨op⟩ bound − Σ c_v · lo_v`, then multiply by `SCALE` and
 * round to integer coefficients. Discretisation error is ~1/SCALE per term.
 */
internal fun Lowering.assertFloatLinear(c: FloatLinearConstraint) {
    val n = c.varNames.size
    val realOp = when (c.op) {
        IntCmpOp.LE,
        IntCmpOp.LT,
        -> LinearOp.LE

        IntCmpOp.GE,
        IntCmpOp.GT,
        -> LinearOp.GE

        IntCmpOp.EQ -> LinearOp.EQ

        IntCmpOp.NE -> LinearOp.NE
    }
    if (schemaFloatsLpOnly) {
        // LP-only continuous columns (issue #1232): emit the raw double coefficients over the real
        // columns, no bucket scaling. The gate admits only LE/GE/EQ float-linears here.
        val realVars = IntArray(n) { realVarIdByName.getValue(c.varNames[it]) }
        factors += Linear(EmptyIntArray, EmptyDoubleArray, realVars, c.coeffs.copyOf(), realOp, c.bound)
        return
    }
    // Bucketed-int rewrite for the factor list. Reuses the same SCALE
    // historically used by [com.eignex.klause.schema.FloatExpr.multiHandleBucketCompare].
    val scale = 1_000_000.0
    var scaledBound = c.bound
    val scaledCoeffs = IntArray(n)
    val intVarIds = IntArray(n)
    for (i in 0 until n) {
        val name = c.varNames[i]
        val fid = floatVarIdByName.getValue(name)
        val interval = floatMetaIntervals[fid]
        val buckets = floatMetaBuckets[fid]
        val step = if (buckets > 1) (interval.hi - interval.lo) / (buckets - 1) else 0.0
        scaledCoeffs[i] = (c.coeffs[i] * step * scale).toLong().toInt()
        intVarIds[i] = floatMetaIntVarIds[fid]
        scaledBound -= c.coeffs[i] * interval.lo
    }
    // The scaled sum is integer-valued, so for a real bound B (= scaledBound * scale) a strict
    // `< B` is exactly `≤ ceil(B) − 1` and `> B` is `≥ floor(B) + 1`. Using ceil/floor (rather than
    // truncate-then-±1) is correct when B is non-integral and for either sign (#83). Non-strict ops
    // keep the truncated bound.
    val scaledReal = scaledBound * scale
    val scaledBoundLong = when (c.op) {
        IntCmpOp.LT -> ceil(scaledReal).toLong() - 1
        IntCmpOp.GT -> floor(scaledReal).toLong() + 1
        else -> scaledReal.toLong()
    }
    val scaledBoundInt = scaledBoundLong.toInt()
    factors += Linear(scaledCoeffs, intVarIds, realOp, scaledBoundInt)
}

internal fun Lowering.assertAllDifferent(terms: List<IntExpr>) {
    val lifted = terms.map { lift(it) }
    // Specialisation: when every operand is a bare IntRef (no arithmetic residual), emit
    // the global factor. Otherwise fall back to pairwise NE through the existing
    // reification path.
    if (lifted.all { it is IntRef }) {
        val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        if (ids.toSet().size == ids.size) {
            val (dMin, span) = domainMinAndSpan(ids)
            factors += AllDifferentFactor(ids, dMin, span.toInt())
            return
        }
    }
    for (i in lifted.indices) {
        for (j in i + 1 until lifted.size) {
            assertExpr(IntCompare(lifted[i], IntCmpOp.NE, lifted[j]))
        }
    }
}

/** Lift `e` to a solver int-var id, materialising an aux var pinned equal when the lifted
 *  form carries an arithmetic residual rather than being a bare variable reference. The
 *  channeling globals below need concrete var ids to post their native factor. */
internal fun Lowering.varIdOfLifted(e: IntExpr): Int {
    val lifted = lift(e)
    if (lifted is IntRef) return intVarOf(lifted.name)
    val aux = newAuxIntVar(domainOf(lifted))
    assertExpr(IntCompare(IntRef(aux), IntCmpOp.EQ, lifted))
    return intVarOf(aux)
}

/** Lift each operand to a bare [IntRef] and resolve it to its int-var id. Globals only accept
 *  bare variable references (no arithmetic residual); a non-bare operand fails with a
 *  [context]-tagged message naming the operand role [term]. */
private fun Lowering.liftToIntRefIds(exprs: List<IntExpr>, context: String, term: String = "start"): IntArray {
    val lifted = exprs.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "$context: every $term term must be a bare variable reference (no arithmetic)."
    }
    return IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
}

/** Smallest domain min and total value span (`max − min + 1`) across [ids]' current domains —
 *  the value-occurrence range an [AllDifferentFactor] indexes over. */
private fun Lowering.domainMinAndSpan(ids: IntArray): Pair<Long, Long> {
    var dMin = intDomains[ids[0]].min
    var dMax = intDomains[ids[0]].max
    for (id in ids) {
        val d = intDomains[id]
        if (d.min < dMin) dMin = d.min
        if (d.max > dMax) dMax = d.max
    }
    return dMin to (dMax - dMin + 1)
}

internal fun Lowering.assertSymmetricAllDifferent(expr: SymmetricAllDifferent) {
    val ids = IntArray(expr.terms.size) { varIdOfLifted(expr.terms[it]) }
    factors += SymmetricAllDifferentFactor(ids, indexOffset = expr.indexOffset)
}

internal fun Lowering.assertInverse(expr: InverseChannel) {
    val f = IntArray(expr.f.size) { varIdOfLifted(expr.f[it]) }
    val g = IntArray(expr.g.size) { varIdOfLifted(expr.g[it]) }
    factors += InverseFactor(f, g, fOffset = expr.fOffset, gOffset = expr.gOffset)
}

/**
 * Lower [CircuitExpr] / [SubcircuitExpr] to its native factor. Each `succ` term must
 * lift to a bare [IntRef]. When [valueOffset] is nonzero (e.g. 1 for FlatZinc-style
 * 1-indexed inputs), aux 0-indexed int vars are allocated and channeled to the original
 * vars via a Linear factor — the factor itself stays 0-indexed.
 */
internal fun Lowering.assertCircuit(succ: List<IntExpr>, valueOffset: Int, sub: Boolean) {
    val n = succ.size
    val lifted = succ.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "${if (sub) "subcircuit" else "circuit"}: every successor term must be a bare " +
            "variable reference (no arithmetic). Got ${lifted.map { it::class.simpleName }}."
    }
    val srcIds = IntArray(n) { intVarOf((lifted[it] as IntRef).name) }
    val ids = if (valueOffset == 0) {
        srcIds
    } else {
        IntArray(n) { i ->
            // Channel: aux = src - valueOffset, with aux ∈ [0, n − 1].
            val auxId = newIntVar(IntDomain(0L, (n - 1).toLong()))
            factors += Linear(
                coeffs = intArrayOf(1, -1),
                vars = intArrayOf(srcIds[i], auxId),
                op = LinearOp.EQ,
                bound = valueOffset,
            )
            auxId
        }
    }
    factors += CircuitFactor(succ = ids, subcircuit = sub)
}

internal fun Lowering.assertCumulative(expr: CumulativeExpr) {
    val ids = liftToIntRefIds(expr.starts, "cumulative")
    factors += CumulativeFactor(
        starts = ids,
        durations = LongArray(expr.durations.size) { expr.durations[it].toLong() },
        resources = LongArray(expr.resources.size) { expr.resources[it].toLong() },
        capacity = expr.capacity.toLong(),
    )
}

/**
 * Lower each presence [BoolExpr] in [presents] to a solver literal. Used by every opt-aware
 * global to thread presence into its factor's `presents: IntArray`.
 */
private fun Lowering.lowerPresences(presents: List<BoolExpr>): IntArray {
    val out = IntArray(presents.size)
    for (i in presents.indices) out[i] = lowerToLit(presents[i])
    return out
}

/** AllDifferent over an opt-presence-gated subset. Bare-IntRef operands map directly to the
 *  global factor; non-bare operands fall back to presence-guarded pairwise NE. */
internal fun Lowering.assertAllDifferentOpt(expr: AllDifferentOpt) {
    val lifted = expr.terms.map { lift(it) }
    val presentLits = lowerPresences(expr.presents)
    if (lifted.all { it is IntRef }) {
        val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        if (ids.toSet().size == ids.size) {
            val (dMin, span) = domainMinAndSpan(ids)
            factors += AllDifferentFactor(ids, dMin, span.toInt(), presents = presentLits)
            return
        }
    }
    // Pairwise fallback: `(present_i ∧ present_j) → (x_i ≠ x_j)`.
    for (i in lifted.indices) {
        for (j in i + 1 until lifted.size) {
            val ne = IntCompare(lifted[i], IntCmpOp.NE, lifted[j])
            val guarded = Implies(
                And(listOf(boolFromLit(presentLits[i]), boolFromLit(presentLits[j]))),
                ne,
            )
            assertExpr(guarded)
        }
    }
}

/** Reconstruct a [BoolExpr] from a solver literal — used to thread already-lowered
 *  presence literals back through the AST-level guards in the pairwise fallback path. */
private fun Lowering.boolFromLit(lit: Int): BoolExpr {
    val v = Lit.variable(lit)
    val name = idToBoolName[v]
        ?: error("opt: unknown bool var id $v in presence lowering")
    return BoolRef(name, negated = !Lit.isPositive(lit))
}

internal fun Lowering.assertCumulativeOpt(expr: CumulativeExprOpt) {
    val ids = liftToIntRefIds(expr.starts, "cumulativeOpt")
    factors += CumulativeFactor(
        starts = ids,
        durations = LongArray(expr.durations.size) { expr.durations[it].toLong() },
        resources = LongArray(expr.resources.size) { expr.resources[it].toLong() },
        capacity = expr.capacity.toLong(),
        presents = lowerPresences(expr.presents),
    )
}

internal fun Lowering.assertDisjunctiveOpt(expr: DisjunctiveExprOpt) {
    val ids = liftToIntRefIds(expr.starts, "disjunctiveOpt")
    factors += CumulativeFactor.unary(
        starts = ids,
        durations = LongArray(expr.durations.size) { expr.durations[it].toLong() },
        presents = lowerPresences(expr.presents),
    )
}

internal fun Lowering.assertNValueOpt(expr: NValueExprOpt) {
    val xsIds = liftToIntRefIds(expr.xs, "nvalueOpt", term = "xs")
    val nLifted = lift(expr.n)
    require(nLifted is IntRef) { "nvalueOpt: n must be a bare variable reference." }
    val nId = intVarOf(nLifted.name)
    val factorMode = when (expr.mode) {
        NValueMode.EQ -> NValueFactor.Mode.Eq
        NValueMode.AT_LEAST -> NValueFactor.Mode.AtLeast
        NValueMode.AT_MOST -> NValueFactor.Mode.AtMost
    }
    factors += NValueFactor(
        n = nId,
        xs = xsIds,
        mode = factorMode,
        presents = lowerPresences(expr.presents),
    )
}

internal fun Lowering.assertGccOpt(expr: GccExprOpt) {
    val xsIds = liftToIntRefIds(expr.xs, "gccOpt", term = "xs")
    factors += GccFactor(
        xs = xsIds,
        cover = expr.cover.map { it.toLong() }.toLongArray(),
        countLow = expr.low.toIntArray(),
        countHigh = expr.high.toIntArray(),
        closed = expr.closed,
        presents = lowerPresences(expr.presents),
    )
}

internal fun Lowering.assertDisjunctive(expr: DisjunctiveExpr) {
    val ids = liftToIntRefIds(expr.starts, "disjunctive")
    factors += CumulativeFactor.unary(
        starts = ids,
        durations = LongArray(expr.durations.size) { expr.durations[it].toLong() },
    )
}

internal fun Lowering.assertSort(expr: SortExpr) {
    val liftedXs = expr.xs.map { lift(it) }
    val liftedYs = expr.ys.map { lift(it) }
    require(liftedXs.all { it is IntRef } && liftedYs.all { it is IntRef }) {
        "sort: every term must be a bare variable reference (no arithmetic)."
    }
    val xsIds = IntArray(liftedXs.size) { intVarOf((liftedXs[it] as IntRef).name) }
    val ysIds = IntArray(liftedYs.size) { intVarOf((liftedYs[it] as IntRef).name) }
    factors += SortFactor(xs = xsIds, ys = ysIds)
}

internal fun Lowering.assertIncreasing(expr: IncreasingExpr) {
    val lifted = expr.xs.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "increasing: every term must be a bare variable reference (no arithmetic)."
    }
    // A chain of 0 or 1 variables is trivially ordered — post nothing.
    if (lifted.size < 2) return
    val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
    factors += IncreasingFactor(xs = ids, strict = expr.strict)
}

internal fun Lowering.assertDiffn(expr: DiffnExpr) {
    val liftedX = expr.xs.map { lift(it) }
    val liftedY = expr.ys.map { lift(it) }
    require(liftedX.all { it is IntRef } && liftedY.all { it is IntRef }) {
        "diffn: every coordinate term must be a bare variable reference (no arithmetic)."
    }
    val xIds = IntArray(liftedX.size) { intVarOf((liftedX[it] as IntRef).name) }
    val yIds = IntArray(liftedY.size) { intVarOf((liftedY[it] as IntRef).name) }
    factors += DiffnFactor(
        xs = xIds,
        ys = yIds,
        widths = LongArray(expr.widths.size) { expr.widths[it].toLong() },
        heights = LongArray(expr.heights.size) { expr.heights[it].toLong() },
    )
}

internal fun Lowering.assertRegular(expr: RegularExpr) {
    val lifted = expr.seq.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "regular: every sequence term must be a bare variable reference (no arithmetic)."
    }
    val seqIds = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
    factors += RegularFactor(
        seq = seqIds,
        numStates = expr.numStates,
        alphabetSize = expr.alphabetSize,
        transitions = expr.transitions.map { it.toLong() }.toLongArray(),
        q0 = expr.q0,
        accepting = expr.accepting.toIntArray(),
    )
}

internal fun Lowering.assertIntCompare(expr: IntCompare) {
    val (op, normBound) = normalize(expr.op, 0)
    val combined = subtract(affine(lift(expr.left)), affine(lift(expr.right)))
    val coeffs = combined.coeffs
    val bound = normBound - combined.constant
    emitTopLevelCmp(coeffs, op, bound)
}

internal fun Lowering.emitTopLevelCmp(coeffs: Map<String, Int>, op: IntCmpOp, bound: Int) {
    if (coeffs.isEmpty()) {
        // 0 op bound: trivially true or false at compile time.
        val holds = when (op) {
            IntCmpOp.LE -> 0 <= bound
            IntCmpOp.GE -> 0 >= bound
            IntCmpOp.EQ -> 0 == bound
            IntCmpOp.NE -> 0 != bound
            IntCmpOp.LT, IntCmpOp.GT -> error("LT/GT should have been normalized away")
        }
        if (!holds) {
            error(
                "Constraint reduces to a constant-false comparison ($op against $bound) " +
                    "and is unsatisfiable as written.",
            )
        }
        return
    }
    if (coeffs.size == 1) {
        val (name, c) = coeffs.entries.first()
        emitSingleVar(name, c, op, bound)
        return
    }
    val (varIds, coeffArr) = coeffsToArrays(coeffs)
    when (op) {
        IntCmpOp.LE -> factors += Linear(coeffArr, varIds, LinearOp.LE, bound)

        IntCmpOp.GE -> factors += Linear(coeffArr, varIds, LinearOp.GE, bound)

        IntCmpOp.EQ -> factors += Linear(coeffArr, varIds, LinearOp.EQ, bound)

        IntCmpOp.NE -> {
            // Reify equality and negate: aux ↔ Σ = bound; assert ¬aux.
            val aux = newBoolVar()
            factors += ReifiedLinear(aux, coeffArr, varIds, LinearOp.EQ, bound)
            factors += Clause(intArrayOf(Lit.make(aux, positive = false)))
        }

        IntCmpOp.LT, IntCmpOp.GT -> error("LT/GT should have been normalized away")
    }
}

internal fun Lowering.emitSingleVar(name: String, coeff: Int, op: IntCmpOp, bound: Int) {
    // Σ c x ⟨op⟩ b reduces to x ⟨op'⟩ b/c (assuming exact division). Avoid the division
    // by lowering through the Linear factor when c isn't ±1.
    if (coeff == 1) {
        emitSingleVarCanonical(name, op, bound)
        return
    }
    if (coeff == -1) {
        // -x op b ⟺ x op' -b with op flipped (LE↔GE etc).
        val flipped = when (op) {
            IntCmpOp.LE -> IntCmpOp.GE
            IntCmpOp.GE -> IntCmpOp.LE
            IntCmpOp.EQ -> IntCmpOp.EQ
            IntCmpOp.NE -> IntCmpOp.NE
            IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
        }
        emitSingleVarCanonical(name, flipped, -bound)
        return
    }
    // All coeffs: emit as a single-term Linear.
    val varId = intVarOf(name)
    factors += Linear(intArrayOf(coeff), intArrayOf(varId), op.toLinearOp(), bound)
}

internal fun Lowering.emitSingleVarCanonical(name: String, op: IntCmpOp, bound: Int) {
    val v = intVarOf(name)
    factors += Linear(intArrayOf(1), intArrayOf(v), op.toLinearOp(), bound)
}

internal fun IntCmpOp.toLinearOp(): LinearOp = when (this) {
    IntCmpOp.LE -> LinearOp.LE
    IntCmpOp.GE -> LinearOp.GE
    IntCmpOp.EQ -> LinearOp.EQ
    IntCmpOp.NE -> LinearOp.NE
    IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
}
