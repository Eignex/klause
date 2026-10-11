; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(define-fun f ((t Real)) Real (+ x t))
(assert (and (= x 2) (let ((x 0)) (= (f 1) 3))))
(check-sat)
