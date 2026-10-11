; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(declare-const y Real)
(declare-const z Real)
(assert (and (= (* x x) 5) (> x 0) (= (* y y) x) (> y 0) (= (* z z) y) (> z 0)))
(check-sat)
