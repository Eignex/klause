package com.eignex.klause.solver.lp

/**
 * Thrown when an exact [Long] operation in the integer-preserving LP core would overflow
 * 64 bits. Issue calls for capping determinant growth by periodic refactorization or
 * falling back to the float-plus-exact-certification path; this exception is the seam where
 * those strategies hook in. The first implementation surfaces overflow as a hard failure
 * rather than silently producing a wrong bound — an unsound LP bound would make
 * branch-and-bound return wrong answers, so failing loud is the safe default.
 *
 * Also raised by the dual simplex's iteration backstop: both mean "the exact LP core
 * could not produce a result," and every caller handles them identically — drop the LP bound and
 * keep the node, which only loses pruning and never soundness.
 */
internal class LpOverflowException(message: String) : ArithmeticException(message)
