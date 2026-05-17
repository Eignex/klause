package com.eignex.klause.solver.localsearch.meta

import kotlin.random.Random

/**
 * Operator-selection bandit used by [Alns] to pick destroy / repair operators by their
 * past rewards. Both the classic roulette-wheel [RouletteWheelBandit] and the kumulant-
 * backed [ThompsonBandit] implement this interface; ALNS treats them interchangeably.
 *
 * Contract:
 *  - [pick] returns an operator index in `[0, numOperators)`. Some implementations
 *    consume the passed [Random]; others (e.g. Thompson) use their own RNG and ignore
 *    the argument.
 *  - [reward] records the reward earned by the operator the caller chose on the previous
 *    pick. Magnitudes are interpreted differently per implementation: roulette uses raw
 *    values while Thompson treats `value ∈ [0, 1]` as a soft Bernoulli success
 *    probability — callers should normalize accordingly.
 *  - [advance] signals "iteration boundary". Roulette uses this to roll its segment;
 *    Thompson is a no-op (updates happen immediately on [reward]).
 */
interface Bandit {
    val numOperators: Int
    fun pick(rng: Random): Int
    fun reward(operatorIdx: Int, reward: Double)
    fun advance()
}
