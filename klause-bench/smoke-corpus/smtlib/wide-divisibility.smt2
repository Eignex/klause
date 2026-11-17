; Self-authored arbitrary-precision divisibility control. Apache-2.0.
(set-logic QF_LIA)
(declare-const x Int)
(assert (= x 18446744073709551616))
(assert ((_ divisible 18446744073709551616) x))
(check-sat)
