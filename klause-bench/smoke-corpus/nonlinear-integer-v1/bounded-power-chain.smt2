; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const x Int)
(assert (and (<= (- 2) x 2) (= (^ (^ x 2) 2) 16)))
(check-sat)
(get-model)
