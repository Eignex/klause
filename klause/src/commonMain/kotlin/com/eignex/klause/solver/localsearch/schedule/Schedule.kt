package com.eignex.klause.solver.localsearch.schedule

/**
 * Temperature schedule for simulated annealing: owns the current [temperature] and evolves it as
 * the search runs. Stateful and per-instance — never share one across concurrent searches.
 *
 * The search loop drives a schedule with three signals:
 *  - [step] once per annealing move; cooling schedules lower the temperature here.
 *  - [observe] at the end of each round ([RoundLog]); adaptive schedules retune, static ones
 *    ignore it.
 *  - [reheat] to raise the temperature by a factor — periodic reheating or an explicit kick.
 *
 * [reset] returns to the start temperature (e.g. on restart). The schedule never touches the search
 * assignment, so reheating re-diversifies without discarding the incumbent.
 *
 * Implementations: [Geometric] (fixed cooling rate — the classic schedule), [AdaptiveCooling] (rate
 * tracks the observed acceptance ratio), and the [Reheating] decorator.
 */
interface Schedule {
    /** Current temperature; always strictly positive. */
    val temperature: Double

    /** Advance one annealing step. Cooling schedules lower the temperature here. */
    fun step()

    /** Multiply the current temperature by [factor] (≥ 1), capped at the schedule's ceiling. */
    fun reheat(factor: Double)

    /** Feed end-of-round statistics; adaptive schedules retune, static ones ignore it. */
    fun observe(round: RoundLog)

    /** Reset to the initial temperature. */
    fun reset()
}

/**
 * Shared temperature state for the cooling schedules: holds the current [temperature] bounded to
 * `[minTemperature, maxTemperature]` and implements [reheat]/[reset]. Subclasses supply the
 * per-[step] cooling and, optionally, an [observe] response. The ceiling defaults to
 * [initialTemperature], so repeated reheats never run hotter than the start.
 */
abstract class AbstractSchedule(
    protected val initialTemperature: Double,
    protected val minTemperature: Double,
    protected val maxTemperature: Double = initialTemperature,
) : Schedule {
    init {
        require(initialTemperature > 0.0) {
            "initialTemperature must be positive, got $initialTemperature"
        }
        require(minTemperature > 0.0) { "minTemperature must be positive, got $minTemperature" }
        require(minTemperature <= initialTemperature) {
            "minTemperature ($minTemperature) must be ≤ initialTemperature ($initialTemperature)"
        }
        require(maxTemperature >= initialTemperature) {
            "maxTemperature ($maxTemperature) must be ≥ initialTemperature ($initialTemperature)"
        }
    }

    final override var temperature: Double = initialTemperature
        protected set

    /** Set the temperature, clamped to `[minTemperature, maxTemperature]`. */
    protected fun updateTemperature(value: Double) {
        temperature = value.coerceIn(minTemperature, maxTemperature)
    }

    override fun reheat(factor: Double) {
        require(factor >= 1.0) { "reheat factor must be ≥ 1, got $factor" }
        updateTemperature(temperature * factor)
    }

    override fun observe(round: RoundLog) = Unit

    override fun reset() {
        temperature = initialTemperature
    }
}

/**
 * Classic geometric cooling: every [step] multiplies the temperature by [coolingRate] (in `(0, 1]`),
 * floored at the schedule's minimum. With `coolingRate == 1.0` the temperature is constant. The
 * defaults (start 1.0, rate 0.999, floor 1e-3) reproduce the long-standing annealing behaviour.
 */
class Geometric(
    initialTemperature: Double = 1.0,
    val coolingRate: Double = 0.999,
    minTemperature: Double = 1e-3,
    maxTemperature: Double = initialTemperature,
) : AbstractSchedule(initialTemperature, minTemperature, maxTemperature) {
    init {
        require(coolingRate in 0.0..1.0) { "coolingRate must be in (0, 1], got $coolingRate" }
    }

    override fun step() {
        updateTemperature(temperature * coolingRate)
    }
}

