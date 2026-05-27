package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.AllDifferentExcept
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
import com.eignex.klause.solver.factor.ReifiedCardinality
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

    // int↔float coercion: bind the int var to the float var's bucket index.
    "int2float" -> emitInt2Float(c)

    // Float scalar comparisons + min/max/times — all reduced to bucket-index lowering
    // via Linear / ReifiedLinear / Table factors. See [emitFloatBinaryCmp] for the
    // binary cmp path and [emitFloatMinMax] / [emitFloatTimes] for the rest.
    "float_eq" -> emitFloatBinaryCmp(c, op = LinearOp.EQ, strict = false, reified = false)
    "float_eq_reif" -> emitFloatBinaryCmp(c, op = LinearOp.EQ, strict = false, reified = true)
    "float_le" -> emitFloatBinaryCmp(c, op = LinearOp.LE, strict = false, reified = false)
    "float_le_reif" -> emitFloatBinaryCmp(c, op = LinearOp.LE, strict = false, reified = true)
    "float_lt" -> emitFloatBinaryCmp(c, op = LinearOp.LE, strict = true, reified = false)
    "float_lt_reif" -> emitFloatBinaryCmp(c, op = LinearOp.LE, strict = true, reified = true)
    "float_ne" -> emitFloatBinaryCmp(c, op = LinearOp.NE, strict = false, reified = false)
    "float_ne_reif" -> emitFloatBinaryCmp(c, op = LinearOp.NE, strict = false, reified = true)
    "float_lin_lt" -> emitFloatLinearStrict(c, reified = false)
    "float_lin_lt_reif" -> emitFloatLinearStrict(c, reified = true)
    "float_min" -> emitFloatMinMax(c, max = false)
    "float_max" -> emitFloatMinMax(c, max = true)
    "float_times" -> emitFloatTimes(c)
    // float_div(a, b, c) ⇔ float_times(b, c, a):  a = b · c.
    "float_div" -> emitFloatTimes(FznConstraint(
        name = "float_times",
        args = listOf(c.args[1], c.args[2], c.args[0]),
        annotations = c.annotations,
    ))

    // Global
    "all_different_int" -> emitAllDifferent(c)
    "alldifferent_except_0", "fzn_alldifferent_except_0" -> emitAllDifferentExceptZero(c)
    "alldifferent_except", "fzn_alldifferent_except" -> emitAllDifferentExcept(c)
    "all_equal_int", "fzn_all_equal_int" -> emitAllEqual(c)
    "member_int", "fzn_member_int" -> emitMember(c)
    "sort", "fzn_sort" -> emitSort(c)
    "arg_sort_int", "fzn_arg_sort_int", "arg_sort" -> emitArgSort(c)
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
    "table_int", "fzn_table_int", "klause_table_int" -> emitTable(c)
    "regular", "fzn_regular", "klause_regular" -> emitRegular(c)
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

    // Bool variants of int globals — channel each bool lit to a 0/1 int and reuse the
    // existing int factor. Channels are allocated lazily per constraint; the engine
    // handles deduplication via standard propagation.
    "increasing_bool", "fzn_increasing_bool" -> emitMonotoneBool(c, ascending = true, strict = false)
    "decreasing_bool", "fzn_decreasing_bool" -> emitMonotoneBool(c, ascending = false, strict = false)
    "strictly_increasing_bool", "fzn_strictly_increasing_bool" -> emitMonotoneBool(c, ascending = true, strict = true)
    "strictly_decreasing_bool", "fzn_strictly_decreasing_bool" -> emitMonotoneBool(c, ascending = false, strict = true)
    "lex_less_bool", "fzn_lex_less_bool" -> emitLexLessBool(c, strict = true)
    "lex_lesseq_bool", "fzn_lex_lesseq_bool" -> emitLexLessBool(c, strict = false)
    "arg_max_bool", "fzn_arg_max_bool" -> emitArgMinMaxBool(c, max = true)
    "arg_min_bool", "fzn_arg_min_bool" -> emitArgMinMaxBool(c, max = false)
    "table_bool", "fzn_table_bool" -> emitTableBool(c)

    // MZN Challenge LS-track conventions: when [FlatZincCompiler.forLocalSearch] is set,
    // both annotations drop entirely; otherwise they assert their bool arg is true so the
    // wrapped constraint is enforced as the CP solver expects.
    "symmetry_breaking_constraint", "fzn_symmetry_breaking_constraint",
    "redundant_constraint", "fzn_redundant_constraint" -> emitAnnotationConstraint(c)

    // Set predicates: bool-indicator decomposition. Each `var set of E` materialises as one
    // bool per universe element; set algebra becomes bool algebra over those indicators.
    "set_in", "fzn_set_in" -> emitSetIn(c, reified = false)
    "set_in_reif", "fzn_set_in_reif" -> emitSetIn(c, reified = true)
    "set_subset", "fzn_set_subset" -> emitSetSubset(c, reified = false)
    "set_subset_reif", "fzn_set_subset_reif" -> emitSetSubset(c, reified = true)
    "set_eq", "fzn_set_eq" -> emitSetEq(c, reified = false)
    "set_eq_reif", "fzn_set_eq_reif" -> emitSetEq(c, reified = true)
    "set_le", "fzn_set_le" -> emitSetLex(c, strict = false, reified = false)
    "set_le_reif", "fzn_set_le_reif" -> emitSetLex(c, strict = false, reified = true)
    "set_lt", "fzn_set_lt" -> emitSetLex(c, strict = true, reified = false)
    "set_lt_reif", "fzn_set_lt_reif" -> emitSetLex(c, strict = true, reified = true)
    "array_set_element", "fzn_array_set_element" -> emitArraySetElement(c, varArray = false)
    "array_var_set_element", "fzn_array_var_set_element" -> emitArraySetElement(c, varArray = true)
    "set_ne", "fzn_set_ne" -> emitSetNe(c, reified = false)
    "set_ne_reif", "fzn_set_ne_reif" -> emitSetNe(c, reified = true)
    "set_card", "fzn_set_card" -> emitSetCard(c)
    "set_union", "fzn_set_union" -> emitSetUnion(c)
    "set_intersect", "fzn_set_intersect" -> emitSetIntersect(c)
    "set_diff", "fzn_set_diff" -> emitSetDiff(c)
    "all_disjoint", "fzn_all_disjoint" -> emitAllDisjoint(c)
    "set_partition_into", "fzn_set_partition_into" -> emitSetPartitionInto(c)

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
    // bool2int(b, x): b ↔ (x = 1), with x ∈ {0, 1}. We pin x to {0,1} via two unit
    // Linear bounds and encode the biconditional with a single ReifiedLinear. Polarity
    // of `b` is folded into the encoded bound: a negated bool literal (¬v ↔ x=1) is
    // rewritten as v ↔ (x = 0).
    val b = resolveBoolLit(c.args[0])
    val x = resolveIntVar(c.args[1])
    factors.add(Linear(intArrayOf(1), intArrayOf(x), LinearOp.GE, 0))
    factors.add(Linear(intArrayOf(1), intArrayOf(x), LinearOp.LE, 1))
    val targetBound = if (Lit.isPositive(b)) 1 else 0
    factors.add(ReifiedLinear(Lit.variable(b),
        coeffs = intArrayOf(1), vars = intArrayOf(x), op = LinearOp.EQ, bound = targetBound))
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
        // Record the original real-valued form so native-real backends (Z3) can solve over
        // reals instead of the bucketed scaled-int factor. Reified variants stay int-only —
        // there's no FloatMetadata channel for them yet.
        val floatIds = IntArray(coefs.size) { i ->
            floatVarIndex[varRefs[i].varId] ?: failHere("float var index missing")
        }
        realConstraints.add(com.eignex.klause.solver.RealLinearConstraint(coefs, floatIds, op, bound))
    }
}

/**
 * `int2float(x_int, y_float)` — coerce x's int value into y's float value. y is
 * backed by a bucket-index int var with `value(idx) = lo + idx * step`. The
 * constraint is `x = lo + idx_y * step`, which rearranges (after scaling by
 * [floatScale]) to a single linear equality over (x, idx_y). Identity buckets
 * (step=1.0, lo integer) are the common case from `var int → var float` lifts.
 */
internal fun FlatZincCompiler.emitInt2Float(c: FznConstraint) {
    require(c.args.size == 2)
    val xInt = resolveIntVar(c.args[0])
    val yName = (c.args[1] as? FznExpr.Ident)?.name
        ?: failHere("int2float: second arg must be a float var identifier")
    val yBk = floatVars[yName] ?: failHere("`$yName` is not a float var")
    val step = if (yBk.buckets > 1) (yBk.hi - yBk.lo) / (yBk.buckets - 1) else 0.0
    // floatScale·x − floatScale·step·idx_y = floatScale·lo.
    val cX = floatScale.toLong()
    val cIdxY = (-step * floatScale).roundToLong()
    val bound = (yBk.lo * floatScale).roundToLong()
    factors.add(Linear(
        intArrayOf(cX.toInt(), cIdxY.toInt()),
        intArrayOf(xInt, yBk.varId),
        LinearOp.EQ, bound.toInt(),
    ))
}

