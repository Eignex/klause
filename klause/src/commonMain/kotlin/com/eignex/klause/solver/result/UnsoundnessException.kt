package com.eignex.klause.solver.result

/**
 * An engine produced a verdict that an independent check refutes, such as a model that violates the
 * constraints it claims to satisfy. This is a solver defect, never a property of the input, so it is
 * raised rather than reported as an inconclusive result: a portfolio must not swallow it and carry on
 * with its other arms, and a caller must not read it as `unknown`.
 */
class UnsoundnessException(message: String) : IllegalStateException(message)
