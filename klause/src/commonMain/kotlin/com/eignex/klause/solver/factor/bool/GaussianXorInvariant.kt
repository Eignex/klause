package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Invariant

/**
 * LS contract for [GaussianXor]: propagation-only — this factor defers all local-search
 * semantics to the per-row [Xor] factors posted alongside the Gaussian system. The Gaussian
 * system is redundant with those [Xor] siblings, which carry real LS support.
 *
 * All methods inherit the sound no-op defaults from [Invariant] (always-satisfied, zero deltas).
 */
interface GaussianXorInvariant : Invariant
