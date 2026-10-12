; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(assert (= n 9223372036854775807))
(assert (= (- (* n n) (* n n)) 0))
(check-sat)
(get-model)
