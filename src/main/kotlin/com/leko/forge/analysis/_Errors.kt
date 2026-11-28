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

fun constantAlreadyExist(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E008", "'$name' is already defined; a constant needs a name of its own")

fun invalidOperatorFunction(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E008", message)

fun invalidProperty(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E008", message)

fun emptyMapIsColon(span: SourceSpan?): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E009", "{} is an empty set; an empty map is {:}")

fun constantUsesThis(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E008", "the value of constant '$name' can not use 'this'")

fun constantDependsOnItself(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E008", "the value of constant '$name' depends on itself")

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

/** A name that a list declares twice: `field 'rhs' already defined for node Sub`, `parameter 'x' already defined for f`. */
fun nameAlreadyDefined(span: SourceSpan?, kind: String, name: String, owner: String): Diagnostic =
    Diagnostic(
        Severity.ERROR,
        span,
        "E007",
        "$kind '$name' already defined for $owner"
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
        "ext function must not have a body"
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

fun unreachableMatchCase(span: SourceSpan?): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E024", "unreachable case: the cases before it match all of its values")

fun matchNotExhaustive(span: SourceSpan?, missing: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E024", "'match' does not cover all values: '$missing' is not matched; add the case or '_ -> ...'")

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

fun portAlreadyExist(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E010", "port '$name' already exist")

fun graphAlreadyExist(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E010", "graph '$name' already exist")

fun graphNotFound(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E011", "graph '$name' not found")

fun flowAlreadyDefined(span: SourceSpan?, graph: String, node: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E011", "flow of '$node' in graph '$graph' already defined")

fun tooManyFlowBinders(span: SourceSpan?, node: String, fields: Int): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E011", "too many names; node '$node' has $fields field(s)")

fun notAFlowTarget(span: SourceSpan?, type: Type): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E012", "'$type' can not be used in a flow; expected a node, a vertex or a port")

fun portOfChildNotATarget(span: SourceSpan?): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E012", "the port of a child can not be a target; connect it instead: port(child) -> ...")

fun ownPortNotASource(span: SourceSpan?): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E012", "a port of the node itself can not be connected; only ports of children: port(child) -> ...")

fun invalidFlowStatement(span: SourceSpan?): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E012", "invalid flow statement; expected entry(..), sequence(..), a -> b, vertex, if or for")

fun portOnlyInFlows(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E012", "port '$name' can only be used inside flows")

fun opaqueType(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E013", "values of '$name' can not be created directly")

fun invalidCast(span: SourceSpan?, from: Type, to: Type): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E009", "can not cast '$from' to '$to'")

fun inhOnVertex(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E008", "inherited attribute '$name' is not available on 'Vertex', only on nodes")

fun circularAndSynDefined(span: SourceSpan?, owner: String, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E007", "attribute '$owner.$name' is defined as circular and as synthesized")

fun fieldClashesWithAttribute(span: SourceSpan?, node: String, name: String, what: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E007", "field '$node.$name' has the same name as the $what '$name'")

fun invalidParserOption(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E014", message)

fun invalidRegex(span: SourceSpan?, regex: String, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E014", "invalid regular expression '$regex': $message")

fun invalidOperator(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E015", message)

fun binaryOnly(span: SourceSpan?, what: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E015", "$what can only be used in binary parsers (parser P binary)")

fun unknownPrimitive(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E015", "unknown primitive '@$name'; expected @u1, @u2, @u4, @bit, @uint(n), @sint(n) or @bytes(n)")

fun abstractRuleWithoutAlternatives(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E015", "abstract rule '$name' has no alternatives")

fun alternativeTypeMismatch(span: SourceSpan?, rule: String, expected: Type, got: Type): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E009", "alternative of '$rule' returns '$got', which is not a '$expected'")

fun regexMatchedLiterally(span: SourceSpan?, regex: String, message: String): Diagnostic =
    Diagnostic(Severity.WARNING, span, "W002", "'$regex' is not a valid regular expression ($message); it is matched literally")

// ---- local bindings, functions as values, generic types, tuples -----------------------------------------

fun traitAlreadyExist(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E001", "trait '$name' already exist")

fun bindingAlreadyDefined(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E016", "'$name' is already defined in this 'where'")

fun bindingDependsOnItself(span: SourceSpan?, names: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E016", "the values of a 'where' depend on each other: $names")

fun invalidDestructuring(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E016", message)

fun canNotInferLambdaParameter(span: SourceSpan?, name: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E017", "can not infer the type of parameter '$name'; write { $name: Type -> ... }")

fun notAFunction(span: SourceSpan?, type: Type): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E017", "'$type' is not a function and can not be called")

fun functionsNotComparable(span: SourceSpan?): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E017", "functions can not be compared")

fun canNotUseAsValue(span: SourceSpan?, name: String, why: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E017", "'$name' can not be used as a value: $why")

fun wrongTypeArguments(span: SourceSpan?, name: String, expected: Int, got: Int): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E018", "'$name' takes $expected type argument${if (expected == 1) "" else "s"}, not $got")

fun tupleIndexOutOfRange(span: SourceSpan?, type: Type, index: Int): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E018", "'$type' has no element $index")

// ---- bounded generics -----------------------------------------------------------------------------------

fun boundNotSatisfied(span: SourceSpan?, type: Type, bound: String, why: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E019", "'$type' is not a '$bound': $why")

fun invalidBound(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E019", message)

// ---- modules --------------------------------------------------------------------------------------------

fun moduleError(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E020", message)

// ---- string interpolation -------------------------------------------------------------------------------

fun notInterpolatable(span: SourceSpan?, type: Type): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E021", "'$type' can not be put into a string: it has no 'str()'")

// ---- child attributes and forwarding --------------------------------------------------------------------

/** `child syn`: a wrong modifier, a type that holds no children, or an equation of an attribute that is not one. */
fun invalidChildAttribute(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E022", message)

/** `node N(...): I by tree`: on an interface, more than once, or a tree that does not fit. */
fun invalidForwarding(span: SourceSpan?, message: String): Diagnostic =
    Diagnostic(Severity.ERROR, span, "E023", message)
