package com.eignex.klause.bench.tune

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
sealed interface ConfigParam {
    val name: String

    /** Whether this param is live given the [assignment] drawn for earlier params (family gating). */
    fun activeIn(assignment: Map<String, Any>): Boolean

    /** A uniform random value from the domain. */
    fun sample(rng: Random): Any
}

/** A categorical choice over [values]. */
class CategoricalParam(
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
class IntParam(
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
class DoubleParam(
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
 * decoder in the concrete space (e.g. [LsConfigSpace]) turns a sampled assignment into a solver recipe.
 */
abstract class ConfigSpace(val params: List<ConfigParam>) {
    /** A lazy random point: draw each active param in declaration order, so a param's [activeIn] sees
     *  the choices its gate depends on. Inactive params are simply absent from the assignment. */
    fun sample(rng: Random): Map<String, Any> {
        val assignment = LinkedHashMap<String, Any>()
        for (p in params) if (p.activeIn(assignment)) assignment[p.name] = p.sample(rng)
        return assignment
    }
}
