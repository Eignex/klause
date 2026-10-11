; SPDX-License-Identifier: Apache-2.0
; Original fixture by Eignex Klause contributors.
(set-logic QF_NRA)
(set-info :status sat)
(declare-const x Real)
(assert (and (= x 10000000000000000000000000000000000000000.000000000000000000001) (> x 10000000000000000000000000000000000000000)))
(check-sat)
