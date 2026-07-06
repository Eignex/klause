package com.eignex.klause.util

/**
 * The shared arm-catalog ritual behind the two portfolio arm catalogs (`LsCatalog` / `BacktrackCatalog`).
 * Given the full set of [all] arms, a [label] accessor, and a [make] factory, it owns the string-boundary
 * accessors (`byLabel` / `fromLabel`) and the order-driven pool builders (`labels` / `ranked` / `factories`
 * over a *supplied* ordered arm list). The concrete catalog keeps its own ordering source — a single
 * ranked list, or per-`Kind` orders — and any engine-specific accessors (`diverse` / `auto`); this is a
 * thin shared core, not a full merge.
 *
 * [A] is the typed arm (an enum), [R] the recipe type it maps to.
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
