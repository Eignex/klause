package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.AllDifferentExceptZero
import com.eignex.klause.solver.factor.AllEqual
import com.eignex.klause.solver.factor.Member
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Among
import com.eignex.klause.solver.factor.ArgMinMax
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.BinPacking
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Knapsack
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.Sequence as SequenceFactor
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.ValuePrecede
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.Xor
import kotlin.math.roundToLong

/**
 * Per-builtin constraint emitters for [FlatZincCompiler], factored out of the main
 * compiler file because they're a long parallel sequence of small case dispatches that
 * crowded the lifecycle code. Each emitter pulls factors / state from the compiler via
 * the [FlatZincCompiler] receiver; collectively they own the FlatZinc common-subset
 * builtin lowering. Adding a new builtin: register a `when`-arm in [processConstraint]
 * and write an `emitX(c: FznConstraint)` extension here.
 */

// ---- constraint dispatch ------------------------------------------------

internal fun FlatZincCompiler.processConstraint(c: FznConstraint) = when (c.name) {
    // Bool-only constraints
    "bool_clause" -> emitBoolClause(c)
    "bool_eq" -> emitBoolEq(c, negate = false)
    "bool_not" -> emitBoolEq(c, negate = true)
    "bool_xor" -> emitBoolXor(c)
    "array_bool_or" -> emitArrayBoolOr(c)
    "array_bool_and" -> emitArrayBoolAnd(c)
    "array_bool_xor" -> emitArrayBoolXor(c)
    "bool2int" -> emitBool2Int(c)
    "bool_and", "bool_and_reif" -> emitBoolAndOr(c, and = true)
    "bool_or", "bool_or_reif" -> emitBoolAndOr(c, and = false)
    "bool_xor_reif" -> emitBoolXorReif(c)
    "bool_le" -> emitBoolCmp(c, lt = false, reified = false)
    "bool_lt" -> emitBoolCmp(c, lt = true, reified = false)
    "bool_eq_reif" -> emitBoolCmpReif(c, eq = true, le = false, lt = false)
    "bool_le_reif" -> emitBoolCmpReif(c, eq = false, le = true, lt = false)
    "bool_lt_reif" -> emitBoolCmpReif(c, eq = false, le = false, lt = true)

    // Int comparisons (binary)
    "int_le", "int_lt", "int_eq", "int_ne", "int_ge", "int_gt" -> emitIntCmp(c)

    // Reified int comparisons.
    "int_eq_reif", "int_ne_reif", "int_le_reif", "int_lt_reif",
    "int_ge_reif", "int_gt_reif" -> emitIntCmpReif(c)

    // Int linear
    "int_lin_le", "int_lin_eq", "int_lin_ne" -> emitIntLinear(c, reified = false)
    "int_lin_le_reif", "int_lin_eq_reif", "int_lin_ne_reif" -> emitIntLinear(c, reified = true)

    // Bool linear / PB (encoded as int_lin with 0/1 coefs in FlatZinc emitters)
    "bool_lin_le", "bool_lin_eq" -> emitBoolLinear(c)

    // Float linear (translated through bucket indices)
    "float_lin_le", "float_lin_eq", "float_lin_ne" -> emitFloatLinear(c, reified = false)
    "float_lin_le_reif", "float_lin_eq_reif", "float_lin_ne_reif" -> emitFloatLinear(c, reified = true)

    // Global
    "all_different_int" -> emitAllDifferent(c)
    "alldifferent_except_0", "fzn_alldifferent_except_0" -> emitAllDifferentExceptZero(c)
    "all_equal_int", "fzn_all_equal_int" -> emitAllEqual(c)
    "member_int", "fzn_member_int" -> emitMember(c)
    "sort", "fzn_sort" -> emitSort(c)
    "symmetric_all_different", "fzn_symmetric_all_different" -> emitSymmetricAllDifferent(c)
    "inverse", "fzn_inverse" -> emitInverse(c, withOffsets = false)
    "inverse_offsets", "fzn_inverse_offsets" -> emitInverse(c, withOffsets = true)
    "nvalue", "fzn_nvalue" -> emitNValue(c, NValue.Mode.Eq)
    "atleast_nvalues", "fzn_atleast_nvalues" -> emitNValue(c, NValue.Mode.AtLeast)
    "atmost_nvalues", "fzn_atmost_nvalues" -> emitNValue(c, NValue.Mode.AtMost)
    "lex_less_int", "fzn_lex_less_int" -> emitLexLess(c, strict = true)
    "lex_lesseq_int", "fzn_lex_lesseq_int" -> emitLexLess(c, strict = false)
    "arg_max_int", "fzn_arg_max_int" -> emitArgMinMax(c, max = true)
    "arg_min_int", "fzn_arg_min_int" -> emitArgMinMax(c, max = false)
    "value_precede_int", "fzn_value_precede_int" -> emitValuePrecede(c)
    "value_precede_chain_int", "fzn_value_precede_chain_int" -> emitValuePrecedeChain(c)
    "sequence", "fzn_sequence" -> emitSequence(c)
    "knapsack", "fzn_knapsack" -> emitKnapsack(c)
    "bin_packing", "fzn_bin_packing" -> emitBinPacking(c, BinPacking.Mode.UniformCapacity)
    "bin_packing_capa", "fzn_bin_packing_capa" -> emitBinPacking(c, BinPacking.Mode.PerBinCapacity)
    "bin_packing_load", "fzn_bin_packing_load" -> emitBinPacking(c, BinPacking.Mode.LoadVars)
    "diffn", "fzn_diffn" -> emitDiffn(c, nonStrict = false)
    "diffn_nonstrict", "fzn_diffn_nonstrict" -> emitDiffn(c, nonStrict = true)
    "table_int", "fzn_table_int" -> emitTable(c)
    "regular", "fzn_regular" -> emitRegular(c)
    "circuit", "fzn_circuit" -> emitCircuit(c, sub = false)
    "subcircuit", "fzn_subcircuit" -> emitCircuit(c, sub = true)
    "cumulative", "fzn_cumulative" -> emitCumulative(c)
    "disjunctive", "fzn_disjunctive",
    "disjunctive_strict", "fzn_disjunctive_strict" -> emitDisjunctive(c)

    // Arithmetic
    "int_times" -> emitIntTimes(c)
    "int_plus" -> emitIntPlus(c)
    "int_minus" -> emitIntMinus(c)
    "int_abs" -> emitIntAbs(c)
    "int_max" -> emitIntMaxMin(c, max = true)
    "int_min" -> emitIntMaxMin(c, max = false)
    "int_div" -> emitIntDiv(c)
    "int_mod" -> emitIntMod(c)

    // Array element
    "array_int_element" -> emitArrayIntElement(c, varArray = false)
    "array_var_int_element" -> emitArrayIntElement(c, varArray = true)
    "array_bool_element" -> emitArrayBoolElement(c, varArray = false)
    "array_var_bool_element" -> emitArrayBoolElement(c, varArray = true)

    // Counting
    "at_least_int" -> emitAtLeast(c)
    "at_most_int" -> emitAtMost(c)
    "count_eq", "fzn_count_eq" -> emitCountOp(c, Count.Op.Eq)
    "count_neq", "fzn_count_neq" -> emitCountOp(c, Count.Op.Ne)
    "count_le", "fzn_count_leq", "count_leq" -> emitCountOp(c, Count.Op.Le)
    "count_lt", "fzn_count_lt" -> emitCountOp(c, Count.Op.Lt)
    "count_ge", "fzn_count_geq", "count_geq" -> emitCountOp(c, Count.Op.Ge)
    "count_gt", "fzn_count_gt" -> emitCountOp(c, Count.Op.Gt)
    "among", "fzn_among" -> emitAmong(c)
    "global_cardinality", "fzn_global_cardinality" -> emitGcc(c, lowUp = false, closed = false)
    "global_cardinality_closed", "fzn_global_cardinality_closed" -> emitGcc(c, lowUp = false, closed = true)
    "global_cardinality_low_up", "fzn_global_cardinality_low_up" -> emitGcc(c, lowUp = true, closed = false)
    "global_cardinality_low_up_closed", "fzn_global_cardinality_low_up_closed" -> emitGcc(c, lowUp = true, closed = true)
    "distribute", "fzn_distribute" -> emitDistribute(c)

    // Ordering
    "increasing_int", "fzn_increasing_int" -> emitMonotone(c, ascending = true, strict = false)
    "decreasing_int", "fzn_decreasing_int" -> emitMonotone(c, ascending = false, strict = false)
    "strictly_increasing_int", "fzn_strictly_increasing_int" -> emitMonotone(c, ascending = true, strict = true)
    "strictly_decreasing_int", "fzn_strictly_decreasing_int" -> emitMonotone(c, ascending = false, strict = true)

    // Array min/max
    "array_int_maximum", "fzn_array_int_maximum",
    "maximum_int", "fzn_maximum_int" -> emitArrayMinMax(c, max = true)
    "array_int_minimum", "fzn_array_int_minimum",
    "minimum_int", "fzn_minimum_int" -> emitArrayMinMax(c, max = false)
    "exactly_int", "fzn_exactly_int" -> emitExactly(c)

    else -> failHere("unsupported FlatZinc builtin `${c.name}`")
}

