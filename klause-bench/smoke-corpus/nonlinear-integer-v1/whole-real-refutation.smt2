; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(assert (< (* n n) 0))
(check-sat)
(get-model)
