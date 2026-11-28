% FlatZinc predicates klause supports natively. A bare declaration (no body) is emitted
% straight to FlatZinc; anything not listed falls back to MiniZinc's standard decomposition.

% Boolean primitives.
predicate bool_clause(array[int] of var bool: as,
                      array[int] of var bool: bs);

predicate array_bool_and(array[int] of var bool: as, var bool: r);
predicate array_bool_or(array[int] of var bool: as, var bool: r);

% Integer linear (reified + non-reified).
predicate int_lin_le(array[int] of int: as,
                     array[int] of var int: bs,
                     int: c);
predicate int_lin_eq(array[int] of int: as,
                     array[int] of var int: bs,
                     int: c);
predicate int_lin_ne(array[int] of int: as,
                     array[int] of var int: bs,
                     int: c);

predicate int_lin_le_reif(array[int] of int: as,
                          array[int] of var int: bs,
                          int: c,
                          var bool: r);
predicate int_lin_eq_reif(array[int] of int: as,
                          array[int] of var int: bs,
                          int: c,
                          var bool: r);
predicate int_lin_ne_reif(array[int] of int: as,
                          array[int] of var int: bs,
                          int: c,
                          var bool: r);

% Boolean linear (pseudo-Boolean).
predicate bool_lin_le(array[int] of int: as,
                      array[int] of var bool: bs,
                      int: c);
predicate bool_lin_eq(array[int] of int: as,
                      array[int] of var bool: bs,
                      int: c);

% Float linear.
predicate float_lin_le(array[int] of float: as,
                       array[int] of var float: bs,
                       float: c);
predicate float_lin_eq(array[int] of float: as,
                       array[int] of var float: bs,
                       float: c);
predicate float_lin_ne(array[int] of float: as,
                       array[int] of var float: bs,
                       float: c);

predicate float_lin_le_reif(array[int] of float: as,
                            array[int] of var float: bs,
                            float: c,
                            var bool: r);
predicate float_lin_eq_reif(array[int] of float: as,
                            array[int] of var float: bs,
                            float: c,
                            var bool: r);
predicate float_lin_ne_reif(array[int] of float: as,
                            array[int] of var float: bs,
                            float: c,
                            var bool: r);

% Multiplication.
predicate int_times(var int: a, var int: b, var int: c);
predicate klause_int_pow(var int: a, int: b, var int: c);
predicate klause_int_pow_reif(var int: a, int: b, var int: c, var bool: r) =
  let {
    var compute_pow_bounds(a, b): value :: is_defined_var;
    constraint klause_int_pow(a, b, value) :: defines_var(value);
  } in r <-> value = c;

% int → float coercion.
predicate int2float(var int: x, var float: y);

% Float comparisons and arithmetic, declared native so MiniZinc skips its float decomposition.
predicate float_eq(var float: a, var float: b);
predicate float_eq_reif(var float: a, var float: b, var bool: r);
predicate float_le(var float: a, var float: b);
predicate float_le_reif(var float: a, var float: b, var bool: r);
predicate float_lt(var float: a, var float: b);
predicate float_lt_reif(var float: a, var float: b, var bool: r);
predicate float_ne(var float: a, var float: b);
predicate float_ne_reif(var float: a, var float: b, var bool: r);
predicate float_lin_lt(array[int] of float: as, array[int] of var float: bs, float: c);
predicate float_lin_lt_reif(array[int] of float: as, array[int] of var float: bs,
                            float: c, var bool: r);
predicate float_min(var float: a, var float: b, var float: c);
predicate float_max(var float: a, var float: b, var float: c);
predicate float_times(var float: a, var float: b, var float: c);
predicate float_pow(var float: a, var float: b, var float: c);
predicate float_div(var float: a, var float: b, var float: c);

% Globals with native factors.
predicate all_different_int(array[int] of var int: x);
predicate all_equal_int(array[int] of var int: x);
predicate symmetric_all_different(array[int] of var int: x);
predicate member_int(array[int] of var int: x, var int: y);
predicate sort(array[int] of var int: x, array[int] of var int: y);


% Cardinality.
predicate global_cardinality(array[int] of var int: x,
                             array[int] of int: cover,
                             array[int] of var int: counts);
predicate global_cardinality_closed(array[int] of var int: x,
                                    array[int] of int: cover,
                                    array[int] of var int: counts);
predicate global_cardinality_low_up(array[int] of var int: x,
                                    array[int] of int: cover,
                                    array[int] of int: lo,
                                    array[int] of int: up);
predicate global_cardinality_low_up_closed(array[int] of var int: x,
                                           array[int] of int: cover,
                                           array[int] of int: lo,
                                           array[int] of int: up);

