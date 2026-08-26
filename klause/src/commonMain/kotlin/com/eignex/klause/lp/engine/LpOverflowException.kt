package com.eignex.klause.lp.engine

/**
 * Thrown when an overflow-checked [Long] operation in LP relaxation construction or an integer bound
 * computation would overflow 64 bits. Surfacing overflow as a hard failure rather than silently
 * producing a wrapped coefficient is deliberate — an unsound LP bound would make branch-and-bound
 * return wrong answers, so failing loud is the safe default. Every caller handles it identically:
 * drop the LP bound and keep the node, which only loses pruning and never soundness.
 */
internal class LpOverflowException(message: String) : ArithmeticException(message)
