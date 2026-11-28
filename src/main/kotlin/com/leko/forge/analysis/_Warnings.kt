package com.leko.forge.analysis

import com.leko.forge.util.Diagnostic
import com.leko.forge.util.Severity
import com.leko.forge.model.SourceSpan


fun targetWrittenSuccessfully(span: SourceSpan?, targetName: String, outputFile: String): Diagnostic =
    Diagnostic(
        Severity.WARNING,
        span,
        "W001",
        "wrote $targetName to $outputFile"
    )

fun unnecessarySafeCall(span: SourceSpan?, type: String): Diagnostic =
    Diagnostic(Severity.WARNING, span, "W003", "unnecessary safe call: the receiver is a '$type', which is never null")
