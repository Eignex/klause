% Shadows std/fzn_regular.mzn. `klause_regular` takes a row-major 1D transition table, since FlatZinc
% rejects 2D builtin args.
predicate klause_regular(array[int] of var int: x, int: Q, int: S,
                         array[int] of int: d, int: q0, set of int: F);

predicate fzn_regular(array[int] of var int: x, int: Q, int: S,
                      array[int, int] of int: d, int: q0, set of int: F) =
    klause_regular(x, Q, S, array1d(d), q0, F);
