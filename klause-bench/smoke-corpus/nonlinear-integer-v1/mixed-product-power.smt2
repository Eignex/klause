; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int) (declare-const x Real)
(assert (= n 2))
(assert (= (^ (* (to_real n) x) 2) 8.0)) (assert (> x 0.0))
(check-sat)
(get-model)
