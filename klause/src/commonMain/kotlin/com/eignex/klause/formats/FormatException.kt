package com.eignex.klause.formats

/** Malformed or unsupported parser input; the message reads `klause <format>: <message>`. */
open class FormatException(format: String, message: String, cause: Throwable? = null) :
    RuntimeException("klause $format: $message", cause)