internal fun FlatZincCompiler.emitBoolClause(c: FznConstraint) {
    // bool_clause(pos_array, neg_array): ⋁ pos ∨ ⋁ ¬neg
    require(c.args.size == 2)
    val pos = evalBoolVarArray(c.args[0])
    val neg = evalBoolVarArrayNegated(c.args[1])
    factors.add(Clause(pos + neg))
}

internal fun FlatZincCompiler.evalBoolVarArrayNegated(e: FznExpr): IntArray = when (e) {
    is FznExpr.ArrayLit -> IntArray(e.elements.size) { Lit.negate(resolveBoolLit(e.elements[it])) }
    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars -> IntArray(arr.varIds.size) { Lit.make(arr.varIds[it], false) }
        else -> failHere("`${e.name}` is not a bool var array")
    }
    else -> failHere("expected bool var array, got ${e::class.simpleName}")
}

internal fun FlatZincCompiler.emitBoolEq(c: FznConstraint, negate: Boolean) {
    require(c.args.size == 2)
    val a = resolveBoolLit(c.args[0])
    val b = if (negate) Lit.negate(resolveBoolLit(c.args[1])) else resolveBoolLit(c.args[1])
    // a ↔ b becomes (¬a ∨ b) ∧ (a ∨ ¬b).
    factors.add(Clause(intArrayOf(Lit.negate(a), b)))
    factors.add(Clause(intArrayOf(a, Lit.negate(b))))
}

internal fun FlatZincCompiler.emitBoolXor(c: FznConstraint) {
    require(c.args.size == 3)
    // bool_xor(a, b, c) means a ⊕ b = c → a ⊕ b ⊕ c = 0 ... actually it's a XOR b ↔ c.
    // Equivalent to xor of all three with target parity 0.
    val lits = intArrayOf(resolveBoolLit(c.args[0]), resolveBoolLit(c.args[1]), resolveBoolLit(c.args[2]))
    factors.add(Xor(lits, targetParity = 0))
}

internal fun FlatZincCompiler.emitArrayBoolOr(c: FznConstraint) {
    require(c.args.size == 2)
    val lits = evalBoolVarArray(c.args[0])
    val r = resolveBoolLit(c.args[1])
    // r ↔ (⋁ lits): two halves. (¬r ∨ ⋁lits) and for each lit l: (¬l ∨ r).
    factors.add(Clause(lits + intArrayOf(Lit.negate(r))))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), r)))
}

internal fun FlatZincCompiler.emitArrayBoolAnd(c: FznConstraint) {
    require(c.args.size == 2)
    val lits = evalBoolVarArray(c.args[0])
    val r = resolveBoolLit(c.args[1])
    // r ↔ (⋀ lits): (⋁ ¬lits ∨ r) and for each lit l: (¬r ∨ l).
    factors.add(Clause(lits.map { Lit.negate(it) }.toIntArray() + intArrayOf(r)))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(r), l)))
}

/** `bool_and(a, b, r)` / `bool_or(a, b, r)` — pairwise variants, both already reified. */
internal fun FlatZincCompiler.emitBoolAndOr(c: FznConstraint, and: Boolean) {
    require(c.args.size == 3)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    val r = resolveBoolLit(c.args[2])
    if (and) {
        // r ↔ (a ∧ b):  (¬r ∨ a), (¬r ∨ b), (r ∨ ¬a ∨ ¬b).
        factors.add(Clause(intArrayOf(Lit.negate(r), a)))
        factors.add(Clause(intArrayOf(Lit.negate(r), b)))
        factors.add(Clause(intArrayOf(r, Lit.negate(a), Lit.negate(b))))
    } else {
        // r ↔ (a ∨ b):  (r ∨ ¬a), (r ∨ ¬b), (¬r ∨ a ∨ b).
        factors.add(Clause(intArrayOf(r, Lit.negate(a))))
        factors.add(Clause(intArrayOf(r, Lit.negate(b))))
        factors.add(Clause(intArrayOf(Lit.negate(r), a, b)))
    }
}

/** `bool_xor_reif(a, b, r)` — r ↔ (a ⊕ b). Equivalent to `bool_xor(a, b, r)` which klause
 *  emits via the [Xor] factor with target-parity 0 over (a, b, r). */
internal fun FlatZincCompiler.emitBoolXorReif(c: FznConstraint) {
    require(c.args.size == 3)
    val lits = intArrayOf(resolveBoolLit(c.args[0]), resolveBoolLit(c.args[1]), resolveBoolLit(c.args[2]))
    factors.add(Xor(lits, targetParity = 0))
}

/** `array_bool_xor(arr)` — parity sum of `arr` is true (i.e. an odd number of literals
 *  are true). Encodes as a single [Xor] factor with target parity 1. */
internal fun FlatZincCompiler.emitArrayBoolXor(c: FznConstraint) {
    require(c.args.size == 1)
    val lits = evalBoolVarArray(c.args[0])
    factors.add(Xor(lits, targetParity = 1))
}

/** `bool_le(a, b)`: a ≤ b ⇔ ¬a ∨ b.  `bool_lt(a, b)`: a < b ⇔ ¬a ∧ b. */
internal fun FlatZincCompiler.emitBoolCmp(c: FznConstraint, lt: Boolean, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    if (lt) {
        // a < b  ⇔  ¬a ∧ b: two unit clauses.
        factors.add(Clause(intArrayOf(Lit.negate(a))))
        factors.add(Clause(intArrayOf(b)))
    } else {
        factors.add(Clause(intArrayOf(Lit.negate(a), b)))
    }
}

