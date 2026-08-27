package com.eignex.klause.formats.smtlib

import com.eignex.klause.formats.FormatException

/** Raised when an SMT-LIB construct outside the supported subset is encountered. */
class UnsupportedSmtException(msg: String) : FormatException("SMT-LIB", msg)
