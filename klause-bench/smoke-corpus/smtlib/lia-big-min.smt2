; Self-authored objective precision smoke instance. License: internal.
(set-logic QF_LIA)
(declare-const x Int)
(assert (>= x 9223372036854775808))
(assert (<= x 9223372036854775809))
(minimize x)
(check-sat)
