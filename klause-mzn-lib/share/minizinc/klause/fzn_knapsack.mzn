% Auto: keep `knapsack` native (klause has a factor for it) instead of the std decomposition.
predicate klause_knapsack(array [int] of int: w, array [int] of int: p, array [int] of var int: x, var int: W, var int: P);

predicate fzn_knapsack(array [int] of int: w, array [int] of int: p, array [int] of var int: x, var int: W, var int: P) =
    klause_knapsack(w, p, x, W, P);
