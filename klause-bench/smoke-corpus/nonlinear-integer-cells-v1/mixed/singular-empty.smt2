; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const r Real)
(declare-const n Int)
(declare-const q Int)
(assert (= (* r r) 0.0))
(assert (= (* n n) 2))
(check-sat)
(get-model)
