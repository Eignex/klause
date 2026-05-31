% Keep `diffn_nonstrict` native (klause Diffn supports constant AND variable rectangle sizes).
predicate klause_diffn_nonstrict(array [int] of var int: x, array [int] of var int: y, array [int] of var int: dx, array [int] of var int: dy);

predicate fzn_diffn_nonstrict(array [int] of var int: x, array [int] of var int: y, array [int] of var int: dx, array [int] of var int: dy) =
    klause_diffn_nonstrict(x, y, dx, dy);
