; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const x Int) (declare-const y Int)
(assert (= (* x y) 6)) (assert (> x 1)) (assert (> y 1))
(check-sat)
(get-model)
