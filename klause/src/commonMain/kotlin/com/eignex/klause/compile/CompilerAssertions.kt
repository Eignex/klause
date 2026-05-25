package com.eignex.klause.compile

import com.eignex.klause.ast.AllDifferent
import com.eignex.klause.ast.And
import com.eignex.klause.ast.AtLeast
import com.eignex.klause.ast.AtMost
import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.CardinalityExpr
import com.eignex.klause.ast.CircuitExpr
import com.eignex.klause.ast.CumulativeExpr
import com.eignex.klause.ast.DisjunctiveExpr
import com.eignex.klause.ast.Iff
import com.eignex.klause.ast.Implies
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.Not
import com.eignex.klause.ast.Or
import com.eignex.klause.ast.PbOp
import com.eignex.klause.ast.PseudoBooleanExpr
import com.eignex.klause.ast.SubcircuitExpr
import com.eignex.klause.ast.TableConstraint
import com.eignex.klause.ast.XorExpr
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Xor
import com.eignex.klause.ast.AllDifferentOpt
import com.eignex.klause.ast.CountExprOpt
import com.eignex.klause.ast.CountOp
import com.eignex.klause.ast.CumulativeExprOpt
import com.eignex.klause.ast.DisjunctiveExprOpt
import com.eignex.klause.ast.GccExprOpt
import com.eignex.klause.ast.NValueExprOpt
import com.eignex.klause.ast.NValueMode
import com.eignex.klause.solver.factor.Count as CountFactor
import com.eignex.klause.solver.factor.GlobalCardinality as GccFactor
import com.eignex.klause.solver.factor.NValue as NValueFactor
import com.eignex.klause.solver.factor.AllDifferent as AllDifferentFactor
import com.eignex.klause.solver.factor.Circuit as CircuitFactor
import com.eignex.klause.solver.factor.Cumulative as CumulativeFactor
import com.eignex.klause.solver.factor.Disjunctive as DisjunctiveFactor
import com.eignex.klause.solver.factor.Subcircuit as SubcircuitFactor

/**
 * Top-level constraint assertion handlers for [Compiler.Build]. The DSL drops a tree of
 * [BoolExpr] into [assertExpr]; this file owns the dispatch into per-shape emitters
 * ([assertAllDifferent], [assertCircuit], [assertCumulative], ...) and the integer-
 * comparison normalisation ([assertIntCompare] / [emitTopLevelCmp] / [emitSingleVar]).
 * Sub-expression-level lowering (`lowerToLit`, `reify*`, `tseitin*`) lives in
 * [CompilerLowering]; affine-fragment lift lives in [CompilerLift].
 */
internal fun Compiler.Build.assertExpr(expr: BoolExpr) {
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
        is com.eignex.klause.ast.FloatLinearConstraint -> assertFloatLinear(expr)
        is AllDifferent -> assertAllDifferent(expr.terms)
        is CircuitExpr -> assertCircuit(expr.succ, expr.valueOffset, sub = false)
        is SubcircuitExpr -> assertCircuit(expr.succ, expr.valueOffset, sub = true)
        is CumulativeExpr -> assertCumulative(expr)
        is DisjunctiveExpr -> assertDisjunctive(expr)
        is AllDifferentOpt -> assertAllDifferentOpt(expr)
        is CumulativeExprOpt -> assertCumulativeOpt(expr)
        is DisjunctiveExprOpt -> assertDisjunctiveOpt(expr)
        is CountExprOpt -> assertCountOpt(expr)
        is NValueExprOpt -> assertNValueOpt(expr)
        is GccExprOpt -> assertGccOpt(expr)
        is TableConstraint -> assertExpr(expandTable(expr))
        is PseudoBooleanExpr -> {
            val lits = lowerAllBool(expr.lits)
            factors += PseudoBoolean(
                weights = expr.weights.toIntArray(),
                literals = lits,
                op = expr.op,
                bound = expr.bound,
)
        }
        is XorExpr -> {
            val lits = lowerAllBool(expr.children)
            factors += Xor(lits, targetParity = 1)
        }
    }
}

