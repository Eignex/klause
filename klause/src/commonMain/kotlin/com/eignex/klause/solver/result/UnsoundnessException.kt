package com.eignex.klause.solver.result

/**
 * An engine produced a verdict that an independent check refutes, such as a model that violates the
 * constraints it claims to satisfy. This is a solver defect, never a property of the input, so it is
 * raised rather than reported as an inconclusive result, and a caller must not read it as `unknown`.
 * A portfolio with other arms to answer quarantines the arm that raised it and reports it loudly (see
 * [com.eignex.klause.portfolio.ArmFault]) rather than let one faulty configuration end the run.
 */
class UnsoundnessException(message: String) : IllegalStateException(message)
