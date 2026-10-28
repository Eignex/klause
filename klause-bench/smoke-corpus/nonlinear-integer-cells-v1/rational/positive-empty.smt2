; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(declare-const q Int)
(assert (> (* 4 n) 1))
(assert (< (* 4 n) 3))
(check-sat)
(get-model)
