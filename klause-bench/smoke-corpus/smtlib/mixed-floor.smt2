; Self-authored exact negative-floor control. Apache-2.0.
(set-logic QF_NIRA)
(declare-const x Real)
(declare-const n Int)
(assert (= x (- (/ 7 3))))
(assert (= n (to_int x)))
(assert (= n (- 3)))
(assert (is_int (to_real n)))
(check-sat)