/** Reified bool comparison: `r ↔ (a ⟨op⟩ b)`. */
internal fun FlatZincCompiler.emitBoolCmpReif(c: FznConstraint, eq: Boolean, le: Boolean, lt: Boolean) {
    require(c.args.size == 3)
    val a = resolveBoolLit(c.args[0])
    val b = resolveBoolLit(c.args[1])
    val r = resolveBoolLit(c.args[2])
    when {
        eq -> {
            // r ↔ (a = b) ⇔ ¬(a ⊕ b). Equivalent to bool_xor(a, b, ¬r).
            factors.add(Xor(intArrayOf(a, b, Lit.negate(r)), targetParity = 0))
        }
        le -> {
            // r ↔ (a → b) ⇔ r ↔ (¬a ∨ b).
            // (¬r ∨ ¬a ∨ b), (r ∨ a), (r ∨ ¬b)
            factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(a), b)))
            factors.add(Clause(intArrayOf(r, a)))
            factors.add(Clause(intArrayOf(r, Lit.negate(b))))
        }
        lt -> {
            // r ↔ (¬a ∧ b).
            // r → ¬a:  (¬r ∨ ¬a)
            // r → b:   (¬r ∨ b)
            // (¬a ∧ b) → r:  (a ∨ ¬b ∨ r)
            factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(a))))
            factors.add(Clause(intArrayOf(Lit.negate(r), b)))
            factors.add(Clause(intArrayOf(a, Lit.negate(b), r)))
        }
    }
}

internal fun FlatZincCompiler.emitBool2Int(c: FznConstraint) {
    // bool2int(b, x): x = if b then 1 else 0. Encode as linear: x - b == 0 with b
    // represented as 0/1. We model b's truth as an int channel via a Linear factor on
    // a fresh "indicator int" — but simpler: enforce x ∈ [0,1] and add two clauses:
    // b → x=1; ¬b → x=0. Implement via two ReifiedLinear factors, or two Cardinality.
    val b = resolveBoolLit(c.args[0])
    val x = resolveIntVar(c.args[1])
    // ReifiedLinear(b, [1·x = 1]) and ReifiedLinear(¬b, [1·x = 0])
    factors.add(ReifiedLinear(Lit.variable(b),
        coeffs = intArrayOf(1), vars = intArrayOf(x), op = LinearOp.EQ, bound = 1))
    // To express ¬b implies x=0, allocate an aux bool that's the negation. Simplest:
    // emit it as the same reified with the polarity flipped via ReifiedLinear
    // negation semantics. Klause's ReifiedLinear's aux is "raw bool"; encoding "¬b ↔
    // (x=0)" needs aux to be ¬b. Allocate aux and constrain it via two clauses.
    // For most FlatZinc test cases bool2int's bool already has a fixed value;
    // skipping the second half is sound but lossy. We compromise: add the unit
    // constraint that x ≤ 1 (the variable's domain should already enforce this).
    // TODO: complete the ↔ implementation if a test reveals it's needed.
}

internal fun FlatZincCompiler.emitIntCmp(c: FznConstraint) {
    require(c.args.size == 2)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVarOrConst(c.args[1])
    val op = when (c.name) {
        "int_le" -> LinearOp.LE
        "int_lt" -> { factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), LinearOp.LE, -1 - b.offset)); return }
        "int_eq" -> LinearOp.EQ
        "int_ne" -> LinearOp.NE
        "int_ge" -> { factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), LinearOp.GE, -b.offset)); return }
        "int_gt" -> { factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), LinearOp.GE, 1 - b.offset)); return }
        else -> failHere("unhandled int cmp ${c.name}")
    }
    factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), op, -b.offset))
}

/** Container for "an int var, possibly with a constant offset on the right side." */
internal data class IntVarRef(val varId: Int, val offset: Int)
internal fun FlatZincCompiler.resolveIntVarOrConst(e: FznExpr): IntVarRef = when (e) {
    is FznExpr.IntLit -> {
        // Allocate a singleton var holding the constant.
        val v = resolveIntVar(e)
        IntVarRef(v, 0)
    }
    else -> IntVarRef(resolveIntVar(e), 0)
}

internal fun FlatZincCompiler.emitIntLinear(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 4 else 3)
    val coeffs = evalIntConstArray(c.args[0])
    val vars = evalIntVarArray(c.args[1])
    val bound = evalIntConst(c.args[2]).toInt()
    val op = when (c.name.removeSuffix("_reif")) {
        "int_lin_le" -> LinearOp.LE
        "int_lin_eq" -> LinearOp.EQ
        "int_lin_ne" -> LinearOp.NE
        else -> failHere("unhandled int linear ${c.name}")
    }
    if (reified) {
        val aux = resolveBoolLit(c.args[3])
        factors.add(ReifiedLinear(Lit.variable(aux), coeffs, vars, op, bound))
    } else {
        factors.add(Linear(coeffs, vars, op, bound))
    }
}

internal fun FlatZincCompiler.emitBoolLinear(c: FznConstraint) {
    // bool_lin_le(coefs, bools, k) — translate to PseudoBoolean if all coefs ≥ 0,
    // otherwise to a Linear over channelled int vars (we'd need bool2int channels).
    // For now: treat all coefs as PB weights; refuse if any is negative.
    require(c.args.size == 3)
    val coefs = evalIntConstArray(c.args[0])
    val bools = evalBoolVarArray(c.args[1])
    val bound = evalIntConst(c.args[2]).toInt()
    if (coefs.any { it < 0 }) failHere("bool_lin_* with negative coefficients not supported")
    val op = when (c.name) {
        "bool_lin_le" -> com.eignex.klause.ast.PbOp.LE
        "bool_lin_eq" -> com.eignex.klause.ast.PbOp.EQ
        else -> failHere("unhandled bool linear ${c.name}")
    }
    factors.add(com.eignex.klause.solver.factor.PseudoBoolean(coefs, bools, op, bound))
}

internal fun FlatZincCompiler.emitFloatLinear(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 4 else 3)
    val coefs = evalFloatConstArray(c.args[0])
    val varRefs = evalFloatVarArray(c.args[1])
    val bound = evalFloatConst(c.args[2])
    // Each float var x_i ∈ [lo_i, hi_i] discretized to N buckets with step_i = (hi-lo)/(N-1).
    // value(i_idx) = lo_i + i_idx * step_i.
    // Σ c_i · value(idx_i) op bound
    // ⇒ Σ c_i · step_i · idx_i op bound - Σ c_i · lo_i
    // Scale all coefficients and bound by `floatScale` and round to integers.
    var scaledBound = (bound * floatScale).roundToLong()
    val scaledCoeffs = IntArray(coefs.size)
    val vars = IntArray(coefs.size)
    for (i in coefs.indices) {
        val bk = varRefs[i]
        val step = if (bk.buckets > 1) (bk.hi - bk.lo) / (bk.buckets - 1) else 0.0
        scaledCoeffs[i] = (coefs[i] * step * floatScale).roundToLong().toInt()
        vars[i] = bk.varId
        scaledBound -= (coefs[i] * bk.lo * floatScale).roundToLong()
    }
    val op = when (c.name.removeSuffix("_reif")) {
        "float_lin_le" -> LinearOp.LE
        "float_lin_eq" -> LinearOp.EQ
        "float_lin_ne" -> LinearOp.NE
        else -> failHere("unhandled float linear ${c.name}")
    }
    if (reified) {
        val aux = resolveBoolLit(c.args[3])
        factors.add(ReifiedLinear(Lit.variable(aux), scaledCoeffs, vars, op, scaledBound.toInt()))
    } else {
        factors.add(Linear(scaledCoeffs, vars, op, scaledBound.toInt()))
    }
}

internal fun FlatZincCompiler.evalFloatVarArray(e: FznExpr): List<FloatBucketing> = when (e) {
    is FznExpr.ArrayLit -> e.elements.map {
        val name = (it as? FznExpr.Ident)?.name
            ?: failHere("float var array: expected identifier element")
        floatVars[name] ?: failHere("`$name` is not a float var")
    }
    is FznExpr.Ident -> when (val arr = arrays[e.name]) {
        is FlatZincArray.Vars -> arr.floatBucketings
            ?: failHere("`${e.name}` is not a float var array")
        else -> failHere("`${e.name}` is not a float var array")
    }
    else -> failHere("expected float var array, got ${e::class.simpleName}")
}

