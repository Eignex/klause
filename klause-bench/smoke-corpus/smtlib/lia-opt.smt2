; Self-authored QF_LIA smoke instance (optimization). License: internal.
; minimize x+y subject to x,y>=0, x+y<=10, (x>=7 or y>=7)  =>  optimum 7.
(set-logic QF_LIA)
(declare-const x Int)
(declare-const y Int)
(assert (>= x 0))
(assert (>= y 0))
(assert (<= (+ x y) 10))
(assert (or (>= x 7) (>= y 7)))
(minimize (+ x y))
(check-sat)
