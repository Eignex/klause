% Override std/fzn_cumulative.mzn (which decomposes into per-task reified sums). klause's
% front-end maps fzn_cumulative directly to the native Cumulative factor, which reads variable
% durations/resources/capacity from the assignment (the const arrays are the fast path). Declared
% in the general var form so models with variable task sizes (e.g. carpet-cutting's rotatable
% rectangles) route here instead of falling back to the O(tasks×horizon) reified decomposition. (#492)
predicate fzn_cumulative(array[int] of var int: s, array[int] of var int: d,
                         array[int] of var int: r, var int: b);
