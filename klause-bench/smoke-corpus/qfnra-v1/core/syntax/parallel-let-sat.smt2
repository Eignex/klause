; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(assert (let ((x 0) (y x)) (and (= (* y y) 2) (= x 0))))
(check-sat)