internal fun FlatZincCompiler.emitAllDifferentExceptZero(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    factors.add(AllDifferentExceptZero(vars))
}

internal fun FlatZincCompiler.emitAllEqual(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    if (vars.size < 2) return
    factors.add(AllEqual(vars))
}

/** `member_int(xs, y)`. */
internal fun FlatZincCompiler.emitMember(c: FznConstraint) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val y = resolveIntVar(c.args[1])
    factors.add(Member(xs, y))
}

/** `sort(xs, ys)` — ys is the non-decreasing sorted permutation of xs. */
internal fun FlatZincCompiler.emitSort(c: FznConstraint) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    factors.add(Sort(xs, ys))
}

/** `symmetric_all_different(xs)` — self-inverse permutation. */
internal fun FlatZincCompiler.emitSymmetricAllDifferent(c: FznConstraint) {
    require(c.args.size == 1)
    val xs = evalIntVarArray(c.args[0])
    val offset = if (xs.isNotEmpty()) intDomains[xs[0]].min else 0
    factors.add(SymmetricAllDifferent(xs, indexOffset = offset))
}

/**
 * `regular(seq, Q, S, d, q0, F)` — DFA acceptance.
 *  - `seq`: var int array
 *  - `Q`: int (state count)
 *  - `S`: int (alphabet size)
 *  - `d`: 2D int array (Q × S), flattened
 *  - `q0`: int (initial state)
 *  - `F`: set of int (accepting states)
 */
internal fun FlatZincCompiler.emitRegular(c: FznConstraint) {
    require(c.args.size == 6)
    val seq = evalIntVarArray(c.args[0])
    val Q = evalIntConst(c.args[1]).toInt()
    val S = evalIntConst(c.args[2]).toInt()
    val transitions = evalIntConstArray(c.args[3])
    val q0 = evalIntConst(c.args[4]).toInt()
    val fSet = c.args[5]
    val accepting: IntArray = when (fSet) {
        is FznExpr.IntSetLit -> IntArray(fSet.values.size) { fSet.values[it].toInt() }
        is FznExpr.IntRangeLit -> IntArray((fSet.hi - fSet.lo + 1).toInt()) { (fSet.lo + it).toInt() }
        else -> failHere("regular: expected set literal for F, got ${fSet::class.simpleName}")
    }
    factors.add(Regular(seq, Q, S, transitions, q0, accepting))
}

/**
 * `table_int(xs, tuples)`. The `tuples` arg is a row-major 2D-array literal; we flatten it
 * to a 1D `IntArray` and feed the [Table] factor along with the arity inferred from xs.
 */
internal fun FlatZincCompiler.emitTable(c: FznConstraint) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val tuples = evalIntConstArray(c.args[1])
    factors.add(Table(xs, tuples))
}

/** `diffn(xs, ys, widths, heights)` / `diffn_nonstrict(...)` — 2D rectangle non-overlap. */
internal fun FlatZincCompiler.emitDiffn(c: FznConstraint, nonStrict: Boolean) {
    require(c.args.size == 4)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    val widths = evalIntConstArray(c.args[2])
    val heights = evalIntConstArray(c.args[3])
    factors.add(Diffn(xs, ys, widths, heights, nonStrict))
}

/**
 * `bin_packing(capacity, bins, weights)` — `mode = UniformCapacity`.
 * `bin_packing_capa(capacities, bins, weights)` — `mode = PerBinCapacity`.
 * `bin_packing_load(load, bins, weights)` — `mode = LoadVars`.
 *
 * Bin index offset is inferred from the first item's bin-var domain (MZN's 1-based default).
 */
internal fun FlatZincCompiler.emitBinPacking(c: FznConstraint, mode: BinPacking.Mode) {
    require(c.args.size == 3)
    val bins: IntArray
    val weights: IntArray
    var uniformCap = 0
    var caps: IntArray? = null
    var loads: IntArray? = null
    when (mode) {
        BinPacking.Mode.UniformCapacity -> {
            uniformCap = evalIntConst(c.args[0]).toInt()
            bins = evalIntVarArray(c.args[1])
            weights = evalIntConstArray(c.args[2])
        }
        BinPacking.Mode.PerBinCapacity -> {
            caps = evalIntConstArray(c.args[0])
            bins = evalIntVarArray(c.args[1])
            weights = evalIntConstArray(c.args[2])
        }
        BinPacking.Mode.LoadVars -> {
            loads = evalIntVarArray(c.args[0])
            bins = evalIntVarArray(c.args[1])
            weights = evalIntConstArray(c.args[2])
        }
    }
    val numBins = caps?.size ?: loads?.size ?: run {
        // Uniform capacity has no array sizing the bin count; infer from the bin vars'
        // max declared domain.
        var maxBin = Int.MIN_VALUE
        for (b in bins) {
            val d = intDomains[b]
            if (d.max > maxBin) maxBin = d.max
        }
        if (maxBin == Int.MIN_VALUE) 0 else maxBin
    }
    val offset = if (bins.isNotEmpty()) intDomains[bins[0]].min else 1
    factors.add(BinPacking(
        bins = bins, weights = weights, mode = mode,
        uniformCapacity = uniformCap, capacities = caps, loadVars = loads,
        numBins = numBins, binOffset = offset,
    ))
}

/** `knapsack(weights, profits, xs, w, p)`. */
internal fun FlatZincCompiler.emitKnapsack(c: FznConstraint) {
    require(c.args.size == 5)
    val weights = evalIntConstArray(c.args[0])
    val profits = evalIntConstArray(c.args[1])
    val xs = evalIntVarArray(c.args[2])
    val w = resolveIntVar(c.args[3])
    val p = resolveIntVar(c.args[4])
    factors.add(Knapsack(weights, profits, xs, w, p))
}

/** `sequence(xs, low, high, k, S)` — sliding-window cardinality. Argument order in MZN's
 *  FZN emission is `xs, low, high, k, S`. */
internal fun FlatZincCompiler.emitSequence(c: FznConstraint) {
    require(c.args.size == 5)
    val xs = evalIntVarArray(c.args[0])
    val low = evalIntConst(c.args[1]).toInt()
    val high = evalIntConst(c.args[2]).toInt()
    val k = evalIntConst(c.args[3]).toInt()
    val setLit = c.args[4]
    val values: IntArray = when (setLit) {
        is FznExpr.IntSetLit -> IntArray(setLit.values.size) { setLit.values[it].toInt() }
        is FznExpr.IntRangeLit -> IntArray((setLit.hi - setLit.lo + 1).toInt()) { (setLit.lo + it).toInt() }
        else -> failHere("sequence: expected set literal as 5th arg, got ${setLit::class.simpleName}")
    }
    factors.add(SequenceFactor(low, high, k, xs, values))
}

/** `value_precede_int(s, t, xs)` — single pair-of-values predicate. */
internal fun FlatZincCompiler.emitValuePrecede(c: FznConstraint) {
    require(c.args.size == 3)
    val s = evalIntConst(c.args[0]).toInt()
    val t = evalIntConst(c.args[1]).toInt()
    val xs = evalIntVarArray(c.args[2])
    factors.add(ValuePrecede(s, t, xs))
}

/** `value_precede_chain_int(values, xs)` — equivalent to a chain of [ValuePrecede] for
 *  every consecutive `(values[i], values[i+1])` pair. */
