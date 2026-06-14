% Override std/fzn_diffn_nonstrict.mzn — see fzn_diffn.mzn. Native Diffn factor (non-strict
% variant: zero-size boxes don't conflict), general var-size form. (#492)
predicate fzn_diffn_nonstrict(array[int] of var int: x,  array[int] of var int: y,
                              array[int] of var int: dx, array[int] of var int: dy);
