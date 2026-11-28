package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.IncumbentSource
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo

// Installs a witness only when its value lies strictly below the standing incumbent's. Scored by [BigInt]: open
// columns are unbounded, and a rounded width would order two incumbents by a value neither attains.
//
// The producer is trusted: a round decides the model plus the row demanding the objective beat the incumbent, so
// a `Sat` verdict already certifies the witness against that bound. The exchange weighs each witness against the
// best so far and versions it, so the bound the next round refutes is a value some assignment attains.
internal fun minimizingWitnessExchange(): IncumbentExchange<OpenTheoryAssignment, BigInt> =
    IncumbentExchange(improves = { candidate, standing -> candidate < standing })

internal fun IncumbentSource<*, BigFraction>.integerBound(): BigInt? =
    current()?.objective?.let { if (it.den == BIG_ONE) it.num else null }