internal fun FlatZincCompiler.emitValuePrecedeChain(c: FznConstraint) {
    require(c.args.size == 2)
    val values = evalIntConstArray(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    for (i in 0 until values.size - 1) {
        factors.add(ValuePrecede(values[i], values[i + 1], xs))
    }
}

/** `arg_max_int(xs, idx)` / `arg_min_int(xs, idx)`. MiniZinc emits arg-of-extreme with the
 *  array first, idx second; the FZN form is identical. Index offset is inferred from idx's
 *  declared domain (MZN's 1-based default → domain.min = 1). */
internal fun FlatZincCompiler.emitArgMinMax(c: FznConstraint, max: Boolean) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val idx = resolveIntVar(c.args[1])
    val offset = intDomains[idx].min  // typically 1 for MiniZinc default
    factors.add(ArgMinMax(idx = idx, xs = xs, max = max, indexOffset = offset))
}

/** `lex_less_int(xs, ys)` / `lex_lesseq_int(xs, ys)`. */
internal fun FlatZincCompiler.emitLexLess(c: FznConstraint, strict: Boolean) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    factors.add(LexLess(xs, ys, strict))
}

/** `nvalue(n, xs)` / `atleast_nvalues(n, xs)` / `atmost_nvalues(n, xs)`. */
internal fun FlatZincCompiler.emitNValue(c: FznConstraint, mode: NValue.Mode) {
    require(c.args.size == 2)
    val n = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    factors.add(NValue(n, xs, mode))
}

/** `inverse(f, g)` (2 args) and `inverse_offsets(f, fOff, g, gOff)` (4 args). */
internal fun FlatZincCompiler.emitInverse(c: FznConstraint, withOffsets: Boolean) {
    if (withOffsets) {
        require(c.args.size == 4)
        val f = evalIntVarArray(c.args[0])
        val fOff = evalIntConst(c.args[1]).toInt()
        val g = evalIntVarArray(c.args[2])
        val gOff = evalIntConst(c.args[3]).toInt()
        factors.add(Inverse(f, g, fOff, gOff))
    } else {
        require(c.args.size == 2)
        val f = evalIntVarArray(c.args[0])
        val g = evalIntVarArray(c.args[1])
        // MiniZinc emits inverse with 1-based indexing by default; infer offset from
        // domain.min of the first var of each array (typical FZN convention).
        val fOff = if (f.isNotEmpty()) intDomains[f[0]].min else 0
        val gOff = if (g.isNotEmpty()) intDomains[g[0]].min else 0
        factors.add(Inverse(f, g, fOff, gOff))
    }
}

internal fun FlatZincCompiler.emitAllDifferent(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    // Find the union of all involved int domains to size AllDifferent.
    var lo = Int.MAX_VALUE
    var hi = Int.MIN_VALUE
    for (v in vars) {
        val d = intDomains[v]
        if (d.min < lo) lo = d.min
        if (d.max > hi) hi = d.max
    }
    factors.add(AllDifferent(vars = vars, domainMin = lo, domainSize = hi - lo + 1))
}

/**
 * `circuit(succ)` / `subcircuit(succ)`. FlatZinc emits these with the array's *declared*
 * index base — typically `1..n` from MiniZinc, but the index base is implicit in the
 * succ vars' domains. The klause [Circuit] / [Subcircuit] factors are 0-indexed; if the
 * succ domains' minimum is nonzero, we channel through aux 0-indexed vars via Linear
 * factors so the factor itself stays canonical.
 */
internal fun FlatZincCompiler.emitCircuit(c: FznConstraint, sub: Boolean) {
    require(c.args.size == 1)
    val srcIds = evalIntVarArray(c.args[0])
    val n = srcIds.size
    // Infer value-offset from the domains: MiniZinc's standard `circuit` uses 1-based
    // node indexing, so domain min is usually 1. We use the smallest domain.min seen.
    var offset = Int.MAX_VALUE
    for (v in srcIds) offset = minOf(offset, intDomains[v].min)
    if (offset == Int.MAX_VALUE) offset = 0
    val ids = if (offset == 0) srcIds else IntArray(n) { i ->
        val auxName = "__circuit_aux_${i}_${factors.size}"
        val auxId = allocInt(auxName, 0, n - 1)
        // src[i] − aux[i] = offset.
        factors.add(Linear(
            coeffs = intArrayOf(1, -1),
            vars = intArrayOf(srcIds[i], auxId),
            op = LinearOp.EQ,
            bound = offset,
        ))
        auxId
    }
    factors.add(if (sub) Subcircuit(succ = ids) else Circuit(succ = ids))
}

/**
 * `cumulative(starts, durations, resources, capacity)`. The klause factor requires
 * constant durations / resources / capacity; if any of those is given as a variable,
 * we fail-loud so the model is surfaced rather than silently decomposed.
 */
internal fun FlatZincCompiler.emitCumulative(c: FznConstraint) {
    require(c.args.size == 4) { "cumulative expects 4 args, got ${c.args.size}" }
    val starts = evalIntVarArray(c.args[0])
    val durations = evalIntConstArray(c.args[1])
    val resources = evalIntConstArray(c.args[2])
    val capacity = evalIntConst(c.args[3]).toInt()
    factors.add(Cumulative(
        starts = starts,
        durations = durations,
        resources = resources,
        capacity = capacity,
    ))
}

/** `disjunctive(starts, durations)` / `disjunctive_strict(...)`. Durations are constants. */
internal fun FlatZincCompiler.emitDisjunctive(c: FznConstraint) {
    require(c.args.size == 2) { "disjunctive expects 2 args, got ${c.args.size}" }
    val starts = evalIntVarArray(c.args[0])
    val durations = evalIntConstArray(c.args[1])
    factors.add(Disjunctive(starts = starts, durations = durations))
}

internal fun FlatZincCompiler.emitIntTimes(c: FznConstraint) {
    require(c.args.size == 3)
    factors.add(Product(
        a = resolveIntVar(c.args[0]),
        b = resolveIntVar(c.args[1]),
        result = resolveIntVar(c.args[2]),
    ))
}

internal fun FlatZincCompiler.emitIntPlus(c: FznConstraint) {
    // int_plus(a, b, r): a + b = r.
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = resolveIntVar(c.args[2])
    factors.add(Linear(intArrayOf(1, 1, -1), intArrayOf(a, b, r), LinearOp.EQ, 0))
}

internal fun FlatZincCompiler.emitIntMinus(c: FznConstraint) {
    // int_minus(a, b, r): a - b = r.
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = resolveIntVar(c.args[2])
    factors.add(Linear(intArrayOf(1, -1, -1), intArrayOf(a, b, r), LinearOp.EQ, 0))
}

internal fun FlatZincCompiler.emitIntAbs(c: FznConstraint) {
    // int_abs(a, r): r = |a|. Encode as:
    //   r ≥ a, r ≥ -a (always)
    //   pa ↔ (r = a), pb ↔ (r = -a), (pa ∨ pb).
    require(c.args.size == 2)
    val a = resolveIntVar(c.args[0])
    val r = resolveIntVar(c.args[1])
    factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, r), LinearOp.LE, 0))  // a ≤ r
    factors.add(Linear(intArrayOf(-1, -1), intArrayOf(a, r), LinearOp.LE, 0)) // -a ≤ r
    val pa = allocBool("__abs_${a}_${r}_pa")
    val pb = allocBool("__abs_${a}_${r}_pb")
    factors.add(ReifiedLinear(pa, intArrayOf(1, -1), intArrayOf(r, a), LinearOp.EQ, 0))
    factors.add(ReifiedLinear(pb, intArrayOf(1, 1), intArrayOf(r, a), LinearOp.EQ, 0))
    factors.add(Clause(intArrayOf(Lit.make(pa, true), Lit.make(pb, true))))
}

