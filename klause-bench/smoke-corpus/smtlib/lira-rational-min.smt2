; Self-authored objective precision smoke instance. License: internal.
(set-logic QF_LIRA)
(declare-const x Real)
(declare-const y Int)
(assert (= (* 3 x) y))
(assert (>= y 1))
(assert (<= y 2))
(minimize x)
(check-sat)
