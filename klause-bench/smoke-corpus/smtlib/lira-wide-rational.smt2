; Self-authored exact witness transport smoke instance. License: internal.
(set-logic QF_LIRA)
(declare-const i Int)
(declare-const x Real)
(assert (= i 9223372036854775808))
(assert (= (* 3 x) 1))
(check-sat)