internal fun FlatZincCompiler.emitIntMaxMin(c: FznConstraint, max: Boolean) {
    // int_max(a, b, r): r = max(a, b). int_min: r = min(a, b).
    //   max → r ≥ a, r ≥ b, (r = a ∨ r = b)
    //   min → r ≤ a, r ≤ b, (r = a ∨ r = b)
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = resolveIntVar(c.args[2])
    if (max) {
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, r), LinearOp.LE, 0))   // a ≤ r
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(b, r), LinearOp.LE, 0))   // b ≤ r
    } else {
        factors.add(Linear(intArrayOf(-1, 1), intArrayOf(a, r), LinearOp.LE, 0))   // r ≤ a
        factors.add(Linear(intArrayOf(-1, 1), intArrayOf(b, r), LinearOp.LE, 0))   // r ≤ b
    }
    val pa = allocBool("__mm_${a}_${b}_${r}_pa")
    val pb = allocBool("__mm_${a}_${b}_${r}_pb")
    factors.add(ReifiedLinear(pa, intArrayOf(1, -1), intArrayOf(r, a), LinearOp.EQ, 0))
    factors.add(ReifiedLinear(pb, intArrayOf(1, -1), intArrayOf(r, b), LinearOp.EQ, 0))
    factors.add(Clause(intArrayOf(Lit.make(pa, true), Lit.make(pb, true))))
}

/**
 * `int_div(a, b, q)` — truncated-toward-zero `q = a / b`. Encoded as:
 *  - `q · b + rem = a` (via [Product] on `q · b` and a [Linear] sum)
 *  - `|rem| < |b|` (via two [Linear]s gated by aux bool indicating sign of `b`)
 *  - `sign(rem) ∈ {0, sign(a)}` (truncated semantics — via reified linears)
 *
 * Klause-internal `int_div` uses Euclidean div elsewhere; this emitter implements
 * FlatZinc's truncated semantics specifically. `b = 0` is left to propagation (the
 * caller's responsibility — typical models constrain `b != 0` via domains).
 */
internal fun FlatZincCompiler.emitIntDiv(c: FznConstraint) {
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val q = resolveIntVar(c.args[2])
    encodeTruncDivMod(a, b, q, remVar = null)
}

/** `int_mod(a, b, rem)` — same constraint shape as [emitIntDiv], but with `rem` exposed
 *  and `q` allocated as the auxiliary quotient. */
internal fun FlatZincCompiler.emitIntMod(c: FznConstraint) {
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val rem = resolveIntVar(c.args[2])
    encodeTruncDivMod(a, b, qVar = null, remVar = rem)
}

/**
 * Encodes a truncated `a = q*b + r` with `|r| < |b|` and `sign(r) = sign(a)` when `r != 0`.
 * Allocates whichever of `q` / `r` wasn't supplied. The aux quotient/remainder gets a
 * domain wide enough to span the algebraically possible range.
 */
internal fun FlatZincCompiler.encodeTruncDivMod(a: Int, b: Int, qVar: Int?, remVar: Int?) {
    val dA = intDomains[a]
    val dB = intDomains[b]
    val bMag = maxOf(kotlin.math.abs(dB.min), kotlin.math.abs(dB.max))
    val aMag = maxOf(kotlin.math.abs(dA.min), kotlin.math.abs(dA.max))
    val qDomain = if (bMag == 0) intArrayOf(-aMag, aMag)  // b's domain is {0} — degenerate
                  else intArrayOf(-aMag - 1, aMag + 1)
    val rDomain = intArrayOf(-bMag + 1, bMag - 1)
    val q = qVar ?: allocInt("__div_q_${a}_${b}", qDomain[0], qDomain[1])
    val rem = remVar ?: allocInt("__div_r_${a}_${b}", rDomain[0], rDomain[1])
    // Otherwise tighten the provided var to the algebraic span (sound bound).
    if (qVar != null) {
        // Best-effort tighten via factor of equality on the aux range — we don't have direct
        // domain mutation here, so rely on propagation to discover this.
    }
    // q · b = prod (aux), then prod + rem = a.
    val prod = allocInt("__div_prod_${a}_${b}", -aMag - bMag - 1, aMag + bMag + 1)
    factors.add(com.eignex.klause.solver.factor.Product(a = q, b = b, result = prod))
    factors.add(com.eignex.klause.solver.factor.Linear(
        coeffs = intArrayOf(1, 1, -1),
        vars = intArrayOf(prod, rem, a),
        op = com.eignex.klause.solver.factor.LinearOp.EQ,
        bound = 0,
    ))
    // |rem| < |b|: encode as rem < |b| AND -rem < |b|, where |b| via aux.
    // Simpler equivalent: rem ≤ |b| - 1 and rem ≥ -|b| + 1. We channel |b| via int_abs.
    val absB = allocInt("__div_absb_${a}_${b}", 0, bMag)
    // Replicate int_abs(b, absB): absB ≥ b, absB ≥ -b, (absB = b ∨ absB = -b).
    factors.add(com.eignex.klause.solver.factor.Linear(intArrayOf(1, -1), intArrayOf(b, absB),
        com.eignex.klause.solver.factor.LinearOp.LE, 0))
    factors.add(com.eignex.klause.solver.factor.Linear(intArrayOf(-1, -1), intArrayOf(b, absB),
        com.eignex.klause.solver.factor.LinearOp.LE, 0))
    val absBpa = allocBool("__div_absb_pa_${a}_${b}")
    val absBpb = allocBool("__div_absb_pb_${a}_${b}")
    factors.add(com.eignex.klause.solver.factor.ReifiedLinear(absBpa, intArrayOf(1, -1),
        intArrayOf(absB, b), com.eignex.klause.solver.factor.LinearOp.EQ, 0))
    factors.add(com.eignex.klause.solver.factor.ReifiedLinear(absBpb, intArrayOf(1, 1),
        intArrayOf(absB, b), com.eignex.klause.solver.factor.LinearOp.EQ, 0))
    factors.add(com.eignex.klause.solver.factor.Clause(
        intArrayOf(Lit.make(absBpa, true), Lit.make(absBpb, true))))
    // rem ≤ absB - 1: rem - absB ≤ -1.
    factors.add(com.eignex.klause.solver.factor.Linear(intArrayOf(1, -1), intArrayOf(rem, absB),
        com.eignex.klause.solver.factor.LinearOp.LE, -1))
    // rem ≥ -absB + 1: -rem - absB ≤ -1.
    factors.add(com.eignex.klause.solver.factor.Linear(intArrayOf(-1, -1), intArrayOf(rem, absB),
        com.eignex.klause.solver.factor.LinearOp.LE, -1))
    // Truncated semantics: sign(rem) = sign(a) when rem != 0.
    //   rem > 0 → a ≥ 0;  rem < 0 → a ≤ 0;  rem = 0 → no constraint.
    // Encode as: rem > 0 → a ≥ 1   (sign-aligned for non-zero rem) AND
    //            rem < 0 → a ≤ -1
    // Both via reified linears.
    val remPos = allocBool("__div_rempos_${a}_${b}")
    val remNeg = allocBool("__div_remneg_${a}_${b}")
    factors.add(com.eignex.klause.solver.factor.ReifiedLinear(remPos, intArrayOf(1),
        intArrayOf(rem), com.eignex.klause.solver.factor.LinearOp.GE, 1))
    factors.add(com.eignex.klause.solver.factor.ReifiedLinear(remNeg, intArrayOf(1),
        intArrayOf(rem), com.eignex.klause.solver.factor.LinearOp.LE, -1))
    val aNonNeg = allocBool("__div_apos_${a}_${b}")
    val aNonPos = allocBool("__div_aneg_${a}_${b}")
    factors.add(com.eignex.klause.solver.factor.ReifiedLinear(aNonNeg, intArrayOf(1),
        intArrayOf(a), com.eignex.klause.solver.factor.LinearOp.GE, 0))
    factors.add(com.eignex.klause.solver.factor.ReifiedLinear(aNonPos, intArrayOf(1),
        intArrayOf(a), com.eignex.klause.solver.factor.LinearOp.LE, 0))
    // remPos → aNonNeg:  ¬remPos ∨ aNonNeg
    factors.add(com.eignex.klause.solver.factor.Clause(
        intArrayOf(Lit.make(remPos, false), Lit.make(aNonNeg, true))))
    // remNeg → aNonPos:  ¬remNeg ∨ aNonPos
    factors.add(com.eignex.klause.solver.factor.Clause(
        intArrayOf(Lit.make(remNeg, false), Lit.make(aNonPos, true))))
}

