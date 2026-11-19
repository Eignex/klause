; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const x Int) (declare-const r Real)
(assert (and (>= x 7) (<= x 10) (>= r 3)
    (>= (- (* r r) (to_real x)) (- 2)) (<= (- (* r r) (to_real x)) 2)))
(check-sat)
(get-model)