internal fun Compiler.Build.expandTable(t: TableConstraint): BoolExpr {
    val lifted = t.terms.map { lift(it) }
    val tuples = t.tuples.map { tup ->
        And(lifted.indices.map { i ->
            IntCompare(lifted[i], IntCmpOp.EQ, IntLit(tup[i]))
        })
    }
    return if (t.negative) {
        And(tuples.map { Not(it) })
    } else {
        if (tuples.size == 1) tuples[0] else Or(tuples)
    }
}

/**
 * Lower a [com.eignex.klause.ast.FloatLinearConstraint] in two parallel ways:
 *
 *  1. Bucket each referenced float variable using its declared [FloatSpec.buckets]
 *     and emit a scaled-integer [Linear] factor — this is what every existing
 *     backend solves over.
 *  2. Append a [com.eignex.klause.solver.RealLinearConstraint] (over float-var
 *     ids) to the metadata buffer so a native-real backend (Z3) can solve it
 *     directly in real arithmetic.
 *
 * Scaling math (per float var `v` with interval `[lo, hi]` and `N` buckets, step
 * `step = (hi - lo) / (N - 1)`): substitute `v = lo + b · step` and rearrange to
 * `Σ (c_v · step_v) · b_v ⟨op⟩ bound − Σ c_v · lo_v`, then multiply by `SCALE` and
 * round to integer coefficients. Discretisation error is ~1/SCALE per term.
 */
internal fun Compiler.Build.assertFloatLinear(c: com.eignex.klause.ast.FloatLinearConstraint) {
    val n = c.varNames.size
    val realIds = IntArray(n) { i ->
        floatVarIdByName[c.varNames[i]]
            ?: error("Float variable '${c.varNames[i]}' not declared")
    }
    val realOp = when (c.op) {
        com.eignex.klause.ast.IntCmpOp.LE,
        com.eignex.klause.ast.IntCmpOp.LT -> com.eignex.klause.solver.factor.LinearOp.LE
        com.eignex.klause.ast.IntCmpOp.GE,
        com.eignex.klause.ast.IntCmpOp.GT -> com.eignex.klause.solver.factor.LinearOp.GE
        com.eignex.klause.ast.IntCmpOp.EQ -> com.eignex.klause.solver.factor.LinearOp.EQ
        com.eignex.klause.ast.IntCmpOp.NE -> com.eignex.klause.solver.factor.LinearOp.NE
    }
    floatMetaConstraints += com.eignex.klause.solver.RealLinearConstraint(
        coeffs = c.coeffs.copyOf(),
        floatVarIds = realIds,
        op = realOp,
        bound = c.bound,
    )

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
    val scaledBoundInt = (scaledBound * scale).toLong().toInt()
    factors += com.eignex.klause.solver.factor.Linear(scaledCoeffs, intVarIds, realOp, scaledBoundInt)
}

internal fun Compiler.Build.assertAllDifferent(terms: List<IntExpr>) {
    val lifted = terms.map { lift(it) }
    // Specialisation: when every operand is a bare IntRef (no arithmetic residual), emit
    // the global factor. Otherwise fall back to pairwise NE through the existing
    // reification path.
    if (lifted.all { it is IntRef }) {
        val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        if (ids.toSet().size == ids.size) {
            var dMin = intDomains[ids[0]].min
            var dMax = intDomains[ids[0]].max
            for (id in ids) {
                val d = intDomains[id]
                if (d.min < dMin) dMin = d.min
                if (d.max > dMax) dMax = d.max
            }
            factors += AllDifferentFactor(ids, dMin, dMax - dMin + 1)
            return
        }
    }
    for (i in lifted.indices) for (j in i + 1 until lifted.size) {
        assertExpr(IntCompare(lifted[i], IntCmpOp.NE, lifted[j]))
    }
}

/**
 * Lower [CircuitExpr] / [SubcircuitExpr] to its native factor. Each `succ` term must
 * lift to a bare [IntRef]. When [valueOffset] is nonzero (e.g. 1 for FlatZinc-style
 * 1-indexed inputs), aux 0-indexed int vars are allocated and channeled to the original
 * vars via a Linear factor — the factor itself stays 0-indexed.
 */