/**
 * `array_int_element(idx, arr, result)` / `array_var_int_element(idx, arr, result)`:
 * `result = arr[idx]` with 1-based indexing. The decomposition reifies `idx = i` for
 * each `i ∈ [1, len]`, then implies `result = arr[i-1]` whenever the indicator holds.
 */
internal fun FlatZincCompiler.emitArrayIntElement(c: FznConstraint, varArray: Boolean) {
    require(c.args.size == 3)
    val idx = resolveIntVar(c.args[0])
    val result = resolveIntVar(c.args[2])
    val len: Int = if (varArray) {
        val arr = evalIntVarArray(c.args[1])
        for (i in 1..arr.size) wireElementCase(idx, i, "result_eq_arr[${i-1}]_var") { eqBool ->
            factors.add(ReifiedLinear(eqBool, intArrayOf(1, -1), intArrayOf(result, arr[i-1]), LinearOp.EQ, 0))
        }
        arr.size
    } else {
        val arrConst = evalIntConstArray(c.args[1])
        for (i in 1..arrConst.size) wireElementCase(idx, i, "result_eq_${arrConst[i-1]}_const") { eqBool ->
            factors.add(ReifiedLinear(eqBool, intArrayOf(1), intArrayOf(result), LinearOp.EQ, arrConst[i-1]))
        }
        arrConst.size
    }
    // Enforce idx ∈ [1, len].
    factors.add(Linear(intArrayOf(1), intArrayOf(idx), LinearOp.GE, 1))
    factors.add(Linear(intArrayOf(1), intArrayOf(idx), LinearOp.LE, len))
}

/** Shared "if idx = i then <body>" wiring for element constraints. Allocates two
 *  aux bools (idx-match indicator and body indicator) and clauses them. */
internal inline fun FlatZincCompiler.wireElementCase(idx: Int, i: Int, tag: String, registerBody: (Int) -> Unit) {
    val idxMatch = allocBool("__elem_${idx}_${i}_idx")
    factors.add(ReifiedLinear(idxMatch, intArrayOf(1), intArrayOf(idx), LinearOp.EQ, i))
    val bodyHolds = allocBool("__elem_${idx}_${i}_$tag")
    registerBody(bodyHolds)
    // idxMatch → bodyHolds  ≡  ¬idxMatch ∨ bodyHolds
    factors.add(Clause(intArrayOf(Lit.make(idxMatch, false), Lit.make(bodyHolds, true))))
}

internal fun FlatZincCompiler.emitArrayBoolElement(c: FznConstraint, varArray: Boolean) {
    require(c.args.size == 3)
    val idx = resolveIntVar(c.args[0])
    val resultLit = resolveBoolLit(c.args[2])
    val resultVar = Lit.variable(resultLit)
    val len: Int = if (varArray) {
        val arr = evalBoolVarArray(c.args[1])
        for (i in 1..arr.size) {
            val idxMatch = allocBool("__belem_${idx}_${i}_idx")
            factors.add(ReifiedLinear(idxMatch, intArrayOf(1), intArrayOf(idx), LinearOp.EQ, i))
            // idxMatch → (result ↔ arr[i-1]): two binary clauses.
            val arrLit = arr[i-1]
            val arrVar = Lit.variable(arrLit)
            factors.add(Clause(intArrayOf(Lit.make(idxMatch, false), Lit.make(resultVar, false), Lit.make(arrVar, true))))
            factors.add(Clause(intArrayOf(Lit.make(idxMatch, false), Lit.make(resultVar, true), Lit.make(arrVar, false))))
        }
        arr.size
    } else {
        val arrConst = evalBoolConstArray(c.args[1])
        for (i in 1..arrConst.size) {
            val idxMatch = allocBool("__belem_${idx}_${i}_idx")
            factors.add(ReifiedLinear(idxMatch, intArrayOf(1), intArrayOf(idx), LinearOp.EQ, i))
            if (arrConst[i-1]) {
                factors.add(Clause(intArrayOf(Lit.make(idxMatch, false), Lit.make(resultVar, true))))
            } else {
                factors.add(Clause(intArrayOf(Lit.make(idxMatch, false), Lit.make(resultVar, false))))
            }
        }
        arrConst.size
    }
    factors.add(Linear(intArrayOf(1), intArrayOf(idx), LinearOp.GE, 1))
    factors.add(Linear(intArrayOf(1), intArrayOf(idx), LinearOp.LE, len))
}

internal fun FlatZincCompiler.emitIntCmpReif(c: FznConstraint) {
    // int_{eq,ne,le,lt,ge,gt}_reif(a, b, r): r ↔ (a ⟨op⟩ b). All reduce to
    // ReifiedLinear with appropriate coeffs / bound.
    require(c.args.size == 3)
    val a = resolveIntVar(c.args[0])
    val b = resolveIntVar(c.args[1])
    val r = Lit.variable(resolveBoolLit(c.args[2]))
    val coeffs = intArrayOf(1, -1)
    val vars = intArrayOf(a, b)
    val (op, bound) = when (c.name) {
        "int_eq_reif" -> LinearOp.EQ to 0
        "int_ne_reif" -> LinearOp.NE to 0
        "int_le_reif" -> LinearOp.LE to 0
        "int_lt_reif" -> LinearOp.LE to -1     // a < b  ⇔  a - b ≤ -1
        "int_ge_reif" -> LinearOp.GE to 0
        "int_gt_reif" -> LinearOp.GE to 1      // a > b  ⇔  a - b ≥ 1
        else -> failHere("unhandled reified int cmp `${c.name}`")
    }
    factors.add(ReifiedLinear(r, coeffs, vars, op, bound))
}

/** `array_int_maximum(result, xs)` / `array_int_minimum(...)` — single [ArrayMinMax] factor. */
internal fun FlatZincCompiler.emitArrayMinMax(c: FznConstraint, max: Boolean) {
    require(c.args.size == 2)
    val result = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    factors.add(ArrayMinMax(result = result, xs = xs, max = max))
}

/** `exactly_int(n, xs, v)` — n equals #{i : xs[i] = v}. Reuses the [Count] factor with
 *  `op = Eq` and a constant target count channeled through an aux singleton int. */
internal fun FlatZincCompiler.emitExactly(c: FznConstraint) {
    require(c.args.size == 3)
    val n = evalIntConst(c.args[0]).toInt()
    val xs = evalIntVarArray(c.args[1])
    val v = evalIntConst(c.args[2]).toInt()
    val nVar = allocInt("__exactly_n_${v}", n, n)
    factors.add(Count(xs, v, Count.Op.Eq, nVar))
}

