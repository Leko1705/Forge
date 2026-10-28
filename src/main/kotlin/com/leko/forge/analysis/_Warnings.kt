package com.leko.forge.analysis

import com.leko.forge.util.Diagnostic
import com.leko.forge.util.Severity
import com.leko.forge.model.SourceSpan


fun targetWrittenSuccessfully(span: SourceSpan?, targetName: String, outputFile: String): Diagnostic =
    Diagnostic(
        Severity.INFO,
        span,
        "I001",
        "wrote $targetName to $outputFile"
    )

fun parserConflict(span: SourceSpan?, parserName: String, description: String): Diagnostic =
    Diagnostic(
        Severity.WARNING,
        span,
        "W101",
        "parser $parserName: $description"
    )

fun terminalMatchesEmpty(span: SourceSpan?, regex: String): Diagnostic =
    Diagnostic(
        Severity.WARNING,
        span,
        "W102",
        "terminal '$regex' can match the empty text; empty matches are ignored by the scanner"
    )

fun regexTakenLiterally(span: SourceSpan?, terminal: String, reason: String, text: String): Diagnostic =
    Diagnostic(
        Severity.WARNING,
        span,
        "W103",
        "$terminal is not a valid regular expression ($reason), it matches the text '$text' literally; write ${com.leko.forge.utils.kotlinStringLiteral(text)} for literal text"
    )
