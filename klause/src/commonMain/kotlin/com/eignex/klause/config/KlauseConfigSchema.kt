package com.eignex.klause.config

import com.eignex.skema.Schema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/** camelCase word boundaries, where a dotted key inserts a separator. */
private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

/** The dotted property/env key for a knob [name]: camelCase boundaries become `.` under a `klause.`
 *  prefix, e.g. `floatBuckets` → `klause.float.buckets`; the env var is this uppercased with
 *  `.` → `_` (`KLAUSE_FLOAT_BUCKETS`). The single transform from a knob's name — never a literal. */
fun klausePropertyKey(name: String): String = "klause." + name.replace(CAMEL_BOUNDARY, "$1.$2").lowercase()

/**
 * A property delegate that yields the [klausePropertyKey] of the property it is declared on, so a
 * `klause.*` env/property knob's key comes from its Kotlin name rather than a duplicated string:
 * `val portfolioArms by propertyKnob()` makes `portfolioArms == "klause.portfolio.arms"`.
 */
fun propertyKnob(): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, String>> =
    PropertyDelegateProvider { _, property ->
        val key = klausePropertyKey(property.name)
        ReadOnlyProperty { _, _ -> key }
    }

/**
 * The wire description of one [KlauseConfig] knob: its value type and built-in default. The
 * [Schema] entry name (the property the knob is declared on) carries the knob's name, so a
 * downstream consumer can decode a [com.eignex.skema.SchemaDef] of these and learn every knob,
 * its type and its default without sharing klause's Kotlin code.
 */
@Serializable
sealed interface ConfigSpec {
    /** The knob's built-in default, as it serialises into the schema definition. */
    val default: String
}

/** A Boolean knob with its [value] default. */
@Serializable
@SerialName("bool")
data class BoolConfigSpec(
    /** The knob's built-in default. */
    val value: Boolean,
) : ConfigSpec {
    override val default: String get() = value.toString()
}

/** An Int knob with its [value] default. */
@Serializable
@SerialName("int")
data class IntConfigSpec(
    /** The knob's built-in default. */
    val value: Int,
) : ConfigSpec {
    override val default: String get() = value.toString()
}

/** A Long knob with its [value] default. */
@Serializable
@SerialName("long")
data class LongConfigSpec(
    /** The knob's built-in default. */
    val value: Long,
) : ConfigSpec {
    override val default: String get() = value.toString()
}

/**
 * A typed handle for one [KlauseConfig] knob: its [name] (captured from the property it is
 * declared on, never spelled as a literal) and how to apply a raw string value onto a config.
 */
class ConfigKey internal constructor(
    /** The knob name, e.g. `floatBuckets` — the [Schema] entry name and the JSON/YAML key. */
    val name: String,
    private val apply: (KlauseConfig, String) -> KlauseConfig,
) {
    /** This knob's [klausePropertyKey], e.g. `klause.float.buckets`. */
    val propertyKey: String get() = klausePropertyKey(name)

    /** Parse [raw] and fold it onto [config]; an unparseable value leaves [config] untouched. */
    fun applyRaw(config: KlauseConfig, raw: String): KlauseConfig = apply(config, raw)
}

/** Boolean knob spellings that read as false; any other present value reads as true. */
private val FALSEY = setOf("0", "false", "off", "no")

/**
 * The schema of env/property/file-overridable [KlauseConfig] knobs. Each `by bool/int/long(...)`
 * registers a serialisable [ConfigSpec] under the property's name (via [Schema.register]) and a
 * typed [ConfigKey] in [keys]; the property name is therefore the single source of the knob's name
 * (env var, dotted property and JSON/YAML key all derive from it) and the default comes from
 * [KlauseConfig.DEFAULT] (so it is never restated). Declaring a knob here — and nowhere else — is
 * what makes it env-configurable; [KlauseConfig.fromProps] folds overrides over [keys].
 *
 * Presolve is deliberately absent: it is set programmatically or via the CLI `--presolve` flag.
 */
object KlauseConfigSchema : Schema<ConfigSpec>() {
    private val mutableKeys = mutableListOf<ConfigKey>()

    /** Every declared knob, in declaration order. */
    val keys: List<ConfigKey> get() = mutableKeys

    private fun bool(default: Boolean, set: (KlauseConfig, Boolean) -> KlauseConfig) =
        register(BoolConfigSpec(default)) { name ->
            ConfigKey(name) { c, raw -> set(c, raw.trim().lowercase() !in FALSEY) }.also { mutableKeys += it }
        }

    private fun int(default: Int, set: (KlauseConfig, Int) -> KlauseConfig) = register(IntConfigSpec(default)) { name ->
        ConfigKey(name) { c, raw -> raw.trim().toIntOrNull()?.let { set(c, it) } ?: c }.also { mutableKeys += it }
    }

    private fun long(default: Long, set: (KlauseConfig, Long) -> KlauseConfig) =
        register(LongConfigSpec(default)) { name ->
            ConfigKey(name) { c, raw -> raw.trim().toLongOrNull()?.let { set(c, it) } ?: c }.also { mutableKeys += it }
        }

    /** Override for [KlauseConfig.pinAbsentOptVars]. */
    val pinAbsentOptVars by bool(KlauseConfig.DEFAULT.pinAbsentOptVars) { c, v -> c.copy(pinAbsentOptVars = v) }

    /** Override for [KlauseConfig.unboundedIntLo]. */
    val unboundedIntLo by long(KlauseConfig.DEFAULT.unboundedIntLo) { c, v -> c.copy(unboundedIntLo = v) }

    /** Override for [KlauseConfig.unboundedIntHi]. */
    val unboundedIntHi by long(KlauseConfig.DEFAULT.unboundedIntHi) { c, v -> c.copy(unboundedIntHi = v) }

    /** Override for [KlauseConfig.largeSpanThreshold]. */
    val largeSpanThreshold by long(KlauseConfig.DEFAULT.largeSpanThreshold) { c, v ->
        c.copy(largeSpanThreshold = v)
    }

    /** Override for [KlauseConfig.floatBuckets]. */
    val floatBuckets by int(KlauseConfig.DEFAULT.floatBuckets) { c, v -> c.copy(floatBuckets = v) }

    /** Override for [KlauseConfig.floatScale]. */
    val floatScale by long(KlauseConfig.DEFAULT.floatScale) { c, v -> c.copy(floatScale = v) }

    /** Override for [KlauseConfig.lpMaxTableauCells]. */
    val lpMaxTableauCells by long(KlauseConfig.DEFAULT.lpMaxTableauCells) { c, v -> c.copy(lpMaxTableauCells = v) }

    /** Override for [KlauseConfig.lpCeilingTableauCells]. */
    val lpCeilingTableauCells by long(KlauseConfig.DEFAULT.lpCeilingTableauCells) { c, v ->
        c.copy(lpCeilingTableauCells = v)
    }

    /** Override for [KlauseConfig.bitsetThreshold]. */
    val bitsetThreshold by int(KlauseConfig.DEFAULT.bitsetThreshold) { c, v -> c.copy(bitsetThreshold = v) }
}
