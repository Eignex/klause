% Auto: keep `inverse` native (klause has a factor for it) instead of the std decomposition.
predicate klause_inverse(array [int] of var int: f, array [int] of var int: invf);

predicate fzn_inverse(array [int] of var int: f, array [int] of var int: invf) =
    klause_inverse(f, invf);
