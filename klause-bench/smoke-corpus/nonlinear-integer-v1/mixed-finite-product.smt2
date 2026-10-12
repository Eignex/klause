; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int) (declare-const x Real)
(assert (and (<= 1 n 3) (<= 1.0 x 2.0) (= (* (to_real n) x) 6.0)))
(check-sat)
(get-model)