/**
 * Look up a float var by name (rejecting constants and non-float identifiers). Used by
 * the binary float-cmp / min-max / times paths where every argument is expected to be a
 * declared `var float` rather than a parameter or array element.
 */
private fun FlatZincCompiler.resolveFloatVarOrConst(e: FznExpr): FloatRef = when (e) {
    is FznExpr.FloatLit -> FloatRef.Const(e.value)
    is FznExpr.IntLit -> FloatRef.Const(e.value.toDouble())
    is FznExpr.Ident -> floatVars[e.name]?.let { FloatRef.Var(it) }
        ?: (params[e.name] as? FlatZincCompiler.ParamValue.Float)?.let { FloatRef.Const(it.value) }
        ?: failHere("`${e.name}` is not a float var or float param")
    else -> failHere("expected float var or float constant, got ${e::class.simpleName}")
}

private sealed interface FloatRef {
    data class Var(val bk: FloatBucketing) : FloatRef
    data class Const(val value: Double) : FloatRef
}

/**
 * `float_eq` / `float_le` / `float_lt` / `float_ne` (+ their `_reif` variants). Rewrites
 * `a op b` as the float-linear `1·a − 1·b op 0`, with [strict] adjusting LE by `-1` scaled
 * unit to encode strict-less. Constants on either side fold into the bound. Delegates to
 * [emitFloatLinear] by synthesising the equivalent `float_lin_*` constraint so the same
 * bucket-scaling logic runs in one place.
 */
internal fun FlatZincCompiler.emitFloatBinaryCmp(
    c: FznConstraint, op: LinearOp, strict: Boolean, reified: Boolean,
) {
    require(c.args.size == if (reified) 3 else 2)
    val a = resolveFloatVarOrConst(c.args[0])
    val b = resolveFloatVarOrConst(c.args[1])
    when {
        a is FloatRef.Const && b is FloatRef.Const -> {
            val holds = when (op) {
                LinearOp.LE -> if (strict) a.value < b.value else a.value <= b.value
                LinearOp.GE -> if (strict) a.value > b.value else a.value >= b.value
                LinearOp.EQ -> a.value == b.value
                LinearOp.NE -> a.value != b.value
            }
            if (reified) {
                val r = resolveBoolLit(c.args[2])
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                    if (holds) r else com.eignex.klause.solver.Lit.negate(r)
                )))
            } else if (!holds) {
                factors.add(com.eignex.klause.solver.factor.Clause(IntArray(0)))
            }
            return
        }
        else -> Unit
    }
    val varSide = if (a is FloatRef.Var) a.bk else (b as FloatRef.Var).bk
    val otherSide = if (a is FloatRef.Var) b else a
    val sign = if (a is FloatRef.Var) 1.0 else -1.0  // coefficient on the var-side arg
    val constPart = if (a is FloatRef.Var) {
        if (b is FloatRef.Const) b.value else 0.0
    } else (a as FloatRef.Const).value
    // Build a synthetic float_lin_* invocation: 1·a − 1·b op 0  ⇒  sign · var + (-sign) · other = -constPart on var side.
    // Simpler: use the existing emitFloatLinear by constructing scaled coefficients/bound
    // directly, without round-tripping through FznExpr.
    val step = if (varSide.buckets > 1) (varSide.hi - varSide.lo) / (varSide.buckets - 1) else 0.0
    val coefVar = (sign * step * floatScale).roundToLong()
    var scaledBound = (-sign * constPart * floatScale).roundToLong() -
        (sign * varSide.lo * floatScale).roundToLong()
    // Two-variable case folds the second var symmetrically.
    val coeffs: IntArray
    val vars: IntArray
    if (a is FloatRef.Var && b is FloatRef.Var) {
        val stepB = if (b.bk.buckets > 1) (b.bk.hi - b.bk.lo) / (b.bk.buckets - 1) else 0.0
        coeffs = intArrayOf(coefVar.toInt(), (-1.0 * stepB * floatScale).roundToLong().toInt())
        vars = intArrayOf(varSide.varId, b.bk.varId)
        scaledBound = (b.bk.lo * floatScale).roundToLong() -
            (varSide.lo * floatScale).roundToLong()
    } else {
        coeffs = intArrayOf(coefVar.toInt())
        vars = intArrayOf(varSide.varId)
    }
    val finalOp = op
    val finalBound = if (op == LinearOp.LE && strict) scaledBound - 1 else scaledBound
    if (reified) {
        val r = resolveBoolLit(c.args[2])
        factors.add(ReifiedLinear(
            com.eignex.klause.solver.Lit.variable(r), coeffs, vars, finalOp, finalBound.toInt()))
    } else {
        factors.add(Linear(coeffs, vars, finalOp, finalBound.toInt()))
    }
}

/**
 * `float_lin_lt(coeffs, vars, bound)` — strict `Σ c·v < bound`. Reduce to
 * `float_lin_le` with `bound − 1` scaled unit so the strict semantics is preserved at
 * bucket granularity (sound since `floatScale` is the smallest representable diff).
 */
internal fun FlatZincCompiler.emitFloatLinearStrict(c: FznConstraint, reified: Boolean) {
    val rewritten = FznConstraint(
        name = if (reified) "float_lin_le_reif" else "float_lin_le",
        args = c.args,
        annotations = c.annotations,
    )
    val coefs = evalFloatConstArray(c.args[0])
    val varRefs = evalFloatVarArray(c.args[1])
    val bound = evalFloatConst(c.args[2])
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
    // Strict: subtract one scaled unit.
    scaledBound -= 1
    if (reified) {
        val aux = resolveBoolLit(c.args[3])
        factors.add(ReifiedLinear(
            com.eignex.klause.solver.Lit.variable(aux),
            scaledCoeffs, vars, LinearOp.LE, scaledBound.toInt()))
    } else {
        factors.add(Linear(scaledCoeffs, vars, LinearOp.LE, scaledBound.toInt()))
    }
}

/**
 * `float_min(a, b, c)` / `float_max(a, b, c)` — c = min/max(a, b). Lowered as the pair
 * of bucket-level inequalities `c ≤ a, c ≤ b` (min) or `c ≥ a, c ≥ b` (max), plus a
 * reified disjunction `c = a ∨ c = b` so c is forced to the bound by one of the inputs.
 */
internal fun FlatZincCompiler.emitFloatMinMax(c: FznConstraint, max: Boolean) {
    require(c.args.size == 3)
    val a = resolveFloatVarOrConst(c.args[0])
    val b = resolveFloatVarOrConst(c.args[1])
    val out = resolveFloatVarOrConst(c.args[2])
    // Inequality direction: c ≤ a, c ≤ b (min) or c ≥ a, c ≥ b (max).
    val cmpOp = if (max) LinearOp.LE else LinearOp.LE  // reused, sign flips via emit
    // Build synthetic float_lin_le or float_lin_ge by reusing emitFloatBinaryCmp's
    // two-var path. For max we negate by swapping argument order.
    val ineqOp = if (max) LinearOp.LE else LinearOp.LE
    val argA = c.args[0]
    val argB = c.args[1]
    val argC = c.args[2]
    fun emitIneq(left: FznExpr, right: FznExpr) {
        val fc = FznConstraint(if (max) "float_le" else "float_le", listOf(left, right), emptyList())
        emitFloatBinaryCmp(fc, op = LinearOp.LE, strict = false, reified = false)
    }
    if (max) {
        // c ≥ a  ⇔  a ≤ c
        emitIneq(argA, argC)
        emitIneq(argB, argC)
    } else {
        // c ≤ a, c ≤ b
        emitIneq(argC, argA)
        emitIneq(argC, argB)
    }
    // Disjunction c = a ∨ c = b via two reified equalities and a clause. Unique
    // suffix ties the aux bools to this constraint's position in the factor list,
    // so multiple float_min/max in one model don't collide.
    val suffix = factors.size.toString()
    val auxA = allocBool("__fminmax_a_$suffix")
    val auxB = allocBool("__fminmax_b_$suffix")
    val eqA = FznConstraint("float_eq_reif", listOf(argA, argC, FznExpr.Ident("__fminmax_a_$suffix")), emptyList())
    val eqB = FznConstraint("float_eq_reif", listOf(argB, argC, FznExpr.Ident("__fminmax_b_$suffix")), emptyList())
    // Note: emitFloatBinaryCmp resolves the bool lit via resolveBoolLit which looks up the
    // identifier in [boolVars]; allocBool registers there.
    emitFloatBinaryCmp(eqA, op = LinearOp.EQ, strict = false, reified = true)
    emitFloatBinaryCmp(eqB, op = LinearOp.EQ, strict = false, reified = true)
    factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
        com.eignex.klause.solver.Lit.make(auxA, true),
        com.eignex.klause.solver.Lit.make(auxB, true),
    )))
}

