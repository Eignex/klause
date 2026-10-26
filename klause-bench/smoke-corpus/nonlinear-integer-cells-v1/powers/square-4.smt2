; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(declare-const q1 Int)
(declare-const q2 Int)
(declare-const q3 Int)
(assert (= (* n n) 2))
(check-sat)
(get-model)
