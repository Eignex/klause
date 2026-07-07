package com.eignex.klause.bench.tune

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One tunable dimension of a solver config space: a name, a domain, a random draw, and an
 * [activeWhen] predicate over the choices made so far (conditional / child parameters — e.g. a
 * CBLS-only knob is inactive unless `family == cbls`). The declaration doubles as the source for a
 * Vizier `StudySpec` parameter (task #24): categorical → CATEGORICAL, int → INTEGER, double → DOUBLE.
 *
 * The space is never materialized — [ConfigSpace.sample] draws points lazily and a decoder turns a
 * point (a `Map<name, value>`) into a solver recipe. This is the search space the BO optimizes over,
 * covering the full sub-algorithm knob cross-product without enumerating it.
 */
internal sealed interface ConfigParam {
    val name: String

    /** Whether this param is live given the [assignment] drawn for earlier params (family gating). */
    fun activeIn(assignment: Map<String, Any>): Boolean

    /** A uniform random value from the domain. */
    fun sample(rng: Random): Any
}

/** A categorical choice over [values]. */
internal class CategoricalParam(
    override val name: String,
    val values: List<String>,
    private val active: (Map<String, Any>) -> Boolean = { true },
) : ConfigParam {
    init {
        require(values.isNotEmpty()) { "categorical '$name' needs at least one value" }
    }

    override fun activeIn(assignment: Map<String, Any>) = active(assignment)
    override fun sample(rng: Random): Any = values[rng.nextInt(values.size)]
}

/** An integer in `[min, max]` (inclusive). */
internal class IntParam(
    override val name: String,
    val min: Int,
    val max: Int,
    private val active: (Map<String, Any>) -> Boolean = { true },
) : ConfigParam {
    init {
        require(min <= max) { "int '$name' needs min <= max" }
    }

    override fun activeIn(assignment: Map<String, Any>) = active(assignment)
    override fun sample(rng: Random): Any = rng.nextInt(min, max + 1)
}

/** A double in `[min, max)`. */
internal class DoubleParam(
    override val name: String,
    val min: Double,
    val max: Double,
    private val active: (Map<String, Any>) -> Boolean = { true },
) : ConfigParam {
    init {
        require(min <= max) { "double '$name' needs min <= max" }
    }

    override fun activeIn(assignment: Map<String, Any>) = active(assignment)
    override fun sample(rng: Random): Any = min + rng.nextDouble() * (max - min)
}

/**
 * A declared search space: an ordered list of [params] (earlier ones gate later conditionals). A
 * decoder in the concrete space (e.g. [LocalSearchConfigSpace]) turns a sampled assignment into a solver recipe.
 */
internal open class ConfigSpace(val params: List<ConfigParam>) {
    /** A lazy random point: draw each active param in declaration order, so a param's [activeIn] sees
     *  the choices its gate depends on. Inactive params are simply absent from the assignment. */
    fun sample(rng: Random): Map<String, Any> {
        val assignment = LinkedHashMap<String, Any>()
        for (p in params) if (p.activeIn(assignment)) assignment[p.name] = p.sample(rng)
        return assignment
    }
}

/**
 * Coerce a tuner's suggested [values] to each param's declared type — a [Tuner] may hand an integer
 * param back as a `Double` (Vizier does; [RandomTuner] already types them), so [IntParam]s are rounded
 * and every numeric is clamped to its declared domain and categoricals normalised to `String`. Makes
 * any backend's raw suggestion safe for the concrete space's `as Int` / `as Double` decoder.
 */
internal fun ConfigSpace.coerce(values: Map<String, Any>): Map<String, Any> {
    val byName = params.associateBy { it.name }
    return values.mapValues { (name, v) ->
        when (val p = byName[name]) {
            is IntParam -> asDouble(v).roundToInt().coerceIn(p.min, p.max)
            is DoubleParam -> asDouble(v).coerceIn(p.min, p.max)
            is CategoricalParam -> v.toString()
            null -> v
        }
    }
}

private fun asDouble(v: Any): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDouble()
    else -> error("cannot coerce '$v' (${v::class.simpleName}) to a number")
}
