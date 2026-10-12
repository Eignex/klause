% Maps `inverse` to its native factor.
predicate klause_inverse(array [int] of var int: f, array [int] of var int: invf);

predicate fzn_inverse(array [int] of var int: f, array [int] of var int: invf) =
    klause_inverse(f, invf);
