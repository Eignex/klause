; Self-authored Eignex Klause regression fixture. Apache-2.0.
(set-logic ALL)
(declare-const n Int)
(define-fun product ((x Int)) Int (* n x))
(assert (let ((n 99)) (= (product 2) 6)))
(check-sat)
(get-model)
