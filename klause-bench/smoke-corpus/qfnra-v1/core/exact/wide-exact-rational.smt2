; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(assert (and (= (* x x) (/ 9007199254740993 9007199254740992)) (> x 1)))
(check-sat)
