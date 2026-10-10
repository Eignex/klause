; Self-authored objective precision smoke instance. License: internal.
(set-logic QF_LIA)
(declare-const x Int)
(assert (>= x 9007199254740992))
(assert (<= x 9007199254740993))
(maximize x)
(check-sat)