/**
 * `float_times(a, b, c)` — c = a · b. Non-linear, lowered by enumerating the Cartesian
 * product of (a, b) bucket indices and emitting an N_a · N_b row [Table] over
 * (idx_a, idx_b, idx_c). The c column is computed by `value(a)·value(b)` rounded to c's
 * closest bucket index; rows whose rounded c falls outside c's domain are dropped (the
 * constraint forbids them). Sound but quadratic in bucket counts — use with care on
 * coarsely bucketed floats.
 */
internal fun FlatZincCompiler.emitFloatTimes(c: FznConstraint) {
    require(c.args.size == 3)
    val aRef = resolveFloatVarOrConst(c.args[0])
    val bRef = resolveFloatVarOrConst(c.args[1])
    val cRef = resolveFloatVarOrConst(c.args[2])
    if (aRef !is FloatRef.Var || bRef !is FloatRef.Var || cRef !is FloatRef.Var) {
        // At least one side constant — degenerate to a linear constraint.
        // (No corpus case exercises this; defer if encountered.)
        failHere("float_times with constant operand not yet handled (only var·var=var)")
    }
    val a = aRef.bk
    val b = bRef.bk
    val cBk = cRef.bk
    val stepA = if (a.buckets > 1) (a.hi - a.lo) / (a.buckets - 1) else 0.0
    val stepB = if (b.buckets > 1) (b.hi - b.lo) / (b.buckets - 1) else 0.0
    val stepC = if (cBk.buckets > 1) (cBk.hi - cBk.lo) / (cBk.buckets - 1) else 0.0
    val rows = ArrayList<Int>(a.buckets * b.buckets * 3)
    val tolerance = 0.5  // round to nearest bucket
    for (ia in 0 until a.buckets) {
        val va = a.lo + ia * stepA
        for (ib in 0 until b.buckets) {
            val vb = b.lo + ib * stepB
            val vc = va * vb
            if (vc < cBk.lo - stepC * tolerance || vc > cBk.hi + stepC * tolerance) continue
            val ic = if (stepC == 0.0) 0 else ((vc - cBk.lo) / stepC).let {
                val rounded = kotlin.math.round(it).toInt()
                if (kotlin.math.abs(it - rounded) > tolerance) return@let -1
                rounded
            }
            if (ic < 0 || ic >= cBk.buckets) continue
            rows.add(ia); rows.add(ib); rows.add(ic)
        }
    }
    if (rows.isEmpty()) {
        // No feasible row — infeasible.
        factors.add(com.eignex.klause.solver.factor.Clause(IntArray(0)))
        return
    }
    factors.add(com.eignex.klause.solver.factor.Table(
        intArrayOf(a.varId, b.varId, cBk.varId), rows.toIntArray()
    ))
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

/** `alldifferent_except(xs, S)` where S is a constant set of int (the sentinel values). */
internal fun FlatZincCompiler.emitAllDifferentExcept(c: FznConstraint) {
    require(c.args.size == 2)
    val vars = evalIntVarArray(c.args[0])
    val setArg = c.args[1]
    val except: IntArray = when (setArg) {
        is FznExpr.IntSetLit -> IntArray(setArg.values.size) { setArg.values[it].toInt() }
        is FznExpr.IntRangeLit -> IntArray((setArg.hi - setArg.lo + 1).toInt()) { (setArg.lo + it).toInt() }
        else -> failHere("alldifferent_except: expected set literal for S, got ${setArg::class.simpleName}")
    }
    factors.add(AllDifferentExcept(vars, except))
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

/** `arg_sort_int(values, perm)` — perm is a permutation of `1..n` (or `0..n-1` if domains
 *  start at 0) such that `values[perm[i]]` is non-decreasing. */
internal fun FlatZincCompiler.emitArgSort(c: FznConstraint) {
    require(c.args.size == 2)
    val values = evalIntVarArray(c.args[0])
    val perm = evalIntVarArray(c.args[1])
    val offset = if (perm.isNotEmpty()) intDomains[perm[0]].min else 0
    factors.add(com.eignex.klause.solver.factor.ArgSort(values, perm, permOffset = offset))
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
/**
 * Channel a bool literal array into a parallel int-var array with domain [0, 1] each.
 * Adds a ReifiedLinear per literal that ties `lit ↔ (channel = 1)`. Returns the new int
 * var ids in the same order. Used by the bool-variant dispatches that reuse int factors.
 */
internal fun FlatZincCompiler.channelBoolsToInts(lits: IntArray, tag: String): IntArray {
    return IntArray(lits.size) { i ->
        val ch = allocInt("__chan_${tag}_$i", 0, 1)
        // lit ↔ (ch = 1): ReifiedLinear with auxBoolVar = variable of lit, polarity-aware.
        // For a positive literal: aux = lit's var directly.
        // For a negative literal: lit = ¬b, so b ↔ (ch = 0) which is the same as ¬b ↔ (ch = 1).
        val (auxVar, useNegatedTarget) = Lit.variable(lits[i]) to !Lit.isPositive(lits[i])
        factors.add(ReifiedLinear(
            auxBoolVar = auxVar,
            coeffs = intArrayOf(1), vars = intArrayOf(ch),
            op = LinearOp.EQ,
            bound = if (useNegatedTarget) 0 else 1,
        ))
        ch
    }
}

internal fun FlatZincCompiler.emitMonotoneBool(c: FznConstraint, ascending: Boolean, strict: Boolean) {
    require(c.args.size == 1)
    val lits = evalBoolVarArray(c.args[0])
    if (lits.size < 2) return
    val ints = channelBoolsToInts(lits, "mono")
    val direction = if (ascending) Monotone.Direction.Increasing else Monotone.Direction.Decreasing
    factors.add(Monotone(ints, direction, strict))
}

internal fun FlatZincCompiler.emitLexLessBool(c: FznConstraint, strict: Boolean) {
    require(c.args.size == 2)
    val xLits = evalBoolVarArray(c.args[0])
    val yLits = evalBoolVarArray(c.args[1])
    val xs = channelBoolsToInts(xLits, "lex_x")
    val ys = channelBoolsToInts(yLits, "lex_y")
    factors.add(LexLess(xs, ys, strict))
}

internal fun FlatZincCompiler.emitArgMinMaxBool(c: FznConstraint, max: Boolean) {
    require(c.args.size == 2)
    val xLits = evalBoolVarArray(c.args[0])
    val idx = resolveIntVar(c.args[1])
    val xs = channelBoolsToInts(xLits, "argmm")
    val offset = intDomains[idx].min
    factors.add(ArgMinMax(idx = idx, xs = xs, max = max, indexOffset = offset))
}

internal fun FlatZincCompiler.emitTableBool(c: FznConstraint) {
    require(c.args.size == 2)
    val xLits = evalBoolVarArray(c.args[0])
    val tuplesBool = evalBoolConstArray(c.args[1])
    val xs = channelBoolsToInts(xLits, "tbl")
    val tuples = IntArray(tuplesBool.size) { if (tuplesBool[it]) 1 else 0 }
    factors.add(Table(xs, tuples))
}

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
        // MZN allows the `counts` argument to be either an int-var array (the standard
        // form) or a constant int array (count must equal that fixed value). Detect the
        // constant form first and route to the low_up path with lo[i] = up[i] = counts[i];
        // the var form falls through to the GlobalCardinality(countVars) constructor.
        val countsAsConst = tryEvalIntConstArray(c.args[2])
        if (countsAsConst != null) {
            factors.add(GlobalCardinality(
                xs = xs, cover = cover,
                countLow = countsAsConst, countHigh = countsAsConst, closed = closed,
            ))
            return
        }
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

internal fun FlatZincCompiler.emitAnnotationConstraint(c: FznConstraint) {
    if (forLocalSearch) return
    require(c.args.size == 1) { "${c.name} expects 1 arg" }
    val lit = resolveBoolLit(c.args[0])
    factors.add(Clause(intArrayOf(lit)))
}

// ===== Set predicates: bool-indicator decomposition =====
//
// Each `var set of E: S` was materialised by [allocSetVar] into one bool per universe
// element. The helpers below lower set algebra to bool algebra over those indicators —
// every set constraint reduces to clauses, cardinality, or pseudo-Boolean sums we already
// have factors for.

/** Resolve a set var reference to its layout. Set literals as parameters (e.g. `set of int: u = {1,3}`)
 *  are not handled here — they belong on the constraint side as constant universes. Accepts
 *  both plain idents (`s`) and array accesses (`a[2]`) into a set-var array. */
internal fun FlatZincCompiler.resolveSetVar(e: FznExpr): SetVarLayout = when (e) {
    is FznExpr.Ident -> setVarsByName[e.name] ?: failHere("`${e.name}` is not a set var")
    is FznExpr.ArrayAccess -> {
        val arr = arrays[e.name] as? FlatZincArray.SetVars
            ?: failHere("`${e.name}` is not a set var array")
        arr.layouts[e.index - 1]
    }
    else -> failHere("expected a set var reference, got ${e::class.simpleName}")
}

/** Resolve a set-literal expression to its element list (sorted ascending). */
internal fun FlatZincCompiler.resolveSetLiteral(e: FznExpr): IntArray = when (e) {
    is FznExpr.IntSetLit -> e.values.map { it.toInt() }.toIntArray().also { it.sort() }
    is FznExpr.IntRangeLit -> IntArray((e.hi - e.lo + 1).toInt()) { (e.lo + it).toInt() }
    is FznExpr.Ident -> {
        // Parameter set: resolve via params map.
        val pv = params[e.name] ?: failHere("undefined set parameter `${e.name}`")
        when (pv) {
            is FlatZincCompiler.ParamValue.IntSet -> pv.values.map { it.toInt() }.toIntArray().also { it.sort() }
            else -> failHere("`${e.name}` is not a set parameter")
        }
    }
    else -> failHere("expected a set literal, got ${e::class.simpleName}")
}

/** `set_in(x, S)` and `set_in_reif(x, S, r)`. Two forms depending on `x`:
 *   - Constant int: direct lookup of the indicator bool for that element.
 *   - Var int: per-element channel `chanᵥ ↔ (x = v)` for every `v` in `x`'s domain,
 *     plus the constraint `chanᵥ ⇒ indicatorᵥ` for v ∈ universe(S) and `chanᵥ = false`
 *     for v ∉ universe(S). For reified, `r ↔ ⋁ (chanᵥ ∧ indicatorᵥ)` via aux ANDs.
 */
internal fun FlatZincCompiler.emitSetIn(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val elem = c.args[0]
    val sExpr = c.args[1]
    val rExpr = if (reified) c.args[2] else null
    // Set-literal RHS: `set_in(x, {v1..vn})` or `set_in(x, lo..hi)`. The MZN compiler
    // produces this directly for `x in S` where S is a parameter set; the set-var
    // indicator layout from `resolveSetVar` is the wrong abstraction here, so handle
    // it inline against the literal values.
    if (sExpr is FznExpr.IntSetLit || sExpr is FznExpr.IntRangeLit) {
        val values = resolveSetLiteral(sExpr)
        emitSetInLiteral(elem, values, rExpr)
        return
    }
    val layout = resolveSetVar(sExpr)
    if (elem is FznExpr.IntLit) {
        emitSetInConst(elem.value.toInt(), layout, rExpr)
        return
    }
    // Var-int element path.
    val xVar = resolveIntVar(elem)
    val dom = intDomains[xVar]
    emitSetInVarInt(xVar, dom.min, dom.max, layout, rExpr)
}

/** `set_in(x, S)` where S is a constant set literal. Two cases by `x`:
 *   - Constant int: trivial — non-reified passes/fails immediately; reified pins `r` to the
 *     membership truth value.
 *   - Var int: decompose to `r ↔ ⋁ (x = vᵢ)` for vᵢ ∈ S via a fresh chan bool per element
 *     + ReifiedCardinality.atLeastOne. Non-reified path forces the disjunction with a plain
 *     Cardinality. Values outside `x`'s current domain contribute no chan (always false). */
private fun FlatZincCompiler.emitSetInLiteral(elem: FznExpr, values: IntArray, rExpr: FznExpr?) {
    if (elem is FznExpr.IntLit) {
        val v = elem.value.toInt()
        val isMember = values.binarySearch(v) >= 0
        if (rExpr != null) {
            val r = resolveBoolLit(rExpr)
            factors.add(Clause(intArrayOf(if (isMember) r else Lit.negate(r))))
        } else {
            if (!isMember) failHere("set_in: element $v outside literal set $values")
        }
        return
    }
    val xVar = resolveIntVar(elem)
    val dom = intDomains[xVar]
    val membershipLits = ArrayList<Int>()
    for (v in values) {
        if (v < dom.min || v > dom.max) continue
        val chan = allocBool("__set_in_lit_chan_${xVar}_$v")
        factors.add(ReifiedLinear(
            auxBoolVar = chan,
            coeffs = intArrayOf(1), vars = intArrayOf(xVar),
            op = LinearOp.EQ, bound = v,
        ))
        membershipLits += Lit.make(chan, true)
    }
    if (rExpr == null) {
        // Non-reified: at least one chan must be true (i.e. x ∈ S). If S is empty given
        // the domain, this is trivially unsatisfiable — emit an empty disjunction (false).
        if (membershipLits.isEmpty()) {
            factors.add(Clause(IntArray(0)))
            return
        }
        factors.add(Cardinality.atLeastOne(membershipLits.toIntArray()))
        return
    }
    val r = resolveBoolLit(rExpr)
    if (membershipLits.isEmpty()) {
        // r ↔ false → r must be false.
        factors.add(Clause(intArrayOf(Lit.negate(r))))
        return
    }
    factors.add(ReifiedCardinality(
        auxBoolVar = Lit.variable(r),
        literals = membershipLits.toIntArray(),
        min = 1, max = membershipLits.size,
    ))
    // Lit.variable strips polarity; if r is negated, flip the reification by swapping
    // the cardinality bound (atLeastOne ↔ exactlyZero on a negated reification).
    if (!Lit.isPositive(r)) {
        // Replace the just-added factor with the negated form: ¬r ↔ ⋁ → r ↔ ¬⋁.
        // Encoded as: ¬r ⇒ all chan false, and r ⇒ some chan true. The simplest
        // equivalent is ReifiedCardinality with min=0,max=0 on the negated aux —
        // but we already emitted the positive form. Add the complement clause set.
        factors.removeAt(factors.size - 1)
        factors.add(ReifiedCardinality(
            auxBoolVar = Lit.variable(r),
            literals = membershipLits.toIntArray(),
            min = 0, max = 0,
        ))
    }
}

private fun FlatZincCompiler.emitSetInConst(xConst: Int, layout: SetVarLayout, rExpr: FznExpr?) {
    val idx = layout.elements.binarySearch(xConst)
    if (idx < 0) {
        if (rExpr != null) {
            val r = resolveBoolLit(rExpr)
            factors.add(Clause(intArrayOf(Lit.negate(r))))
        } else {
            failHere("set_in: element $xConst outside set `${layout.name}`'s universe")
        }
        return
    }
    val indicator = layout.indicatorBoolIds[idx]
    if (rExpr != null) {
        val r = resolveBoolLit(rExpr)
        factors.add(Clause(intArrayOf(Lit.negate(r), Lit.make(indicator, true))))
        factors.add(Clause(intArrayOf(r, Lit.make(indicator, false))))
    } else {
        factors.add(Clause(intArrayOf(Lit.make(indicator, true))))
    }
}

private fun FlatZincCompiler.emitSetInVarInt(
    xVar: Int, xLo: Int, xHi: Int, layout: SetVarLayout, rExpr: FznExpr?,
) {
    val membershipLits = ArrayList<Int>()
    for (v in xLo..xHi) {
        // chanᵥ ↔ (x = v) via int_lin_eq_reif([1], [x], v, chanᵥ).
        val chan = allocBool("__set_in_chan_${layout.name}_$v")
        factors.add(ReifiedLinear(
            auxBoolVar = chan,
            coeffs = intArrayOf(1), vars = intArrayOf(xVar),
            op = LinearOp.EQ, bound = v,
        ))
        val setIdx = layout.elements.binarySearch(v)
        if (rExpr == null) {
            // Non-reified: x ∈ S must hold.
            if (setIdx < 0) {
                // x = v would put x outside S's universe → forbid.
                factors.add(Clause(intArrayOf(Lit.make(chan, false))))
            } else {
                // chanᵥ ⇒ indicatorᵥ : (¬chanᵥ ∨ indicatorᵥ)
                factors.add(Clause(intArrayOf(
                    Lit.make(chan, false),
                    Lit.make(layout.indicatorBoolIds[setIdx], true),
                )))
            }
        } else {
            // Reified: build per-element AND aux_v ↔ (chanᵥ ∧ indicatorᵥ), then r ↔ ⋁ aux_v.
            if (setIdx < 0) {
                // chanᵥ → r = false. Captured by membershipLits not including v.
                // No clause needed: aux_v would be permanently false.
                continue
            }
            val ind = layout.indicatorBoolIds[setIdx]
            val aux = allocBool("__set_in_aux_${layout.name}_$v")
            // aux ↔ (chan ∧ indicator): (¬aux ∨ chan), (¬aux ∨ indicator), (aux ∨ ¬chan ∨ ¬indicator).
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(chan, true))))
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(ind, true))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(chan, false), Lit.make(ind, false))))
            membershipLits += Lit.make(aux, true)
        }
    }
    if (rExpr != null) {
        val r = resolveBoolLit(rExpr)
        // r ↔ ⋁ membershipLits.
        val lits = membershipLits.toIntArray()
        if (lits.isEmpty()) {
            // No element of x's domain is in S's universe → r must be false.
            factors.add(Clause(intArrayOf(Lit.negate(r))))
        } else {
            // r → ⋁ aux : (¬r ∨ aux1 ∨ ... ∨ auxn)
            factors.add(Clause(intArrayOf(Lit.negate(r)) + lits))
            // each auxᵢ → r : (¬auxᵢ ∨ r)
            for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), r)))
        }
    }
}

