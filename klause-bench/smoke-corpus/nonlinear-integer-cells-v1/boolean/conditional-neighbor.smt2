; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(declare-const q Int)
(declare-const b Bool)
(assert (or (and b (= (* n n) 2)) (and (not b) (= (* n n) 4))))
(check-sat)
(get-model)
