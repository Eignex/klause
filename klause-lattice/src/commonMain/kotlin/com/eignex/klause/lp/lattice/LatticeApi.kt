package com.eignex.klause.lp.lattice

/**
 * Numerical implementation seam between Klause modules. Callers borrow result buffers read-only;
 * input rows remain caller-owned and must stay unchanged during reduction. This surface is versioned
 * with Klause and is not a general solver extension contract.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LatticeApi
