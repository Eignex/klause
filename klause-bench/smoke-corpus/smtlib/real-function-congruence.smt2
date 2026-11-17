; Self-authored exact rational congruence control. Apache-2.0.
(set-logic QF_UFLRA)
(declare-fun value (Real) Real)
(assert (distinct (value (/ 1 3)) (value (/ 2 6))))
(check-sat)
