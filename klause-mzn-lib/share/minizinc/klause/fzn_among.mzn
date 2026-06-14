% Override std/fzn_among.mzn (decomposes into a reified set-membership sum n = Σ_i (x[i] in v)).
% klause maps fzn_among to its native GlobalCardinality factor over the (constant) value set v
% plus a linear tying n to the per-value counts, so membership counting is propagated by GCC
% flow reasoning instead of a set_in_reif/bool2int swamp (#517).
predicate fzn_among(var int: n, array [int] of var int: x, set of int: v);
