package com.leko.forge.intellij.parser

import ForgeParser
import com.leko.forge.intellij.parser.ForgeNameRole.*
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token

/** The types the compiler knows without a declaration (TypeRes); the graph types are declared by the compiler. */
private val builtinTypes = setOf("int", "float", "bool", "boolean", "str", "Nothing", "Graph", "Vertex", "Edge", "Span")

/**
 * The role of [token], an IDENT or `@` directly below [context]: what the grammar says the name is. `null` for
 * names whose meaning only the analysis knows, e.g. an identifier in an expression.
 */
fun nameRole(context: ParserRuleContext, token: Token): ForgeNameRole? = when (context) {
    is ForgeParser.RecordContext -> TYPE_DECLARATION.takeIf { token === context.name }
    is ForgeParser.RecordFieldContext -> FIELD.takeIf { token === context.name }
    is ForgeParser.EnumContext -> TYPE_DECLARATION.takeIf { token === context.name }
    is ForgeParser.EnumItemContext -> ENUM_CONSTANT.takeIf { token === context.name }
    is ForgeParser.ConstDefContext -> CONSTANT.takeIf { token === context.name }
    is ForgeParser.ParameterContext -> PARAMETER.takeIf { token === context.name }
    is ForgeParser.FunctionContext -> when {
        token === context.name -> if (context.property != null) PROPERTY else FUNCTION_DECLARATION
        token === context.op -> SOFT_KEYWORD
        context.typeParams.any { it === token } -> TYPE_PARAMETER
        else -> null
    }
    is ForgeParser.NodeDefContext -> when (token) {
        context.name -> NODE_DECLARATION
        context.parent -> TYPE_REFERENCE
        else -> null
    }
    is ForgeParser.NodeInterfaceImplContext -> TYPE_REFERENCE.takeIf { token === context.extended || token === context.iface }
    is ForgeParser.SynDefContext -> when (token) {
        context.node -> TYPE_REFERENCE
        context.attrName -> ATTRIBUTE
        else -> null
    }
    is ForgeParser.InhDefContext -> ATTRIBUTE.takeIf { token === context.attrName }
    is ForgeParser.InhImplContext -> when (token) {
        context.node -> TYPE_REFERENCE
        context.attrName -> ATTRIBUTE
        else -> null
    }
    is ForgeParser.InhDelegateContext -> FIELD.takeIf { token === context.child }
    is ForgeParser.CircularDefContext -> when (token) {
        context.node -> TYPE_REFERENCE
        context.attrName -> ATTRIBUTE
        else -> null
    }
    is ForgeParser.CollContext -> when (token) {
        context.node -> TYPE_REFERENCE
        context.collName -> ATTRIBUTE
        else -> null
    }
    is ForgeParser.ContribContext -> when (token) {
        context.node -> TYPE_REFERENCE
        context.collName -> ATTRIBUTE
        context.alias -> LOCAL_VARIABLE
        else -> null
    }
    is ForgeParser.PortDefContext -> PORT.takeIf { token.type == ForgeParser.IDENT }
    is ForgeParser.GraphDefContext -> when (token) {
        context.name -> GRAPH
        context.rootNode -> TYPE_REFERENCE
        else -> null
    }
    is ForgeParser.FlowDefContext -> when (token) {
        context.graph -> GRAPH
        context.node -> TYPE_REFERENCE
        else -> null
    }
    is ForgeParser.FlowBinderContext -> LOCAL_VARIABLE.takeIf { token === context.name }
    is ForgeParser.FlowVertexContext -> LOCAL_VARIABLE.takeIf { token === context.name }
    is ForgeParser.FlowForContext -> LOCAL_VARIABLE.takeIf { token === context.name }
    is ForgeParser.ParserDefContext -> PARSER.takeIf { token === context.name }
    is ForgeParser.ParserOptionContext -> SOFT_KEYWORD.takeIf { token === context.key }
    is ForgeParser.ParseRuleDefContext -> when (token) {
        context.parserName -> PARSER
        context.ruleName -> PARSER_RULE
        else -> null
    }
    is ForgeParser.OperatorSpecContext -> ANNOTATION.takeIf { token === context.kind || token.type == ForgeParser.AT }
    is ForgeParser.BuiltinRuleContext -> ANNOTATION.takeIf { token === context.name || token.type == ForgeParser.AT }
    is ForgeParser.ParserLabeledContext -> LOCAL_VARIABLE.takeIf { token === context.label }
    is ForgeParser.NonterminalRuleContext -> PARSER_RULE.takeIf { token === context.name }
    is ForgeParser.NamedTypeContext -> when {
        token !== context.name -> null
        token.text in builtinTypes -> BUILTIN_TYPE
        else -> TYPE_REFERENCE
    }
    is ForgeParser.FunctionCallContext -> FUNCTION_CALL.takeIf { token === context.name }
    is ForgeParser.MethodCallContext -> METHOD_CALL.takeIf { token === context.name }
    is ForgeParser.FieldAccessContext -> FIELD.takeIf { token === context.name }
    is ForgeParser.TypePatternContext -> when (token) {
        context.receiver -> TYPE_REFERENCE
        context.name -> ENUM_CONSTANT
        else -> null
    }
    is ForgeParser.PatternVariableContext -> LOCAL_VARIABLE.takeIf { token === context.name }
    is ForgeParser.DefinedParameterContext -> LOCAL_VARIABLE.takeIf { token === context.name }
    is ForgeParser.ListComprehensionContext -> LOCAL_VARIABLE.takeIf { token === context.name || token === context.acc }
    else -> null
}
