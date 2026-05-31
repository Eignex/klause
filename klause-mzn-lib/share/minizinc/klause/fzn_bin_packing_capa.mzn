% Auto: keep `bin_packing_capa` native (klause has a factor for it) instead of the std decomposition.
predicate klause_bin_packing_capa(array [int] of int: c, array [int] of var int: bin, array [int] of int: w);

predicate fzn_bin_packing_capa(array [int] of int: c, array [int] of var int: bin, array [int] of int: w) =
    klause_bin_packing_capa(c, bin, w);
