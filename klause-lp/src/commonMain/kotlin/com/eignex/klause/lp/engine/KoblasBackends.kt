package com.eignex.klause.lp.engine

import com.eignex.koblas.discoverBackends

private val koblasDiscovery: Unit by lazy { discoverBackends() }

/**
 * Run koblas's host-backend discovery, once for the process.
 *
 * koblas registers nothing by itself. Until discovery runs it answers every seam with its portable
 * reference implementation, so an accelerated backend on the classpath — HFactor for the simplex basis,
 * a host SuiteSparse or OpenBLAS where one is installed — is present and never reached. Nothing else in
 * klause touches koblas, so this is the whole of the decision.
 *
 * Each candidate costs a `dlopen` and a symbol lookup, so it is done lazily, by the first solve that
 * builds a factorization, rather than at class load. koblas's own entry point is already once-latched;
 * the lazy is here so a solve does not re-enter it per pivot.
 *
 * Which backend fills which role is the deployment's business rather than this call's: a role can be
 * pinned to a named backend, or back to the portable one, through koblas's own configuration. So this
 * decides only that the question gets asked.
 */
internal fun ensureKoblasBackends() {
    koblasDiscovery
}
