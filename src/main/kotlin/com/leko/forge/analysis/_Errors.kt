package com.leko.forge.analysis

import com.leko.forge.model.Kind
import com.leko.forge.types.Type
import com.leko.forge.util.Diagnostic
import com.leko.forge.util.Severity
import com.leko.forge.model.SourceSpan



fun nodeAlreadyExist(span: SourceSpan?, nodeName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E001",
        "node '$nodeName' already exist"
    )

fun interfaceAlreadyExist(span: SourceSpan?, interfaceName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E002",
        "interface node '$interfaceName' already exist"
    )

fun enumAlreadyExist(span: SourceSpan?, enumName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E003",
        "enum '$enumName' already exist"
    )

fun recordAlreadyExist(span: SourceSpan?, recordName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E004",
        "record '$recordName' already exist"
    )

fun functionAlreadyExist(span: SourceSpan?, functionName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E005",
        "function '$functionName' already exist"
    )

fun parserAlreadyExist(span: SourceSpan?, functionName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E005",
        "parser '$functionName' already exist"
    )

fun parseRuleAlreadyExist(span: SourceSpan?, ruleName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E005",
        "parser rule $ruleName already exist"
    )

fun abstractParseRuleAlreadyExist(span: SourceSpan?, ruleName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E005",
        "abstract parser rule $ruleName already exist"
    )


fun inheritedAttributeAlreadyExist(span: SourceSpan?, attrName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E005",
        "function '$attrName' already exist"
    )

fun methodAlreadyExist(span: SourceSpan?, methodName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E005",
        "method '$methodName' already exist"
    )

fun nodeNotFound(span: SourceSpan?, nodeName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E006",
        "node '$nodeName' not found"
    )

fun interfaceNodeNotFound(span: SourceSpan?, interfaceName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E006",
        "interface node '$interfaceName' not found"
    )

fun syntheticRootAttributeNotFound(span: SourceSpan?, attrName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not find root for inherited attribute '$attrName'"
    )

fun collectionAttributeNotFound(span: SourceSpan?, type: String, attrName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not find collection attribute '$type.$attrName'"
    )

fun functionNotFound(span: SourceSpan?, functionName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E006",
        "function '$functionName' not found"
    )

fun parserNotFound(span: SourceSpan?, parserName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E006",
        "parser '$parserName' not found"
    )

fun attributeAlreadyDefined(span: SourceSpan?, owner: String, attrName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E007",
        "attribute '$attrName' already defined for $owner"
    )

fun attributeNotFound(span: SourceSpan?, owner: String, attrName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "attribute '$owner.$attrName' not found"
    )

fun parserRuleNotFound(span: SourceSpan?, parserName: String, ruleName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "parser rule '$parserName.$ruleName' not found"
    )

fun methodNotFound(span: SourceSpan?, owner: String, attrName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "method '$owner.$attrName' not found"
    )

fun missingFunctionBody(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "function body is missing"
    )

fun externalFunctionMustHaveNoBody(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "external function must not have a body"
    )

fun missingSynAttributeImplementationFor(span: SourceSpan?, owner: String, attrName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "Synthetic attribute '$attrName' is not implemented for $owner"
    )

fun canNotSplit(span: SourceSpan?, type: Type): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "Can not split '$type'"
    )


fun typeMismatch(span: SourceSpan?, expected: Type, got: Type): Diagnostic =
    typeMismatch(span, listOf(expected), listOf(got))

fun typeMismatch(span: SourceSpan?, expected: List<Type>, got: Type): Diagnostic =
    typeMismatch(span, expected, listOf(got))

fun typeMismatch(span: SourceSpan?, expected: List<Type>, got: List<Type>): Diagnostic {
    val message = if (expected.size == 1) {
        "expected type '${expected[0]}' but got ${got.joinToString(" and ")}"
    }
    else {
        "expected any type of ${expected.joinToString(" and ")} but got ${got.joinToString(" and ")}"
    }

    return Diagnostic(
        Severity.ERROR,
        span,
        "E009",
        message
    )
}

fun canNotPerformUnaryOperation(span: SourceSpan?, type: Type, operation: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not apply $operation $type"
    )

fun canNotPerformBinaryOperation(span: SourceSpan?, lhs: Type, rhs: Type, operation: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not apply '$operation' on $lhs and $rhs"
    )


fun tooManyArguments(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "too many arguments"
    )

fun listTypeIsAmbiguous(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not infer list type"
    )

fun canNotInferType(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not infer type"
    )

fun tooFewArguments(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "too few arguments"
    )

fun canNotFindIdentifier(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not find '$name'"
    )

fun canNotFindParser(span: SourceSpan?, parserName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not find parser '$parserName'"
    )

fun invalidSymbolKind(span: SourceSpan?, got: Kind, expected: Set<Kind>): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "invalid symbol kind; expected ${expected.joinToString(", ")} but got $got"
    )

fun noMatchCaseSpecified(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "no match case specified"
    )

fun cyclicDependencyDetected(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "cyclic dependency detected for '$name'"
    )


fun invalidToListDelegation(span: SourceSpan?, childName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not delegate to a list of children; use list-delegation-pattern ($childName[..] <- ..) instead"
    )

fun invalidListAccessDelegation(span: SourceSpan?, childName: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not apply list-delegation to a single child; use ($childName <- ..) instead"
    )

fun invalidExternalInhAttribAccess(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not access inherited attributed on others than 'this'"
    )

fun collectionAttributeExpected(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "collection attribute expected"
    )

fun ruleLabelingNotAllowedHere(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "labeling not allowed here"
    )

fun canNotInferParseValue(span: SourceSpan?): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        "can not infer parsed value"
    )

fun parseError(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E008",
        message
    )