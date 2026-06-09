; Self-authored QF_LIA smoke instance (infeasible). License: internal.
; x > 5 and x < 3 is unsatisfiable over the integers.
(set-logic QF_LIA)
(declare-const x Int)
(assert (> x 5))
(assert (< x 3))
(check-sat)
