package com.eignex.klause.lp

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem

/**
 * The **root** box integer column [v] takes: the root-propagation fold once it has run
 * (`BakedProblem.rootIntDomain`), the model's own declaration before it — presolve drives the relaxation
 * over a source model whose bake is still deferred, so both shapes reach here.
 *
 * Node-invariant, which is what a column layout that must be identical across nodes, a
 * relaxation-size estimate, or a live-versus-root comparison needs. A per-node bound is a different
 * thing entirely and comes from [com.eignex.klause.propagation.PropagationSession.intDomain]; reading a
 * root box where the live one is meant loses every decision above the node.
 *
 * Whether the endpoints of this box are the model's own is a separate question — see
 * [statesLowerBound] / [statesUpperBound].
 */
internal fun Problem.rootDomainOf(v: Int): IntDomain = finiteIntDomain(v)

/** Every column's [rootDomainOf], in column order. Copied, so read one column through [rootDomainOf]
 *  rather than calling this per access. */
internal fun Problem.rootDomainsOf(): Array<IntDomain> = finiteIntDomains()

/**
 * Whether the model itself bounds integer column [v] below.
 *
 * A side closed by an invented box bounds the search and not the model, so a row or cut whose constants
 * lean on that endpoint holds inside the box only — it cannot be published as globally valid, and a
 * relaxation that enumerates the box restricts a range the model never excluded. Consumers either read
 * this state or decline the column outright.
 */
internal fun Problem.statesLowerBound(v: Int): Boolean = intBounds.hasLower(v)

/** Whether the model itself bounds integer column [v] above; see [statesLowerBound]. */
internal fun Problem.statesUpperBound(v: Int): Boolean = intBounds.hasUpper(v)

/**
 * Whether integer column [v] takes a value in `{0, 1}` at every solution of the model.
 *
 * Both sides have to be the model's own: a `{0, 1}` box over a column the source left open is a search
 * restriction, and a linearization that reads it as a binary declaration — an indicator, a product
 * column, an RLT row — would state constants no solution outside the box has to satisfy.
 */
internal fun Problem.statesBinary(v: Int): Boolean =
    statesLowerBound(v) && statesUpperBound(v) && rootDomainOf(v).let { it.min == 0L && it.max == 1L }