internal fun Compiler.Build.assertCircuit(succ: List<IntExpr>, valueOffset: Int, sub: Boolean) {
    val n = succ.size
    val lifted = succ.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "${if (sub) "subcircuit" else "circuit"}: every successor term must be a bare " +
            "variable reference (no arithmetic). Got ${lifted.map { it::class.simpleName }}."
    }
    val srcIds = IntArray(n) { intVarOf((lifted[it] as IntRef).name) }
    val ids = if (valueOffset == 0) srcIds else IntArray(n) { i ->
        // Channel: aux = src - valueOffset, with aux ∈ [0, n − 1].
        val auxId = newIntVar(IntDomain(0, n - 1))
        factors += Linear(
            coeffs = intArrayOf(1, -1),
            vars = intArrayOf(srcIds[i], auxId),
            op = LinearOp.EQ,
            bound = valueOffset,
        )
        auxId
    }
    factors += if (sub) SubcircuitFactor(succ = ids) else CircuitFactor(succ = ids)
}

internal fun Compiler.Build.assertCumulative(expr: CumulativeExpr) {
    val lifted = expr.starts.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "cumulative: every start term must be a bare variable reference (no arithmetic)."
    }
    val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
    factors += CumulativeFactor(
        starts = ids,
        durations = expr.durations.toIntArray(),
        resources = expr.resources.toIntArray(),
        capacity = expr.capacity,
    )
}

/**
 * Lower each presence [BoolExpr] in [presents] to a solver literal. Used by every opt-aware
 * global to thread presence into its factor's `presents: IntArray`.
 */
private fun Compiler.Build.lowerPresences(presents: List<BoolExpr>): IntArray {
    val out = IntArray(presents.size)
    for (i in presents.indices) out[i] = lowerToLit(presents[i])
    return out
}

/** AllDifferent over an opt-presence-gated subset. Bare-IntRef operands map directly to the
 *  global factor; non-bare operands fall back to presence-guarded pairwise NE. */
internal fun Compiler.Build.assertAllDifferentOpt(expr: AllDifferentOpt) {
    val lifted = expr.terms.map { lift(it) }
    val presentLits = lowerPresences(expr.presents)
    if (lifted.all { it is IntRef }) {
        val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
        if (ids.toSet().size == ids.size) {
            var dMin = intDomains[ids[0]].min
            var dMax = intDomains[ids[0]].max
            for (id in ids) {
                val d = intDomains[id]
                if (d.min < dMin) dMin = d.min
                if (d.max > dMax) dMax = d.max
            }
            factors += AllDifferentFactor(ids, dMin, dMax - dMin + 1, presents = presentLits)
            return
        }
    }
    // Pairwise fallback: `(present_i ∧ present_j) → (x_i ≠ x_j)`.
    for (i in lifted.indices) for (j in i + 1 until lifted.size) {
        val ne = IntCompare(lifted[i], IntCmpOp.NE, lifted[j])
        val guarded = Implies(
            And(listOf(boolFromLit(presentLits[i]), boolFromLit(presentLits[j]))),
            ne,
        )
        assertExpr(guarded)
    }
}

/** Reconstruct a [BoolExpr] from a solver literal — used to thread already-lowered
 *  presence literals back through the AST-level guards in the pairwise fallback path. */
private fun Compiler.Build.boolFromLit(lit: Int): BoolExpr {
    val v = Lit.variable(lit)
    val name = boolVarIdByName.entries.firstOrNull { it.value == v }?.key
        ?: error("opt: unknown bool var id $v in presence lowering")
    return BoolRef(name, negated = !Lit.isPositive(lit))
}

internal fun Compiler.Build.assertCumulativeOpt(expr: CumulativeExprOpt) {
    val lifted = expr.starts.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "cumulativeOpt: every start term must be a bare variable reference (no arithmetic)."
    }
    val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
    factors += CumulativeFactor(
        starts = ids,
        durations = expr.durations.toIntArray(),
        resources = expr.resources.toIntArray(),
        capacity = expr.capacity,
        presents = lowerPresences(expr.presents),
    )
}