/** `set_subset(S, T)` / `set_subset_reif(S, T, r)`. Non-reified: for each universe element
 *  of S, `Sᵢ ⇒ Tᵢ` (with elements outside T's universe forced absent from S).
 *  Reified: per element, channel `auxᵢ ↔ (Sᵢ ⇒ Tᵢ)`, then `r ↔ ⋀ auxᵢ`. */
internal fun FlatZincCompiler.emitSetSubset(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    if (!reified) {
        for (i in s.elements.indices) {
            val e = s.elements[i]
            val sBit = s.indicatorBoolIds[i]
            val tIdx = t.elements.binarySearch(e)
            if (tIdx < 0) {
                factors.add(Clause(intArrayOf(Lit.make(sBit, false))))
            } else {
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(t.indicatorBoolIds[tIdx], true))))
            }
        }
        return
    }
    val r = resolveBoolLit(c.args[2])
    val auxes = ArrayList<Int>(s.elements.size)
    for (i in s.elements.indices) {
        val sBit = s.indicatorBoolIds[i]
        val tIdx = t.elements.binarySearch(s.elements[i])
        val aux = allocBool("__subset_aux_${s.name}_${t.name}_${s.elements[i]}")
        auxes += Lit.make(aux, true)
        if (tIdx < 0) {
            // aux ↔ ¬Sᵢ : two clauses.
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(sBit, true))))
        } else {
            val tBit = t.indicatorBoolIds[tIdx]
            // aux ↔ (¬Sᵢ ∨ Tᵢ): three clauses.
            // (¬aux ∨ ¬Sᵢ ∨ Tᵢ), (Sᵢ ∨ aux), (¬Tᵢ ∨ aux)
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false), Lit.make(tBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(aux, true))))
            factors.add(Clause(intArrayOf(Lit.make(tBit, false), Lit.make(aux, true))))
        }
    }
    reifyAndOfLits(auxes.toIntArray(), r)
}

