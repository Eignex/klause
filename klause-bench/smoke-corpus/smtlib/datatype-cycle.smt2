; Self-authored finite-constructor-tree control. Apache-2.0.
(set-logic QF_DT)
(declare-datatype Nat ((zero) (succ (previous Nat))))
(declare-const x Nat)
(assert (= x (succ x)))
(check-sat)
