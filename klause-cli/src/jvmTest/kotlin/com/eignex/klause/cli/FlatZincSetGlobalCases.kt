package com.eignex.klause.cli

internal val flatZincSetGlobalCases: List<Triple<String, String, String?>> = listOf(
    Triple("set_in", "set_in(x,{1})", "Cardinality"),
    Triple("set_in_reif", "set_in_reif(1,{1},b); constraint bool2int(b,x)", null),
    Triple("set_subset", "set_subset({1},s)$SET_MEMBERSHIP", null),
    Triple("set_eq", "set_eq(s,{1})$SET_MEMBERSHIP", null),
    Triple("set_card", "set_card({1},x)", null),
    Triple("set_union", "set_union(s,{}, {1})$SET_MEMBERSHIP", null),
    Triple("set_intersect", "set_intersect(s,{1},{1})$SET_MEMBERSHIP", null),
    Triple("set_diff", "set_diff(s,{}, {1})$SET_MEMBERSHIP", null),
    Triple("set_subset_reif", "set_subset_reif({1},{1},b); constraint bool2int(b,x)", null),
    Triple("set_eq_reif", "set_eq_reif({1},{1},b); constraint bool2int(b,x)", null),
    Triple("set_ne", "set_ne(s,{})$SET_MEMBERSHIP", null),
    Triple("set_ne_reif", "set_ne_reif({1},{},b); constraint bool2int(b,x)", null),
    Triple("set_le", "set_le({1},s)$SET_MEMBERSHIP", null),
    Triple("set_le_reif", "set_le_reif({1},{1},b); constraint bool2int(b,x)", null),
    Triple("set_lt", "set_lt({},s)$SET_MEMBERSHIP", null),
    Triple("set_lt_reif", "set_lt_reif({},{1},b); constraint bool2int(b,x)", null),
    Triple("array_set_element", "array_set_element(x,[{1},{}],{1})", "Element"),
    Triple("array_var_set_element", "array_var_set_element(x,[s,t],{1}); " +
        "constraint set_eq(s,{1}); constraint set_eq(t,{})", "Element"),
    Triple("all_disjoint", "all_disjoint([s,{1}]); constraint set_in_reif(1,s,b); " +
        "constraint bool_not(b,c); constraint bool2int(c,x)", null),
    Triple("set_partition_into", "set_partition_into([s],{1})$SET_MEMBERSHIP", null),
)

private const val SET_MEMBERSHIP = "; constraint set_in_reif(1,s,b); constraint bool2int(b,x)"