/** `r ↔ ⋀ lits`. Decomposes to one big OR (the "r false implies some lit false" direction)
 *  plus one binary clause per lit (the "r true implies lit true" direction). */
internal fun FlatZincCompiler.reifyAndOfLits(lits: IntArray, r: Int) {
    factors.add(Clause(lits.map { Lit.negate(it) }.toIntArray() + intArrayOf(r)))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(r), l)))
}

/** `set_eq(S, T)` / `set_eq_reif(S, T, r)`. Non-reified: per element of S ∪ T's universe,
 *  `Sᵢ ↔ Tᵢ`; elements only in one universe force the corresponding indicator false.
 *  Reified: per element, channel `auxᵢ ↔ (Sᵢ ↔ Tᵢ)`, then `r ↔ ⋀ auxᵢ`. */
internal fun FlatZincCompiler.emitSetEq(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    if (!reified) {
        for (i in s.elements.indices) {
            val e = s.elements[i]
            val sBit = s.indicatorBoolIds[i]
            val tIdx = t.elements.binarySearch(e)
            if (tIdx < 0) {
                factors.add(Clause(intArrayOf(Lit.make(sBit, false))))
            } else {
                val tBit = t.indicatorBoolIds[tIdx]
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(tBit, false))))
            }
        }
        for (i in t.elements.indices) {
            if (s.elements.binarySearch(t.elements[i]) < 0) {
                factors.add(Clause(intArrayOf(Lit.make(t.indicatorBoolIds[i], false))))
            }
        }
        return
    }
    val r = resolveBoolLit(c.args[2])
    val auxes = ArrayList<Int>()
    val emitEqAux: (Int, Int, Int) -> Unit = { sBit, tBit, aux ->
        // aux ↔ (Sᵢ = Tᵢ): four clauses from the truth table.
        // (S=0,T=0,aux=0): S∨T∨aux. (0,1,1): S∨¬T∨¬aux.
        // (1,0,1): ¬S∨T∨¬aux. (1,1,0): ¬S∨¬T∨aux.
        factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(tBit, true), Lit.make(aux, true))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(tBit, false), Lit.make(aux, false))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, true), Lit.make(aux, false))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, false), Lit.make(aux, true))))
    }
    for (i in s.elements.indices) {
        val sBit = s.indicatorBoolIds[i]
        val tIdx = t.elements.binarySearch(s.elements[i])
        val aux = allocBool("__eq_aux_${s.name}_${t.name}_${s.elements[i]}")
        auxes.add(Lit.make(aux, true))
        if (tIdx < 0) {
            // No counterpart in T → aux ↔ ¬Sᵢ.
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(sBit, true))))
        } else {
            emitEqAux(sBit, t.indicatorBoolIds[tIdx], aux)
        }
    }
    for (i in t.elements.indices) {
        if (s.elements.binarySearch(t.elements[i]) < 0) {
            val tBit = t.indicatorBoolIds[i]
            val aux = allocBool("__eq_aux_${s.name}_${t.name}_only_t_${t.elements[i]}")
            auxes.add(Lit.make(aux, true))
            // aux ↔ ¬Tᵢ.
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(tBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(tBit, true))))
        }
    }
    reifyAndOfLits(auxes.toIntArray(), r)
}

/** `set_ne(S, T)` / `set_ne_reif(S, T, r)`. Allocates an `eq` aux mirroring `set_eq_reif`'s
 *  channel, then ties `r = ¬eq` (reified) or `eq = false` (non-reified). */
internal fun FlatZincCompiler.emitSetNe(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    // Allocate the eq-channel and run the per-element decomposition over it.
    val eqLit = if (reified) {
        val r = resolveBoolLit(c.args[2])
        val eqAux = allocBool("__set_ne_eq_${s.name}_${t.name}")
        val eqLit = Lit.make(eqAux, true)
        // r ↔ ¬eq:  (r ∨ eq), (¬r ∨ ¬eq)
        factors.add(Clause(intArrayOf(r, eqLit)))
        factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(eqLit))))
        eqLit
    } else {
        // Hard inequality: force eq = false.
        val eqAux = allocBool("__set_ne_eq_${s.name}_${t.name}")
        val eqLit = Lit.make(eqAux, true)
        factors.add(Clause(intArrayOf(Lit.negate(eqLit))))
        eqLit
    }
    emitSetEqChannel(s, t, eqLit)
}

/** Resolve a set-var-array argument to a list of [SetVarLayout]. Accepts either an array
 *  name (registered as [FlatZincArray.SetVars]) or an inline array literal of set var idents. */
internal fun FlatZincCompiler.resolveSetVarArray(e: FznExpr): List<SetVarLayout> = when (e) {
    is FznExpr.Ident -> when (val a = arrays[e.name]) {
        is FlatZincArray.SetVars -> a.layouts
        else -> failHere("`${e.name}` is not an array of set vars")
    }
    is FznExpr.ArrayLit -> e.elements.map { resolveSetVar(it) }
    else -> failHere("expected an array of set vars, got ${e::class.simpleName}")
}

/**
 * `set_le(S, T)` / `set_lt(S, T)` (+ `_reif`) — lex-of-sorted-elements comparison from
 * MiniZinc's `std/nosets.mzn`. Walks the joint universe top-down; at each element `e`,
 * cascade-equality flows from the higher level unless one side has `e` and the other
 * doesn't — then the comparison is determined by whether the side-without-e has any
 * larger element, captured by per-set `max` ints.
 *
 * Decomposition (per the MZN std impl):
 *   - Let `U` = sorted ascending union of S.universe ∪ T.universe.
 *   - For S and T, alloc `xmax`/`ymax` int vars = max(set ∪ {U[0]-1}). Channel each
 *     indicator bool to a 0/1 int and use ArrayMax.
 *   - Allocate `b[i]` bool for each position i in U, representing "lex-≤ considering
 *     only elements ≥ U[i]".
 *   - Top of the table: `b[u]` = `S_has(u) → T_has(u)` (last position).
 *   - Inner i: 4-case truth-table over (S_has, T_has):
 *       (0,0) → b[i] = b[i+1]
 *       (0,1) → b[i] = (xmax < U[i])         // S has nothing ≥ U[i], T has U[i], S<T
 *       (1,0) → b[i] = (ymax > U[i])         // S has U[i], T must have larger, else S>T
 *       (1,1) → b[i] = b[i+1]
 *   - Result: `b[0]` is the lex-≤ verdict. For `set_lt` (strict), the final bit is
 *     `b[0] ∧ ¬(S = T)` — implemented by reifying set_eq as an aux and combining.
 */
