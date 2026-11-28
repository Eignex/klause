% Shadows std/fzn_table_int.mzn. `klause_table_int` takes a row-major 1D tuple array, since FlatZinc
% rejects 2D builtin args.
predicate klause_table_int(array[int] of var int: x, array[int] of int: t);

predicate fzn_table_int(array[int] of var int: x, array[int, int] of int: t) =
    klause_table_int(x, array1d(t));