internal fun Compiler.Build.assertDisjunctiveOpt(expr: DisjunctiveExprOpt) {
    val lifted = expr.starts.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "disjunctiveOpt: every start term must be a bare variable reference (no arithmetic)."
    }
    val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
    factors += DisjunctiveFactor(
        starts = ids,
        durations = expr.durations.toIntArray(),
        presents = lowerPresences(expr.presents),
    )
}

internal fun Compiler.Build.assertCountOpt(expr: CountExprOpt) {
    val xsLifted = expr.xs.map { lift(it) }
    require(xsLifted.all { it is IntRef }) {
        "countOpt: every xs term must be a bare variable reference (no arithmetic)."
    }
    val xsIds = IntArray(xsLifted.size) { intVarOf((xsLifted[it] as IntRef).name) }
    val nLifted = lift(expr.n)
    require(nLifted is IntRef) { "countOpt: count target n must be a bare variable reference." }
    val nId = intVarOf(nLifted.name)
    val factorOp = when (expr.op) {
        CountOp.EQ -> CountFactor.Op.Eq
        CountOp.NE -> CountFactor.Op.Ne
        CountOp.LE -> CountFactor.Op.Le
        CountOp.LT -> CountFactor.Op.Lt
        CountOp.GE -> CountFactor.Op.Ge
        CountOp.GT -> CountFactor.Op.Gt
    }
    factors += CountFactor(
        xs = xsIds,
        v = expr.v,
        op = factorOp,
        n = nId,
        presents = lowerPresences(expr.presents),
    )
}

internal fun Compiler.Build.assertNValueOpt(expr: NValueExprOpt) {
    val xsLifted = expr.xs.map { lift(it) }
    require(xsLifted.all { it is IntRef }) {
        "nvalueOpt: every xs term must be a bare variable reference (no arithmetic)."
    }
    val xsIds = IntArray(xsLifted.size) { intVarOf((xsLifted[it] as IntRef).name) }
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

internal fun Compiler.Build.assertGccOpt(expr: GccExprOpt) {
    val xsLifted = expr.xs.map { lift(it) }
    require(xsLifted.all { it is IntRef }) {
        "gccOpt: every xs term must be a bare variable reference (no arithmetic)."
    }
    val xsIds = IntArray(xsLifted.size) { intVarOf((xsLifted[it] as IntRef).name) }
    factors += GccFactor(
        xs = xsIds,
        cover = expr.cover.toIntArray(),
        countLow = expr.low.toIntArray(),
        countHigh = expr.high.toIntArray(),
        closed = expr.closed,
        presents = lowerPresences(expr.presents),
    )
}

internal fun Compiler.Build.assertDisjunctive(expr: DisjunctiveExpr) {
    val lifted = expr.starts.map { lift(it) }
    require(lifted.all { it is IntRef }) {
        "disjunctive: every start term must be a bare variable reference (no arithmetic)."
    }
    val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
    factors += DisjunctiveFactor(
        starts = ids,
        durations = expr.durations.toIntArray(),
    )
}

internal fun Compiler.Build.assertIntCompare(expr: IntCompare) {
    val (op, normBound) = normalize(expr.op, 0)
    val combined = subtract(affine(lift(expr.left)), affine(lift(expr.right)))
    val coeffs = combined.coeffs
    val bound = normBound - combined.constant
    emitTopLevelCmp(coeffs, op, bound)
}

internal fun Compiler.Build.emitTopLevelCmp(
    coeffs: Map<String, Int>,
    op: IntCmpOp,
    bound: Int,
) {
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
            throw IllegalStateException(
                "Constraint reduces to a constant-false comparison ($op against $bound) " +
                    "and is unsatisfiable as written."
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

internal fun Compiler.Build.emitSingleVar(
    name: String,
    coeff: Int,
    op: IntCmpOp,
    bound: Int,
) {
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

internal fun Compiler.Build.emitSingleVarCanonical(
    name: String, op: IntCmpOp, bound: Int,
) {
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