internal fun FlatZincCompiler.emitSetLex(c: FznConstraint, strict: Boolean, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val universe = (s.elements.toSet() + t.elements.toSet()).sorted().toIntArray()
    if (universe.isEmpty()) {
        // Both sets are over empty universes: lex-≤ trivially holds, strict-≤ fails.
        if (reified) {
            val r = resolveBoolLit(c.args[2])
            val lit = if (strict) com.eignex.klause.solver.Lit.negate(r) else r
            factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(lit)))
        } else if (strict) {
            factors.add(com.eignex.klause.solver.factor.Clause(IntArray(0)))
        }
        return
    }
    // Allocate xmax/ymax via ArrayMax over channel int vars: channel[i] = element if
    // indicator true else (universe[0] - 1).
    val lo = universe.first() - 1
    val hi = universe.last()
    fun maxOf(set: SetVarLayout, label: String): Int {
        // For each universe element, find the indicator (or skip if not in set's universe).
        // Channel vars: `c[i] in [lo, hi]`, c[i] = element if indicator[i] true else lo.
        val channels = IntArray(set.elements.size)
        for (i in set.elements.indices) {
            val elem = set.elements[i]
            val ind = set.indicatorBoolIds[i]
            val ch = allocInt("__setlex_${label}_${set.name}_${elem}", lo, hi)
            channels[i] = ch
            // ind=true  ⇒ ch=elem;   ind=false ⇒ ch=lo
            // Encoded as ReifiedLinear(ind, [1] * [ch], EQ, elem) plus
            //            ReifiedLinear(¬ind, [1] * [ch], EQ, lo).
            factors.add(ReifiedLinear(ind,
                coeffs = intArrayOf(1), vars = intArrayOf(ch), op = LinearOp.EQ, bound = elem))
            // For "ind=false ⇒ ch=lo", allocate negation aux.
            val negInd = allocBool("__setlex_${label}_neg_${set.name}_${elem}_${factors.size}")
            factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                com.eignex.klause.solver.Lit.make(ind, true),
                com.eignex.klause.solver.Lit.make(negInd, true),
            )))
            factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                com.eignex.klause.solver.Lit.make(ind, false),
                com.eignex.klause.solver.Lit.make(negInd, false),
            )))
            factors.add(ReifiedLinear(negInd,
                coeffs = intArrayOf(1), vars = intArrayOf(ch), op = LinearOp.EQ, bound = lo))
        }
        val maxVar = allocInt("__setlex_${label}max_${set.name}_${factors.size}", lo, hi)
        if (channels.isEmpty()) {
            // Empty set universe: max is lo by definition.
            factors.add(Linear(intArrayOf(1), intArrayOf(maxVar), LinearOp.EQ, lo))
        } else {
            factors.add(com.eignex.klause.solver.factor.ArrayMinMax(maxVar, channels, max = true))
        }
        return maxVar
    }
    val xmax = maxOf(s, "x")
    val ymax = maxOf(t, "y")
    // Allocate b[i] bools for i = 0..U.size-1.
    val b = IntArray(universe.size) { allocBool("__setlex_b_${s.name}_${t.name}_${universe[it]}_${factors.size}") }
    val emptyLit = com.eignex.klause.solver.Lit
    // Lookup S/T indicator (or null if elem not in that set's universe).
    fun indicator(set: SetVarLayout, elem: Int): Int? {
        val idx = set.elements.binarySearch(elem)
        return if (idx < 0) null else set.indicatorBoolIds[idx]
    }
    // Top of table: b[last] = (S_has(last) → T_has(last))  ≡  (¬S_has ∨ T_has).
    run {
        val last = universe.size - 1
        val sLit = indicator(s, universe[last])
        val tLit = indicator(t, universe[last])
        val sHas = if (sLit != null) com.eignex.klause.solver.Lit.make(sLit, true) else null
        val tHas = if (tLit != null) com.eignex.klause.solver.Lit.make(tLit, true) else null
        when {
            sHas == null && tHas == null -> {
                // Neither set has the element: b[last] = true trivially.
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(b[last], true))))
            }
            sHas == null -> {
                // S can't have it: implication is vacuously true → b[last] = true.
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(b[last], true))))
            }
            tHas == null -> {
                // T can't have it: b[last] = ¬S_has.
                // b ↔ ¬s_has: (b ∨ s_has) ∧ (¬b ∨ ¬s_has)
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(b[last], true), sHas)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(b[last], false), emptyLit.negate(sHas))))
            }
            else -> {
                // b ↔ (¬s_has ∨ t_has):
                // (¬b ∨ ¬s_has ∨ t_has) ∧ (b ∨ s_has) ∧ (b ∨ ¬t_has)
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(b[last], false), emptyLit.negate(sHas), tHas)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(b[last], true), sHas)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(b[last], true), emptyLit.negate(tHas))))
            }
        }
    }
    // Inner positions i = u-2 down to 0.
    for (i in universe.size - 2 downTo 0) {
        val elem = universe[i]
        val sLit = indicator(s, elem)
        val tLit = indicator(t, elem)
        // Allocate the (xmax < elem) and (ymax > elem) reified aux bools as needed.
        val xmaxLessLit: Int by lazy {
            val aux = allocBool("__setlex_xmaxlt_${elem}_${factors.size}")
            // aux ↔ (xmax ≤ elem-1)  ≡  Linear coeffs [1] vars [xmax] op LE bound elem-1
            factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(xmax), LinearOp.LE, elem - 1))
            emptyLit.make(aux, true)
        }
        val ymaxGreaterLit: Int by lazy {
            val aux = allocBool("__setlex_ymaxgt_${elem}_${factors.size}")
            // aux ↔ (ymax ≥ elem+1)  ≡  Linear coeffs [-1] vars [ymax] op LE bound -(elem+1)
            factors.add(ReifiedLinear(aux, intArrayOf(-1), intArrayOf(ymax), LinearOp.LE, -(elem + 1)))
            emptyLit.make(aux, true)
        }
        val bi = emptyLit.make(b[i], true)
        val nbi = emptyLit.make(b[i], false)
        val bn = emptyLit.make(b[i + 1], true)
        val nbn = emptyLit.make(b[i + 1], false)
        // Encode the 4-case truth table as case-conditioned biconditionals.
        // Effectively: b[i] = (S_has ⊕ T_has) ? (S_has ? ymax>elem : xmax<elem) : b[i+1].
        // For each combo of (S_has, T_has) ∈ {present, absent}, post the rule.
        val sHas = if (sLit != null) emptyLit.make(sLit, true) else null
        val tHas = if (tLit != null) emptyLit.make(tLit, true) else null
        when {
            sHas == null && tHas == null -> {
                // (0,0) → b[i] = b[i+1]
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(nbi, bn)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(bi, nbn)))
            }
            sHas == null -> {
                // S can't have elem: case is (0, T_has). T_has=0 → b[i]=b[i+1]; T_has=1 → b[i]=(xmax<elem).
                // Implications guarded by tHas/¬tHas:
                // (¬tHas → b[i]=b[i+1]): (tHas ∨ ¬b[i] ∨ b[i+1]) ∧ (tHas ∨ b[i] ∨ ¬b[i+1])
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(tHas!!, nbi, bn)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(tHas, bi, nbn)))
                // (tHas → b[i]=xmaxLess): (¬tHas ∨ ¬b[i] ∨ xmaxLessLit) ∧ (¬tHas ∨ b[i] ∨ ¬xmaxLessLit)
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(tHas), nbi, xmaxLessLit)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(tHas), bi, emptyLit.negate(xmaxLessLit))))
            }
            tHas == null -> {
                // T can't have elem: case is (S_has, 0). S_has=0 → b[i]=b[i+1]; S_has=1 → b[i]=(ymax>elem).
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(sHas, nbi, bn)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(sHas, bi, nbn)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(sHas), nbi, ymaxGreaterLit)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(sHas), bi, emptyLit.negate(ymaxGreaterLit))))
            }
            else -> {
                // Both indicators exist. Four sub-cases by (S_has, T_has):
                // (0,0): both absent → b[i]=b[i+1]  | guard: ¬S ∧ ¬T
                // (0,1): only T     → b[i]=xmax<i  | guard: ¬S ∧  T
                // (1,0): only S     → b[i]=ymax>i  | guard:  S ∧ ¬T
                // (1,1): both       → b[i]=b[i+1]  | guard:  S ∧  T
                // Implication (¬S ∧ ¬T) → (b[i] ↔ b[i+1]):
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(sHas, tHas, nbi, bn)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(sHas, tHas, bi, nbn)))
                // (¬S ∧ T) → (b[i] ↔ xmaxLess):
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(sHas, emptyLit.negate(tHas), nbi, xmaxLessLit)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(sHas, emptyLit.negate(tHas), bi, emptyLit.negate(xmaxLessLit))))
                // (S ∧ ¬T) → (b[i] ↔ ymaxGreater):
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(sHas), tHas, nbi, ymaxGreaterLit)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(sHas), tHas, bi, emptyLit.negate(ymaxGreaterLit))))
                // (S ∧ T) → (b[i] ↔ b[i+1]):
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(sHas), emptyLit.negate(tHas), nbi, bn)))
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(sHas), emptyLit.negate(tHas), bi, nbn)))
            }
        }
    }
    // Verdict: r ↔ b[0] for set_le. For set_lt, r ↔ (b[0] ∧ ¬(s = t)).
    val verdict = b[0]
    if (!strict && !reified) {
        // Non-reified, non-strict: assert b[0] = true.
        factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(verdict, true))))
    } else if (!strict && reified) {
        val r = resolveBoolLit(c.args[2])
        // r ↔ verdict
        factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(r, emptyLit.make(verdict, false))))
        factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(r), emptyLit.make(verdict, true))))
    } else {
        // Strict variants: combine verdict with set-inequality.
        val eqAux = allocBool("__setlex_eq_${s.name}_${t.name}_${factors.size}")
        emitSetEqChannel(s, t, emptyLit.make(eqAux, true))
        val strictAux = allocBool("__setlex_strict_${factors.size}")
        // strict ↔ verdict ∧ ¬eq
        // (¬strict ∨ verdict) ∧ (¬strict ∨ ¬eq) ∧ (strict ∨ ¬verdict ∨ eq)
        factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(strictAux, false), emptyLit.make(verdict, true))))
        factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(strictAux, false), emptyLit.make(eqAux, false))))
        factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(strictAux, true), emptyLit.make(verdict, false), emptyLit.make(eqAux, true))))
        if (reified) {
            val r = resolveBoolLit(c.args[2])
            factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(r, emptyLit.make(strictAux, false))))
            factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.negate(r), emptyLit.make(strictAux, true))))
        } else {
            factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(emptyLit.make(strictAux, true))))
        }
    }
}

