package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * The single incumbent one open-model descent publishes through: a witness installs only when the value
 * read off it lies strictly below the standing incumbent's.
 *
 * Scored by [BigInteger] rather than by the `Double` a finite exchange uses. The columns an open route
 * decides are unbounded and the descent subtracts one per improvement without knowing how far it will go,
 * so a width that rounded would order two incumbents by a value neither of them attains.
 *
 * The producer is trusted here, unlike a finite arm's. A round asks the route to decide the model *plus* the
 * row demanding the objective beat the incumbent, so a `Sat` verdict already certifies that witness
 * against that bound; verifying it here would solve the same question a second time. What the exchange
 * adds is what a single round cannot see: one place where a witness is weighed against the best so far and
 * stamped with a version, so the bound the next round refutes is a value some assignment attains.
 */
internal fun minimizingWitnessExchange(): IncumbentExchange<OpenTheoryAssignment, BigInteger> =
    IncumbentExchange(improves = { candidate, standing -> candidate < standing })
