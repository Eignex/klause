% Override std/fzn_diffn.mzn (pairwise no-overlap reified decomposition). klause maps fzn_diffn to
% the native Diffn factor, which reads variable box sizes from the assignment. General var form so
% variable-size packing (carpet-cutting) passes through instead of decomposing. (#492)
predicate fzn_diffn(array[int] of var int: x,  array[int] of var int: y,
                    array[int] of var int: dx, array[int] of var int: dy);
