package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.NValue

internal fun FlatZincCompiler.processConstraint(c: FznConstraint) = when (c.name) {
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

    "bool_eq_reif" -> emitBoolCmpReif(c, BoolCmpOp.EQ)

    "bool_le_reif" -> emitBoolCmpReif(c, BoolCmpOp.LE)

    "bool_lt_reif" -> emitBoolCmpReif(c, BoolCmpOp.LT)

    "int_le", "int_lt", "int_eq", "int_ne", "int_ge", "int_gt" -> emitIntCmp(c)

    "int_eq_reif", "int_ne_reif", "int_le_reif", "int_lt_reif",
    "int_ge_reif", "int_gt_reif",
    -> emitIntCmpReif(c)

    "int_lin_le", "int_lin_eq", "int_lin_ne" -> emitIntLinear(c, reified = false)

    "int_lin_le_reif", "int_lin_eq_reif", "int_lin_ne_reif" -> emitIntLinear(c, reified = true)

    "bool_lin_le", "bool_lin_eq" -> emitBoolLinear(c)

    "float_lin_le", "float_lin_eq", "float_lin_ne" -> emitFloatLinear(c, reified = false)

    "float_lin_le_reif", "float_lin_eq_reif", "float_lin_ne_reif" -> emitFloatLinear(c, reified = true)

    "int2float" -> emitInt2Float(c)

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

    "float_div" -> emitFloatTimes(
        FznConstraint(
            name = "float_times",
            args = listOf(c.args[1], c.args[2], c.args[0]),
            annotations = c.annotations,
        ),
    )

    "all_different_int", "klause_all_different_int" -> emitAllDifferent(c)

    "alldifferent_except_0", "fzn_alldifferent_except_0", "klause_alldifferent_except_0" ->
        emitAllDifferentExceptZero(c)

    "all_equal_int", "fzn_all_equal_int", "klause_all_equal_int" -> emitAllEqual(c)

    "member_int", "fzn_member_int", "klause_member_int" -> emitMember(c)

    "sort", "fzn_sort", "klause_sort" -> emitSort(c)

    "symmetric_all_different", "fzn_symmetric_all_different", "klause_symmetric_all_different" ->
        emitSymmetricAllDifferent(c)

    "inverse", "fzn_inverse", "klause_inverse" -> emitInverse(c, withOffsets = false)

    "inverse_offsets", "fzn_inverse_offsets" -> emitInverse(c, withOffsets = true)

    "nvalue", "fzn_nvalue", "klause_nvalue" -> emitNValue(c, NValue.Mode.Eq)

    "atleast_nvalues", "fzn_atleast_nvalues" -> emitNValue(c, NValue.Mode.AtLeast)

    "atmost_nvalues", "fzn_atmost_nvalues" -> emitNValue(c, NValue.Mode.AtMost)

    "lex_less_int", "fzn_lex_less_int", "klause_lex_less_int" -> emitLexLess(c, strict = true)

    "lex_lesseq_int", "fzn_lex_lesseq_int", "klause_lex_lesseq_int" -> emitLexLess(c, strict = false)

    "value_precede_int", "fzn_value_precede_int", "klause_value_precede_int" -> emitValuePrecede(c)

    "value_precede_chain_int", "fzn_value_precede_chain_int", "klause_value_precede_chain_int" -> emitValuePrecedeChain(
        c,
    )

    "diffn", "fzn_diffn", "klause_diffn" -> emitDiffn(c, nonStrict = false)

    "diffn_nonstrict", "fzn_diffn_nonstrict", "klause_diffn_nonstrict" -> emitDiffn(c, nonStrict = true)

    "table_int", "fzn_table_int", "klause_table_int" -> emitTable(c)

    "regular", "fzn_regular", "klause_regular" -> emitRegular(c)

    "mdd", "fzn_mdd", "klause_mdd" -> emitMdd(c)

    "circuit", "fzn_circuit", "klause_circuit" -> emitCircuit(c, sub = false)

    "subcircuit", "fzn_subcircuit", "klause_subcircuit" -> emitCircuit(c, sub = true)

    "cumulative", "fzn_cumulative" -> emitCumulative(c)

    "sliding_sum", "fzn_sliding_sum" -> emitSlidingSum(c)

    "disjunctive", "fzn_disjunctive",
    "disjunctive_strict", "fzn_disjunctive_strict",
    -> emitDisjunctive(c)

    "int_times" -> emitIntTimes(c)

    "int_plus" -> emitIntPlus(c)

    "int_minus" -> emitIntMinus(c)

    "int_abs" -> emitIntAbs(c)

    "int_max" -> emitIntMaxMin(c, max = true)

    "int_min" -> emitIntMaxMin(c, max = false)

    "int_div" -> emitIntDiv(c)

    "int_mod" -> emitIntMod(c)

    "array_int_element" -> emitArrayIntElement(c, varArray = false)

    "array_var_int_element" -> emitArrayIntElement(c, varArray = true)

    "gecode_int_element" -> emitGecodeIntElement(c)

    "array_bool_element" -> emitArrayBoolElement(c, varArray = false)

    "array_var_bool_element" -> emitArrayBoolElement(c, varArray = true)

    "at_least_int", "fzn_at_least_int" -> emitAtLeast(c)

    "at_most_int", "fzn_at_most_int" -> emitAtMost(c)

    "count_eq", "fzn_count_eq", "klause_count_eq" -> emitCountEq(c)

    "among", "fzn_among" -> emitAmong(c)

    "global_cardinality", "fzn_global_cardinality" -> emitGcc(c, GccVariant.STANDARD)

    "global_cardinality_closed", "fzn_global_cardinality_closed" -> emitGcc(c, GccVariant.CLOSED)

    "global_cardinality_low_up", "fzn_global_cardinality_low_up" -> emitGcc(c, GccVariant.LOW_UP)

    "global_cardinality_low_up_closed", "fzn_global_cardinality_low_up_closed" ->
        emitGcc(c, GccVariant.LOW_UP_CLOSED)

    "distribute", "fzn_distribute" -> emitDistribute(c)

    "increasing_int", "fzn_increasing_int", "klause_increasing_int" -> emitMonotone(c, MonotoneOp.INCREASING)

    "decreasing_int", "fzn_decreasing_int" -> emitMonotone(c, MonotoneOp.DECREASING)

    "strictly_increasing_int", "fzn_strictly_increasing_int", "klause_strictly_increasing_int" ->
        emitMonotone(c, MonotoneOp.STRICTLY_INCREASING)

    "strictly_decreasing_int", "fzn_strictly_decreasing_int" -> emitMonotone(c, MonotoneOp.STRICTLY_DECREASING)

    "array_int_maximum", "fzn_array_int_maximum",
    "maximum_int", "fzn_maximum_int",
    -> emitArrayMinMax(c, max = true)

    "array_int_minimum", "fzn_array_int_minimum",
    "minimum_int", "fzn_minimum_int",
    -> emitArrayMinMax(c, max = false)

    "exactly_int", "fzn_exactly_int" -> emitExactly(c)

    "increasing_bool", "fzn_increasing_bool" -> emitMonotoneBool(c, MonotoneOp.INCREASING)

    "decreasing_bool", "fzn_decreasing_bool" -> emitMonotoneBool(c, MonotoneOp.DECREASING)

    "strictly_increasing_bool", "fzn_strictly_increasing_bool" -> emitMonotoneBool(c, MonotoneOp.STRICTLY_INCREASING)

    "strictly_decreasing_bool", "fzn_strictly_decreasing_bool" -> emitMonotoneBool(c, MonotoneOp.STRICTLY_DECREASING)

    "lex_less_bool", "fzn_lex_less_bool" -> emitLexLessBool(c, strict = true)

    "lex_lesseq_bool", "fzn_lex_lesseq_bool" -> emitLexLessBool(c, strict = false)

    "table_bool", "fzn_table_bool" -> emitTableBool(c)

    "symmetry_breaking_constraint", "fzn_symmetry_breaking_constraint",
    "redundant_constraint", "fzn_redundant_constraint",
    -> emitAnnotationConstraint(c)

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
