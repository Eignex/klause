package com.eignex.klause.formats

/**
 * Base for an input-format parser's "malformed or unsupported input" error. Each front-end subclasses
 * it with its own [format] label, so the message reads `klause <format>: <message>` — giving every
 * parser one catchable supertype while keeping the per-format prefix.
 */
open class FormatException(format: String, message: String) : RuntimeException("klause $format: $message")
