package com.eignex.klause.solver

import kotlin.random.Random

typealias IntConsumer = com.eignex.klause.ir.IntConsumer
typealias IntDomain = com.eignex.klause.ir.IntDomain
typealias IntSpan = com.eignex.klause.ir.IntSpan

val IntDomain.values: IntSpan get() = com.eignex.klause.ir.values(this)
fun IntDomain.randomValue(rng: Random): Long = com.eignex.klause.ir.randomValue(this, rng)