/**
 * `array_set_element(x, ys, z)` — `z = ys[x]` where `ys` is an array of *constant* sets.
 * For each universe element `k` of `z`, the indicator `z.ind[k]` holds iff
 * `x ∈ { i : k ∈ ys[i] }`. Encoded as one reified `set_in` per element with a constant
 * "elements-of-x-that-pick-k" mask.
 *
 * `array_var_set_element(x, ys, z)` — `z = ys[x]` where `ys` is an array of *var* sets.
 * For each `i` and each element `k`, post `x=i → (z.ind[k] ↔ ys[i].ind[k])` as a
 * reified clause guarded by `(x = i)`. O(|x.domain| × |universe|) clauses.
 */
internal fun FlatZincCompiler.emitArraySetElement(c: FznConstraint, varArray: Boolean) {
    require(c.args.size == 3)
    val x = resolveIntVar(c.args[0])
    val z = resolveSetVar(c.args[2])
    val xDom = intDomains[x]
    if (varArray) {
        val ys = resolveSetVarArray(c.args[1])
        // ys is 1-indexed in FZN by convention; xDom.min should be 1 unless declared otherwise.
        // Channel each indicator under each candidate value.
        for (vi in xDom.min..xDom.max) {
            val yIdx = vi - xDom.min
            if (yIdx !in ys.indices) {
                // x can't realistically take value vi (no corresponding ys entry); forbid.
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(/* unsat sentinel */)))
                continue
            }
            val ySet = ys[yIdx]
            // x_eq_vi reified bool.
            val xEqAux = allocBool("__arraysetelem_xeq_${vi}_${factors.size}")
            factors.add(ReifiedLinear(
                xEqAux, intArrayOf(1), intArrayOf(x), LinearOp.EQ, vi))
            // For each universe element of z, channel through ySet.
            for (zi in z.elements.indices) {
                val k = z.elements[zi]
                val zBit = z.indicatorBoolIds[zi]
                val yIdxInSet = ySet.elements.binarySearch(k)
                if (yIdxInSet < 0) {
                    // ySet's universe doesn't contain k → if x=vi then z.ind[k]=false.
                    factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                        com.eignex.klause.solver.Lit.make(xEqAux, false),
                        com.eignex.klause.solver.Lit.make(zBit, false),
                    )))
                } else {
                    val yBit = ySet.indicatorBoolIds[yIdxInSet]
                    // (x=vi) → (z.ind[k] ↔ yBit)
                    // (¬xEq ∨ ¬z.ind ∨ yBit) ∧ (¬xEq ∨ z.ind ∨ ¬yBit)
                    factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                        com.eignex.klause.solver.Lit.make(xEqAux, false),
                        com.eignex.klause.solver.Lit.make(zBit, false),
                        com.eignex.klause.solver.Lit.make(yBit, true),
                    )))
                    factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                        com.eignex.klause.solver.Lit.make(xEqAux, false),
                        com.eignex.klause.solver.Lit.make(zBit, true),
                        com.eignex.klause.solver.Lit.make(yBit, false),
                    )))
                }
            }
        }
        return
    }
    // Constant-set array: extract each row as IntArray (sorted ascending), then per
    // universe element of z, build the "x values that pick k" mask and post a reified
    // set_in over x.
    val arrName = (c.args[1] as? FznExpr.Ident)?.name
        ?: failHere("array_set_element: second arg must be an array identifier")
    val arr = arrays[arrName] ?: failHere("array_set_element: unknown array `$arrName`")
    val rows: List<IntArray> = when (arr) {
        is FlatZincArray.IntSetParam -> arr.values
        else -> failHere("array_set_element: expected array of set-of-int param, got ${arr::class.simpleName}")
    }
    // x is 1-indexed by FZN convention; xDom describes its valid range. For each
    // universe element k of z, the picking constraint is z.ind[k] ↔ x ∈ mask_k.
    for (zi in z.elements.indices) {
        val k = z.elements[zi]
        val zBit = z.indicatorBoolIds[zi]
        // Collect the set of x-values (1-indexed) for which k ∈ rows[x-1].
        val pick = ArrayList<Int>()
        for ((rowIdx, row) in rows.withIndex()) {
            if (row.binarySearch(k) >= 0) pick.add(rowIdx + 1)
        }
        when {
            pick.isEmpty() -> {
                // No x value leads to z containing k → force z.ind[k] = false.
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                    com.eignex.klause.solver.Lit.make(zBit, false))))
            }
            pick.size == rows.size -> {
                // Every x value gives z containing k → force z.ind[k] = true.
                factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                    com.eignex.klause.solver.Lit.make(zBit, true))))
            }
            else -> {
                // Reified disjunction: zBit ↔ ⋁ (x = pick[j]) for j.
                // For each pick value, alloc a reified bool xEq_v ↔ (x = v).
                val orLits = IntArray(pick.size)
                for ((idx, v) in pick.withIndex()) {
                    val aux = allocBool("__aseelem_${arrName}_${k}_x${v}_${factors.size}")
                    factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(x), LinearOp.EQ, v))
                    orLits[idx] = com.eignex.klause.solver.Lit.make(aux, true)
                }
                // zBit ↔ ⋁ orLits
                // (¬zBit ∨ ⋁ orLits) ∧ for each orLit: (zBit ∨ ¬orLit)
                factors.add(com.eignex.klause.solver.factor.Clause(
                    intArrayOf(com.eignex.klause.solver.Lit.make(zBit, false)) + orLits))
                for (orLit in orLits) {
                    factors.add(com.eignex.klause.solver.factor.Clause(intArrayOf(
                        com.eignex.klause.solver.Lit.make(zBit, true),
                        com.eignex.klause.solver.Lit.negate(orLit))))
                }
            }
        }
    }
}

/** `all_disjoint(arr)` — every pair of sets in [arr] has empty intersection. For each
 *  pair (Sᵢ, Sⱼ) and each element `e` shared between their universes, post the binary
 *  mutex clause `¬Sᵢ[e] ∨ ¬Sⱼ[e]`. */
internal fun FlatZincCompiler.emitAllDisjoint(c: FznConstraint) {
    require(c.args.size == 1)
    val sets = resolveSetVarArray(c.args[0])
    for (i in sets.indices) for (j in i + 1 until sets.size) {
        val a = sets[i]; val b = sets[j]
        for (ai in a.elements.indices) {
            val bi = b.elements.binarySearch(a.elements[ai])
            if (bi >= 0) {
                factors.add(Clause(intArrayOf(
                    Lit.make(a.indicatorBoolIds[ai], false),
                    Lit.make(b.indicatorBoolIds[bi], false),
                )))
            }
        }
    }
}

/** `set_partition_into(arr, U)` — sets in [arr] are pairwise disjoint AND their union
 *  equals U. Reuses `emitAllDisjoint`'s pairwise mutex; adds for each `e` in U's universe
 *  the clause `Uₑ ↔ ⋁ᵢ Sᵢ[e]` plus the universe-mismatch exclusions (elements outside
 *  U but in some Sᵢ's universe must be absent from Sᵢ).
 *
 *  When `U` is a set literal (not a var), treats it as a fully-determined universe: every
 *  element of `U` must be covered by exactly one set; elements outside `U` can't appear in
 *  any set. */
