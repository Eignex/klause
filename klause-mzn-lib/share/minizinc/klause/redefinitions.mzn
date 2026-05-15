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

% Globals (the small set klause has native propagators for).
predicate all_different_int(array[int] of var int: x);
predicate count_eq(array[int] of var int: x, var int: y, var int: c);
