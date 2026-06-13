package com.eignex.klause.cli

/**
 * Static build identity reported by `--version`.
 *
 * Keep [VERSION] in sync with the `version` field of the MiniZinc solver configuration at
 * `klause-mzn-lib/share/minizinc/solvers/klause.msc`: MiniZinc reads the solver version from
 * the `.msc`, the CLI reports it here, and the two must agree.
 */
internal object BuildInfo {
    const val NAME = "Klause"
    const val ID = "com.eignex.klause"
    const val VERSION = "0.1.0"

    /** Single line for `--version`, MiniZinc-solver convention (`<name> <version>`). */
    fun versionLine(): String = "$NAME $VERSION"
}