internal fun FlatZincCompiler.emitSetPartitionInto(c: FznConstraint) {
    require(c.args.size == 2)
    val sets = resolveSetVarArray(c.args[0])
    emitAllDisjoint(FznConstraint("all_disjoint", listOf(c.args[0]), emptyList()))
    val uExpr = c.args[1]
    val universe: IntArray = if (uExpr is FznExpr.Ident && setVarsByName.containsKey(uExpr.name)) {
        // U is a set var: cover & disjointness over U's universe; per-element `Uₑ ↔ ⋁ Sᵢ[e]`.
        val u = setVarsByName.getValue(uExpr.name)
        for (i in u.elements.indices) {
            val e = u.elements[i]
            val uBit = u.indicatorBoolIds[i]
            val parts = ArrayList<Int>()
            for (s in sets) {
                val si = s.elements.binarySearch(e)
                if (si >= 0) parts += Lit.make(s.indicatorBoolIds[si], true)
            }
            if (parts.isEmpty()) {
                // No set can contain e; force Uₑ = false.
                factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            } else {
                // (¬Uₑ ∨ S₁[e] ∨ ... ∨ Sₙ[e])
                factors.add(Clause(intArrayOf(Lit.make(uBit, false)) + parts.toIntArray()))
                // (Sᵢ[e] → Uₑ) for each part.
                for (p in parts) factors.add(Clause(intArrayOf(Lit.negate(p), Lit.make(uBit, true))))
            }
        }
        u.elements
    } else {
        // U is a constant set literal — cover exactly its elements; forbid extras.
        val uniq = resolveSetLiteral(uExpr)
        for (e in uniq) {
            // ⋁ᵢ Sᵢ[e] = true (since e must be in the partition).
            val parts = ArrayList<Int>()
            for (s in sets) {
                val si = s.elements.binarySearch(e)
                if (si >= 0) parts += Lit.make(s.indicatorBoolIds[si], true)
            }
            if (parts.isEmpty()) {
                failHere("set_partition_into: element $e in U has no set containing it")
            }
            factors.add(Clause(parts.toIntArray()))
        }
        uniq
    }
    // Elements in some Sᵢ's universe but not in U must be excluded from Sᵢ.
    for (s in sets) {
        for (i in s.elements.indices) {
            if (universe.binarySearch(s.elements[i]) < 0) {
                factors.add(Clause(intArrayOf(Lit.make(s.indicatorBoolIds[i], false))))
            }
        }
    }
}

/** Channel: `eqLit ↔ (S = T)`. Shared by `set_eq_reif` and `set_ne_reif`. Same lowering
 *  as `emitSetEq(reified=true)` but parameterised by the channel literal instead of
 *  pulling it from the constraint args. */
internal fun FlatZincCompiler.emitSetEqChannel(s: SetVarLayout, t: SetVarLayout, r: Int) {
    val auxes = ArrayList<Int>()
    val emitEqAux: (Int, Int, Int) -> Unit = { sBit, tBit, aux ->
        factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(tBit, true), Lit.make(aux, true))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(tBit, false), Lit.make(aux, false))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, true), Lit.make(aux, false))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, false), Lit.make(aux, true))))
    }
    for (i in s.elements.indices) {
        val sBit = s.indicatorBoolIds[i]
        val tIdx = t.elements.binarySearch(s.elements[i])
        val aux = allocBool("__eq_aux_${s.name}_${t.name}_${s.elements[i]}")
        auxes.add(Lit.make(aux, true))
        if (tIdx < 0) {
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(sBit, true))))
        } else {
            emitEqAux(sBit, t.indicatorBoolIds[tIdx], aux)
        }
    }
    for (i in t.elements.indices) {
        if (s.elements.binarySearch(t.elements[i]) < 0) {
            val tBit = t.indicatorBoolIds[i]
            val aux = allocBool("__eq_aux_${s.name}_${t.name}_only_t_${t.elements[i]}")
            auxes.add(Lit.make(aux, true))
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(tBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(tBit, true))))
        }
    }
    reifyAndOfLits(auxes.toIntArray(), r)
}

/** `set_card(S, n)`. Σ indicator_S[e] = n. n can be a constant or an int var; lowers to a
 *  pseudo-Boolean linear constraint either way. */
internal fun FlatZincCompiler.emitSetCard(c: FznConstraint) {
    require(c.args.size == 2)
    val s = resolveSetVar(c.args[0])
    val nExpr = c.args[1]
    when (nExpr) {
        is FznExpr.IntLit -> {
            // Σ Sᵢ = const → bool_lin_eq. PseudoBoolean takes literals (Lit-encoded), not
            // raw var ids; wrap each indicator as a positive literal.
            val coeffs = IntArray(s.indicatorBoolIds.size) { 1 }
            val lits = IntArray(s.indicatorBoolIds.size) { Lit.make(s.indicatorBoolIds[it], true) }
            factors.add(com.eignex.klause.solver.factor.PseudoBoolean(
                coeffs, lits, com.eignex.klause.ast.PbOp.EQ, nExpr.value.toInt()
            ))
        }
        is FznExpr.Ident -> {
            // Σ Sᵢ = nVar → int_lin_eq([1...1, -1], [indicator channel ints..., nVar], 0).
            // We channel each bool indicator to a 0/1 int, then post the linear.
            val nVar = resolveIntVar(nExpr)
            val channels = IntArray(s.indicatorBoolIds.size) { i ->
                val ch = allocInt("__card_chan_${s.name}_${s.elements[i]}", 0, 1)
                factors.add(ReifiedLinear(
                    auxBoolVar = s.indicatorBoolIds[i],
                    coeffs = intArrayOf(1), vars = intArrayOf(ch),
                    op = LinearOp.EQ, bound = 1,
                ))
                ch
            }
            val coefs = IntArray(channels.size + 1) { if (it < channels.size) 1 else -1 }
            val vars = IntArray(channels.size + 1) { if (it < channels.size) channels[it] else nVar }
            factors.add(Linear(coefs, vars, LinearOp.EQ, 0))
        }
        else -> failHere("set_card: second arg must be int var or constant, got ${nExpr::class.simpleName}")
    }
}

/** `set_union(S, T, U)`. For each element of U's universe: `Uᵢ ↔ (Sᵢ ∨ Tᵢ)`. Elements
 *  outside U's universe but in S or T's must not be in S/T. */
internal fun FlatZincCompiler.emitSetUnion(c: FznConstraint) {
    require(c.args.size == 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.elements.binarySearch(e)
        val tIdx = t.elements.binarySearch(e)
        when {
            sIdx >= 0 && tIdx >= 0 -> {
                val sBit = s.indicatorBoolIds[sIdx]
                val tBit = t.indicatorBoolIds[tIdx]
                // Uᵢ ↔ (Sᵢ ∨ Tᵢ): three clauses.
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(tBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true), Lit.make(tBit, true))))
            }
            sIdx >= 0 -> {
                // Uᵢ ↔ Sᵢ
                val sBit = s.indicatorBoolIds[sIdx]
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(uBit, false))))
            }
            tIdx >= 0 -> {
                val tBit = t.indicatorBoolIds[tIdx]
                factors.add(Clause(intArrayOf(Lit.make(tBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(tBit, true), Lit.make(uBit, false))))
            }
            else -> {
                // Element only in U's universe — neither S nor T can contribute, so Uᵢ = false.
                factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            }
        }
    }
    // Elements in S or T's universe but not U's must be excluded from S/T.
    for (i in s.elements.indices) {
        if (u.elements.binarySearch(s.elements[i]) < 0) {
            factors.add(Clause(intArrayOf(Lit.make(s.indicatorBoolIds[i], false))))
        }
    }
    for (i in t.elements.indices) {
        if (u.elements.binarySearch(t.elements[i]) < 0) {
            factors.add(Clause(intArrayOf(Lit.make(t.indicatorBoolIds[i], false))))
        }
    }
}

/** `set_intersect(S, T, U)`. For each element of U's universe: `Uᵢ ↔ (Sᵢ ∧ Tᵢ)`. Elements
 *  outside U but in both S and T's universes must not be in both (or unconstrained — we
 *  leave them unconstrained, since intersection only needs U to track the conjunction). */
internal fun FlatZincCompiler.emitSetIntersect(c: FznConstraint) {
    require(c.args.size == 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.elements.binarySearch(e)
        val tIdx = t.elements.binarySearch(e)
        if (sIdx >= 0 && tIdx >= 0) {
            val sBit = s.indicatorBoolIds[sIdx]
            val tBit = t.indicatorBoolIds[tIdx]
            // Uᵢ ↔ (Sᵢ ∧ Tᵢ): three clauses.
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(tBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, false), Lit.make(uBit, true))))
        } else {
            // Element not in both S and T's universes → can't be in intersection.
            factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
        }
    }
}

/** `set_diff(S, T, U)`. For each element of U's universe: `Uᵢ ↔ (Sᵢ ∧ ¬Tᵢ)`. */
internal fun FlatZincCompiler.emitSetDiff(c: FznConstraint) {
    require(c.args.size == 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.elements.binarySearch(e)
        if (sIdx < 0) {
            // Element not in S → can't be in S \ T.
            factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            continue
        }
        val sBit = s.indicatorBoolIds[sIdx]
        val tIdx = t.elements.binarySearch(e)
        if (tIdx < 0) {
            // Element in S but not in T's universe → Uᵢ ↔ Sᵢ.
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(uBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(uBit, false))))
        } else {
            val tBit = t.indicatorBoolIds[tIdx]
            // Uᵢ ↔ (Sᵢ ∧ ¬Tᵢ): three clauses.
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(tBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, true), Lit.make(uBit, true))))
        }
    }
}

