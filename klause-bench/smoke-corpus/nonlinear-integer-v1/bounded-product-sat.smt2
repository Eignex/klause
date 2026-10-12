; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const x Int) (declare-const y Int)
(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) 6)))
(check-sat)
(get-model)