/**
 * Geometric cooling whose rate adapts each round to hold the observed acceptance ratio near
 * [targetAcceptance]. The temperature still cools by the current [coolingRate] on every [step];
 * [observe] nudges that rate:
 *
 *  - acceptance **above** target ⇒ the walk is too free (temperature too high) ⇒ cool faster, by
 *    pushing the rate down toward [minRate];
 *  - acceptance **below** target ⇒ the walk is too frozen ⇒ cool slower, by pushing the rate up
 *    toward [maxRate].
 *
 * The rate moves geometrically by `1 − adjustStep·(acceptance − target)` per round, bounded to
 * `[minRate, maxRate]`. Cost variance is reported in [RoundLog] for callers that want it, but the
 * controller keys off the acceptance ratio alone — the most robust, scale-free signal.
 */
class AdaptiveCooling(
    initialTemperature: Double = 1.0,
    val targetAcceptance: Double = 0.4,
    initialRate: Double = 0.999,
    val minRate: Double = 0.9,
    val maxRate: Double = 0.99999,
    /** Per-round reactivity: the rate moves by `1 − adjustStep·(acceptance − target)`. */
    val adjustStep: Double = 0.2,
    minTemperature: Double = 1e-3,
    maxTemperature: Double = initialTemperature,
) : AbstractSchedule(initialTemperature, minTemperature, maxTemperature) {
    init {
        require(targetAcceptance in 0.0..1.0) {
            "targetAcceptance must be in [0, 1], got $targetAcceptance"
        }
        require(minRate in 0.0..1.0) { "minRate must be in [0, 1], got $minRate" }
        require(maxRate in 0.0..1.0) { "maxRate must be in [0, 1], got $maxRate" }
        require(minRate <= maxRate) { "minRate ($minRate) must be ≤ maxRate ($maxRate)" }
        require(initialRate in minRate..maxRate) {
            "initialRate ($initialRate) must be in [$minRate, $maxRate]"
        }
        require(adjustStep >= 0.0) { "adjustStep must be non-negative, got $adjustStep" }
    }

    private val startRate: Double = initialRate

    /** Current geometric cooling rate; retuned by [observe], reset by [reset]. */
    var coolingRate: Double = initialRate
        private set

    override fun step() {
        updateTemperature(temperature * coolingRate)
    }

    override fun observe(round: RoundLog) {
        val error = round.acceptanceRatio - targetAcceptance
        val scaled = (1.0 - adjustStep * error).coerceAtLeast(0.0)
        coolingRate = (coolingRate * scaled).coerceIn(minRate, maxRate)
    }

    override fun reset() {
        super.reset()
        coolingRate = startRate
    }
}

/**
 * Decorates a base [Schedule] with periodic geometric reheating: it forwards every [step] to
 * [base], and every [period] steps raises the base temperature by [reheatFactor] (≥ 1). A
 * cooled-and-stuck run re-diversifies without a restart — the schedule never touches the
 * assignment, so the incumbent is preserved. The base's own ceiling caps the reheated temperature.
 */
class Reheating(private val base: Schedule, val period: Int, val reheatFactor: Double = 4.0) : Schedule {
    init {
        require(period > 0) { "period must be positive, got $period" }
        require(reheatFactor >= 1.0) { "reheatFactor must be ≥ 1, got $reheatFactor" }
    }

    private var sinceReheat: Int = 0

    override val temperature: Double get() = base.temperature

    override fun step() {
        base.step()
        if (++sinceReheat >= period) {
            base.reheat(reheatFactor)
            sinceReheat = 0
        }
    }

    override fun reheat(factor: Double) = base.reheat(factor)

    override fun observe(round: RoundLog) = base.observe(round)

    override fun reset() {
        base.reset()
        sinceReheat = 0
    }
}
