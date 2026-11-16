package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtSessionSink

internal expect fun openSmtSessionAppend(path: String): SmtSessionSink
