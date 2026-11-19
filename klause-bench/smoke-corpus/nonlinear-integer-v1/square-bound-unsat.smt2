; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const x Int) (declare-const r Real)
(assert (and (>= x 0) (<= x 8) (>= r 3) (= (* r r) (to_real x))))
(check-sat)
