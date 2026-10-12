package com.eignex.klause.util

/**
 * Shared label lookup and ordered-pool construction for typed arm catalogs.
 * [A] is the arm type and [R] its recipe; concrete catalogs supply their ordering
 * and engine-specific accessors.
 */
internal class ArmCatalog<A, R>(
    private val all: List<A>,
    private val label: (A) -> String,
    private val make: (A) -> R,
) {
    /** The arm whose label is [name]; a hard error listing the known labels otherwise. */
    fun fromLabel(name: String): A = all.firstOrNull { label(it) == name }
        ?: error("unknown arm '$name' (have ${all.joinToString { label(it) }})")

    /** A fresh recipe for the arm named [name] — the single string boundary. */
    fun byLabel(name: String): R = make(fromLabel(name))

    /** The labels of [order], in that order. */
    fun labels(order: List<A>): List<String> = order.map(label)

    /** One fresh recipe per arm in [order]. */
    fun ranked(order: List<A>): List<R> = order.map(make)

    /** Per-arm factories in [order] — each call builds a fresh recipe. */
    fun factories(order: List<A>): List<() -> R> = order.map { arm -> { make(arm) } }
}
