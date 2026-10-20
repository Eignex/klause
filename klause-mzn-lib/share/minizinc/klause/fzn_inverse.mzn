% Maps `inverse` to its native factor.
predicate klause_inverse_offsets(array [int] of var int: f, int: fOff,
                                 array [int] of var int: invf, int: gOff);

predicate fzn_inverse(array [int] of var int: f, array [int] of var int: invf) =
    if length(f) = 0 /\ length(invf) = 0 then true else
      klause_inverse_offsets(array1d(f), min(index_set(f)), array1d(invf), min(index_set(invf)))
    endif;