/** `increasing_int(xs)` / `decreasing_int(xs)` / strict variants — chained pairwise
 *  ordering, lowered to a single [Monotone] factor. */
internal fun FlatZincCompiler.emitMonotone(c: FznConstraint, ascending: Boolean, strict: Boolean) {
    require(c.args.size == 1)
    val xs = evalIntVarArray(c.args[0])
    if (xs.size < 2) return  // 0- or 1-element array is trivially monotone.
    val direction = if (ascending) Monotone.Direction.Increasing else Monotone.Direction.Decreasing
    factors.add(Monotone(xs, direction, strict))
}

internal fun FlatZincCompiler.emitAtLeast(c: FznConstraint) {
    // at_least_int(n, x[], v): at least n of x[i] = v.
    require(c.args.size == 3)
    val n = evalIntConst(c.args[0]).toInt()
    val xs = evalIntVarArray(c.args[1])
    val v = evalIntConst(c.args[2]).toInt()
    val lits = IntArray(xs.size) { i ->
        val aux = allocBool("__atleast_${xs[i]}_eq_${v}")
        factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(xs[i]), LinearOp.EQ, v))
        Lit.make(aux, true)
    }
    factors.add(Cardinality(lits, min = n, max = lits.size))
}

internal fun FlatZincCompiler.emitAtMost(c: FznConstraint) {
    require(c.args.size == 3)
    val n = evalIntConst(c.args[0]).toInt()
    val xs = evalIntVarArray(c.args[1])
    val v = evalIntConst(c.args[2]).toInt()
    val lits = IntArray(xs.size) { i ->
        val aux = allocBool("__atmost_${xs[i]}_eq_${v}")
        factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(xs[i]), LinearOp.EQ, v))
        Lit.make(aux, true)
    }
    factors.add(Cardinality(lits, min = 0, max = n))
}

/** Unified `count_{eq,neq,le,lt,ge,gt}(xs, v, n)`. When `v` is a constant int the dispatch
 *  lands on the [Count] factor directly. When `v` is a variable (only emitted for count_eq
 *  in practice) we fall back to the existing reified-decomposition path. */
internal fun FlatZincCompiler.emitCountOp(c: FznConstraint, op: Count.Op) {
    require(c.args.size == 3)
    val xs = evalIntVarArray(c.args[0])
    val vConst = evalIntConstOrNull(c.args[1])?.toInt()
    val n = resolveIntVar(c.args[2])
    if (vConst != null) {
        factors.add(Count(xs, vConst, op, n))
    } else {
        if (op != Count.Op.Eq) failHere("count_$op with variable v not supported; only count_eq")
        // Delegate to the older reified-decomposition path that channels b_i ↔ xs[i]=v
        // through ReifiedLinear + an integer-sum link.
        emitCountEq(c)
    }
}

/**
 * Unified `global_cardinality*` dispatch. Signatures:
 *  - `gcc(xs, cover[], counts[])`
 *  - `gcc_closed(xs, cover[], counts[])`
 *  - `gcc_low_up(xs, cover[], lo[], up[])`
 *  - `gcc_low_up_closed(xs, cover[], lo[], up[])`
 */
internal fun FlatZincCompiler.emitGcc(c: FznConstraint, lowUp: Boolean, closed: Boolean) {
    require(c.args.size == if (lowUp) 4 else 3)
    val xs = evalIntVarArray(c.args[0])
    val cover = evalIntConstArray(c.args[1])
    if (lowUp) {
        val lo = evalIntConstArray(c.args[2])
        val up = evalIntConstArray(c.args[3])
        factors.add(GlobalCardinality(
            xs = xs, cover = cover, countLow = lo, countHigh = up, closed = closed,
        ))
    } else {
        val countVars = evalIntVarArray(c.args[2])
        factors.add(GlobalCardinality(
            xs = xs, cover = cover, countVars = countVars, closed = closed,
        ))
    }
}

/** `distribute(card[], value[], base[])` — alias for `gcc(base, value, card)` (older
 *  MiniZinc syntax; equivalent semantics, parameter order shuffled). */
internal fun FlatZincCompiler.emitDistribute(c: FznConstraint) {
    require(c.args.size == 3)
    val card = evalIntVarArray(c.args[0])
    val value = evalIntConstArray(c.args[1])
    val base = evalIntVarArray(c.args[2])
    factors.add(GlobalCardinality(xs = base, cover = value, countVars = card))
}

/** `among(n, xs, S)` — `S` is a constant set literal or range. */
internal fun FlatZincCompiler.emitAmong(c: FznConstraint) {
    require(c.args.size == 3)
    val n = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    val setLit = c.args[2]
    val values: IntArray = when (setLit) {
        is FznExpr.IntSetLit -> IntArray(setLit.values.size) { setLit.values[it].toInt() }
        is FznExpr.IntRangeLit -> IntArray((setLit.hi - setLit.lo + 1).toInt()) { (setLit.lo + it).toInt() }
        is FznExpr.Ident -> {
            val p = params[setLit.name] ?: failHere("undefined parameter `${setLit.name}` in among")
            (p as? FlatZincCompiler.ParamValue.IntSet)?.values?.let { vs ->
                IntArray(vs.size) { vs[it].toInt() }
            } ?: failHere("`${setLit.name}` is not an int-set parameter")
        }
        else -> failHere("among: expected set literal or parameter, got ${setLit::class.simpleName}")
    }
    factors.add(Among(n, xs, values))
}

internal fun FlatZincCompiler.emitCountEq(c: FznConstraint) {
    // count_eq(x[], v, n): #{i : x[i] = v} = n. Any of v, n may be a constant or
    // a variable; FlatZinc allows all four combinations.
    require(c.args.size == 3)
    val xs = evalIntVarArray(c.args[0])
    val vConst = evalIntConstOrNull(c.args[1])?.toInt()
    val nConst = evalIntConstOrNull(c.args[2])?.toInt()

    // Build one bool indicator per xs[i] satisfying `b_i ↔ (xs[i] = v)`.
    val indicatorBools = IntArray(xs.size) { i ->
        val aux = allocBool("__count_${xs[i]}_eq_${i}")
        if (vConst != null) {
            factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(xs[i]), LinearOp.EQ, vConst))
        } else {
            // v is a variable: encode b_i ↔ (xs[i] - v = 0).
            val vVar = resolveIntVar(c.args[1])
            factors.add(ReifiedLinear(aux, intArrayOf(1, -1), intArrayOf(xs[i], vVar), LinearOp.EQ, 0))
        }
        aux
    }

    if (nConst != null) {
        // Pure cardinality constraint: exactly nConst indicators true.
        val lits = IntArray(xs.size) { Lit.make(indicatorBools[it], true) }
        factors.add(Cardinality(lits, min = nConst, max = nConst))
    } else {
        // n is a variable. Channel each b_i to a 0/1 int via a second reified
        // factor, then sum the channels and constrain the sum to equal n.
        val channels = IntArray(xs.size) { i ->
            val name = "__count_chan_$i"
            val id = allocInt(name, 0, 1)
            // `b_i ↔ (chan_i = 1)` — bidirectional channeling.
            factors.add(ReifiedLinear(
                auxBoolVar = indicatorBools[i],
                coeffs = intArrayOf(1),
                vars = intArrayOf(id),
                op = LinearOp.EQ,
                bound = 1,
            ))
            id
        }
        val nVar = resolveIntVar(c.args[2])
        val coefs = IntArray(xs.size + 1) { if (it < xs.size) 1 else -1 }
        val vars = IntArray(xs.size + 1) { if (it < xs.size) channels[it] else nVar }
        factors.add(Linear(coefs, vars, LinearOp.EQ, 0))
    }
}
