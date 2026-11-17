; Self-authored independent total zero-division choices. Apache-2.0.
(set-logic QF_NIA)
(declare-const a Int)
(declare-const b Int)
(assert (= a 3))
(assert (= b 0))
(assert (= (div a b) 7))
(assert (= (mod a b) 11))
(check-sat)
