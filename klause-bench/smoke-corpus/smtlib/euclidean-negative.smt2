; Self-authored negative-divisor Euclidean control. Apache-2.0.
(set-logic QF_NIA)
(declare-const a Int)
(declare-const b Int)
(assert (= a (- 7)))
(assert (= b (- 3)))
(assert (= (div a b) 3))
(assert (= (mod a b) 2))
(check-sat)
