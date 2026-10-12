; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(assert (= n 100000000000000000000))
(assert (= (* n n) 10000000000000000000000000000000000000000))
(check-sat)
(get-model)
