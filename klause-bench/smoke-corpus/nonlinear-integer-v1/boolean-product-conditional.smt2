; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int) (declare-const p Bool)
(assert (= p (= (* n n) 4)))
(assert (= (ite p (^ n 2) 99) 4))
(check-sat-assuming (p))
(get-model)