% Distinct value count.
predicate nvalue(var int: n, array[int] of var int: x);
predicate atleast_nvalues(var int: n, array[int] of var int: x);
predicate atmost_nvalues(var int: n, array[int] of var int: x);

% Channeling.
predicate inverse(array[int] of var int: f, array[int] of var int: g);
predicate inverse_offsets(array[int] of var int: f, int: fOff,
                          array[int] of var int: g, int: gOff);

% Array reductions.
predicate array_int_maximum(var int: m, array[int] of var int: x);
predicate array_int_minimum(var int: m, array[int] of var int: x);
predicate maximum_int(var int: m, array[int] of var int: x);
predicate minimum_int(var int: m, array[int] of var int: x);

% Ordering.
predicate increasing_int(array[int] of var int: x);
predicate decreasing_int(array[int] of var int: x);
predicate strictly_increasing_int(array[int] of var int: x);
predicate strictly_decreasing_int(array[int] of var int: x);
predicate lex_less_int(array[int] of var int: x, array[int] of var int: y);
predicate lex_lesseq_int(array[int] of var int: x, array[int] of var int: y);

% Symmetry-breaking.
predicate value_precede_int(int: s, int: t, array[int] of var int: x);
predicate value_precede_chain_int(array[int] of int: values, array[int] of var int: x);

% Packing.
predicate diffn(array[int] of var int: x, array[int] of var int: y,
                array[int] of int: w, array[int] of int: h);
predicate diffn_nonstrict(array[int] of var int: x, array[int] of var int: y,
                          array[int] of int: w, array[int] of int: h);

% Table and regular wrappers live in fzn_table_int.mzn and fzn_regular.mzn so they shadow the std files.

% Set variables. Each `var set of E: S` becomes one indicator bool per universe element, and the set
% predicates lower to Boolean factors over those indicators.
predicate set_in(var int: x, var set of int: S);
predicate set_in_reif(var int: x, var set of int: S, var bool: r);
predicate set_subset(var set of int: S, var set of int: T);
predicate set_eq(var set of int: S, var set of int: T);
predicate set_card(var set of int: S, var int: n);
predicate set_union(var set of int: S, var set of int: T, var set of int: U);
predicate set_intersect(var set of int: S, var set of int: T, var set of int: U);
predicate set_diff(var set of int: S, var set of int: T, var set of int: U);
predicate set_subset_reif(var set of int: S, var set of int: T, var bool: r);
predicate set_eq_reif(var set of int: S, var set of int: T, var bool: r);
predicate set_ne(var set of int: S, var set of int: T);
predicate set_ne_reif(var set of int: S, var set of int: T, var bool: r);
predicate set_le(var set of int: S, var set of int: T);
predicate set_le_reif(var set of int: S, var set of int: T, var bool: r);
predicate set_lt(var set of int: S, var set of int: T);
predicate set_lt_reif(var set of int: S, var set of int: T, var bool: r);
predicate array_set_element(var int: x, array[int] of set of int: y, var set of int: z);
predicate array_var_set_element(var int: x, array[int] of var set of int: y, var set of int: z);
predicate all_disjoint(array[int] of var set of int: a);
predicate set_partition_into(array[int] of var set of int: a, var set of int: u);

% Native scheduling and disjunctive predicates.
predicate circuit(array[int] of var int: x);
predicate subcircuit(array[int] of var int: x);
predicate cumulative(array[int] of var int: starts, array[int] of int: durations,
                     array[int] of int: resources, int: capacity);
predicate disjunctive(array[int] of var int: starts, array[int] of int: durations);
predicate disjunctive_strict(array[int] of var int: starts, array[int] of int: durations);
predicate klause_disjunctive_opt(array[int] of var bool: presents,
                               array[int] of var int: starts, array[int] of int: durations);
% Var-arg cumulative / disjunctive[_strict] live in their own override files
% (fzn_cumulative.mzn etc.) so MiniZinc shadows the std decomposing definitions.

% Implied-constraint markers. The stdlib lowers `redundant_constraint(c)` and
% `symmetry_breaking_constraint(c)` to a bare `c`, erasing the marker. Keeping it lets local search
% seed implied rows at a lower violation weight and lets presolve skip its own symmetry breaking.
% The typechecker rewrites the public predicates to these `mzn_*` builtins; redefining them keeps
% `b` and attaches the annotation.
annotation klause_redundant;
annotation klause_symmetry;
predicate mzn_redundant_constraint(var bool: b) = b :: klause_redundant;
predicate mzn_symmetry_breaking_constraint(var bool: b) = b :: klause_symmetry;
