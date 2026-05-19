%-----------------------------------------------------------------------------%
% Klause solver library.
%
% Declares which FlatZinc predicates klause supports natively. Anything not
% listed here falls back to MiniZinc's standard library, which decomposes
% globals (cumulative, regular, circuit, bin_packing, table, element, lex,
% gcc, nvalue, etc.) into combinations of the predicates below.
%
% The pattern is: bare declarations (no body) mean "native, emit straight to
% FlatZinc". Klause's FlatZincCompiler reads the resulting FZN.
%-----------------------------------------------------------------------------%

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

% Float linear — klause buckets float vars into ints at FZN-load time.
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

% Globals — klause has native factors for these. MiniZinc routes them directly to
% klause's FlatZinc compiler instead of decomposing in its standard library.
predicate all_different_int(array[int] of var int: x);
predicate all_equal_int(array[int] of var int: x);
predicate alldifferent_except_0(array[int] of var int: x);
predicate symmetric_all_different(array[int] of var int: x);
predicate member_int(array[int] of var int: x, var int: y);
predicate sort(array[int] of var int: x, array[int] of var int: y);

% Counting.
predicate count_eq(array[int] of var int: x, var int: y, var int: c);
predicate count_neq(array[int] of var int: x, var int: y, var int: c);
predicate count_le(array[int] of var int: x, var int: y, var int: c);
predicate count_lt(array[int] of var int: x, var int: y, var int: c);
predicate count_ge(array[int] of var int: x, var int: y, var int: c);
predicate count_gt(array[int] of var int: x, var int: y, var int: c);
predicate among(var int: n, array[int] of var int: x, set of int: v);

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
predicate arg_max_int(array[int] of var int: x, var int: i);
predicate arg_min_int(array[int] of var int: x, var int: i);
predicate exactly_int(int: n, array[int] of var int: x, int: v);

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

% Sliding-window cardinality.
predicate sequence(array[int] of var int: x, int: low, int: high, int: k, set of int: s);

% Packing.
predicate bin_packing(int: c, array[int] of var int: bin, array[int] of int: w);
predicate bin_packing_capa(array[int] of int: c, array[int] of var int: bin, array[int] of int: w);
predicate bin_packing_load(array[int] of var int: load, array[int] of var int: bin, array[int] of int: w);
predicate knapsack(array[int] of int: w, array[int] of int: p,
                   array[int] of var int: x, var int: weight, var int: profit);
predicate diffn(array[int] of var int: x, array[int] of var int: y,
                array[int] of int: w, array[int] of int: h);
predicate diffn_nonstrict(array[int] of var int: x, array[int] of var int: y,
                          array[int] of int: w, array[int] of int: h);

% Extensional / regular.
predicate table_int(array[int] of var int: x, array[int, int] of int: t);
predicate regular(array[int] of var int: x, int: Q, int: S,
                  array[int, int] of int: d, int: q0, set of int: F);

% Set variables and predicates. Klause materialises each `var set of E: S` as one
% indicator bool per universe element; the set predicates below dispatch to bool-level
% Clause / PseudoBoolean / ReifiedLinear factors over those indicators. set_in supports
% constant-int elements only today; set_in_reif likewise.
predicate set_in(var int: x, var set of int: S);
predicate set_in_reif(var int: x, var set of int: S, var bool: r);
predicate set_subset(var set of int: S, var set of int: T);
predicate set_eq(var set of int: S, var set of int: T);
predicate set_card(var set of int: S, var int: n);
predicate set_union(var set of int: S, var set of int: T, var set of int: U);
predicate set_intersect(var set of int: S, var set of int: T, var set of int: U);
predicate set_diff(var set of int: S, var set of int: T, var set of int: U);

% Scheduling / disjunctive (legacy klause coverage; kept native for completeness).
predicate circuit(array[int] of var int: x);
predicate subcircuit(array[int] of var int: x);
predicate cumulative(array[int] of var int: starts, array[int] of int: durations,
                     array[int] of int: resources, int: capacity);
predicate disjunctive(array[int] of var int: starts, array[int] of int: durations);
predicate disjunctive_strict(array[int] of var int: starts, array[int] of int: durations);
