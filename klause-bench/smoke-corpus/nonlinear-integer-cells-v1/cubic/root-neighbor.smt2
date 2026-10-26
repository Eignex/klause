; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(declare-const q Int)
(assert (= (* (- (* n n) 2) (- n 2)) 0))
(check-sat)
(get-model)
