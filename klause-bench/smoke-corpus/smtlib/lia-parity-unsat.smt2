; Self-authored QF_LIA infeasible disjunction control. License: internal.
(set-logic QF_LIA)
(declare-const x Int)
(assert (or (= (* 2 x) 1) (= (* 2 x) 3)))
(check-sat)
