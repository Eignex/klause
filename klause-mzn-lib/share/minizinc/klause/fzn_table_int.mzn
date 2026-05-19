% Override std/fzn_table_int.mzn. MiniZinc looks up a solver-lib file with the
% same basename as a std file and uses it instead. klause's native `klause_table_int`
% takes a 1D row-major flattened tuple array (FlatZinc rejects 2D builtin args).
predicate klause_table_int(array[int] of var int: x, array[int] of int: t);

predicate fzn_table_int(array[int] of var int: x, array[int, int] of int: t) =
    klause_table_int(x, array1d(t));
