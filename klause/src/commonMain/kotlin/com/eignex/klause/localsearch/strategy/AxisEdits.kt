package com.eignex.klause.localsearch.strategy

import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSourceCatalog

/**
 * A parsed axis-edit token from a `--param <axis>=…` value: an optional arm-family [selector], an
 * [op], and the bare [value]. Edits transform a curated recipe set in place of building one from
 * scratch, so `-e ls` can race the curated pool with one axis rewritten (`sources=-violated`,
 * `scoring=cbls.break`).
 *
 * Grammar (presolve-shaped): comma-separated tokens, each `[family.]±value`. A leading `family.`
 * scopes the token to arms whose label starts with `family` (`cbls` matches `cbls/fixed`,
 * `cbls-plateau/ils-basin`); no prefix applies to every arm. A leading `+`/`-` adds/removes a value
 * from a list axis; a bare value sets it (single-valued axes) or force-selects it (the sources list).
 */
class AxisToken(
    /** Arm-family label prefix this token applies to; `null` applies to every arm. */
    val selector: String?,
    /** Whether the token adds, removes, or sets/force-selects [value]. */
    val op: Op,
    /** The bare value, with any selector and `+`/`-` stripped. */
    val value: String,
) {
    /** Add to / remove from a list axis, or set (force-select) a value. */
    enum class Op {
        /** Add the value to a list axis. */
        ADD,

        /** Remove the value from a list axis. */
        REMOVE,

        /** Set a single-valued axis, or force-select a value on a list axis. */
        SET,
    }

    /** Whether this token's [selector] matches the arm named [label]. */
    fun appliesTo(label: String): Boolean = selector == null || label.startsWith(selector)
}

/** Parsing and application of the four-axis edit grammar over a curated [LsRecipe] set. */
object AxisEdits {
    /** Parse a comma-separated axis value into its [AxisToken]s (blanks skipped). */
    fun tokens(value: String): List<AxisToken> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { parse(it) }

    private fun parse(raw: String): AxisToken {
        val dot = raw.indexOf('.')
        val selector = if (dot > 0) raw.substring(0, dot) else null
        val body = if (dot > 0) raw.substring(dot + 1) else raw
        val op = when (body.firstOrNull()) {
            '+' -> AxisToken.Op.ADD
            '-' -> AxisToken.Op.REMOVE
            else -> AxisToken.Op.SET
        }
        val value = if (op == AxisToken.Op.SET) body else body.substring(1)
        require(value.isNotEmpty()) { "axis-edit token `$raw` has no value" }
        return AxisToken(selector, op, value)
    }

    /**
     * Apply sources-axis [tokens] (already filtered to one arm) to its [current] configured sources.
     * A bare list force-selects exactly those sources (order preserved); `+`/`-` add/remove against
     * the current set. Mixing the two forms is rejected. Values are [MoveSourceCatalog] tokens.
     */
    fun applySources(current: List<ConfiguredSource>, tokens: List<AxisToken>): List<ConfiguredSource> {
        val sets = tokens.filter { it.op == AxisToken.Op.SET }
        val mods = tokens.filter { it.op != AxisToken.Op.SET }
        require(sets.isEmpty() || mods.isEmpty()) {
            "sources: cannot mix a force-exactly list with +/- edits"
        }
        if (sets.isNotEmpty()) return sets.map { MoveSourceCatalog.configured(it.value) }
        var out = current
        for (token in mods) {
            val id = MoveSourceCatalog.idOf(token.value)
            out = when (token.op) {
                AxisToken.Op.REMOVE -> out.filterNot { it.source.id == id }

                AxisToken.Op.ADD ->
                    if (out.any { it.source.id == id }) out else out + MoveSourceCatalog.configured(token.value)

                AxisToken.Op.SET -> out
            }
        }
        return out
    }
}
