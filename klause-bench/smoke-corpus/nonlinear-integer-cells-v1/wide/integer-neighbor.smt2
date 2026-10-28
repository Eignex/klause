; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(declare-const q Int)
(assert (> n 100000000000000000000))
(assert (<= (* (- n 100000000000000000000) (- n 100000000000000000000)) 1))
(check-sat)
(get-model)
