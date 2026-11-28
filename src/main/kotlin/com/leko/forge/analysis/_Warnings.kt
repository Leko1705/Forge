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

fun unnecessaryNotNull(span: SourceSpan?, type: String): Diagnostic =
    Diagnostic(Severity.WARNING, span, "W005", "unnecessary '!!': the value is a '$type', which is never null")

fun nullComparisonAlwaysSame(span: SourceSpan?, type: String, result: Boolean): Diagnostic =
    Diagnostic(Severity.WARNING, span, "W006", "the condition is always $result: the value is a '$type', which is never null")

fun unnecessaryOptionalType(span: SourceSpan?, declaration: String, type: String): Diagnostic =
    Diagnostic(Severity.WARNING, span, "W007", "unnecessary '?': no value of $declaration is null, so it can be a '$type'")

fun unnecessaryElvis(span: SourceSpan?, type: String): Diagnostic =
    Diagnostic(Severity.WARNING, span, "W004", "unnecessary '?:': the left side is a '$type', which is never null")