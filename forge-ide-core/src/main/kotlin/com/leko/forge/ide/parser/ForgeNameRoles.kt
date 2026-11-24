package com.leko.forge.ide.parser

import ForgeParser
import com.leko.forge.ide.parser.ForgeNameRole.*
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token

/** What a name is, e.g. the name of a node declaration or a type in a type reference; it decides its color. */
enum class ForgeNameRole {
    NODE_DECLARATION, TYPE_DECLARATION, TYPE_REFERENCE, BUILTIN_TYPE, TYPE_PARAMETER,
    ENUM_CONSTANT, FUNCTION_DECLARATION, FUNCTION_CALL, METHOD_CALL, PROPERTY,
    CONSTANT, FIELD, PARAMETER, LOCAL_VARIABLE, ATTRIBUTE, PORT, GRAPH,
    PARSER, PARSER_RULE, ANNOTATION, SOFT_KEYWORD, MODULE, TRAIT,
    /** An identifier in an expression: a variable, a field, an attribute, a constant, ... */
    IDENTIFIER,
}

/** The types the compiler knows without a declaration (TypeRes); the graph types are declared by the compiler. */
private val builtinTypes = setOf("int", "long", "float", "bool", "boolean", "str", "Nothing", "Graph", "Vertex", "Edge", "Span")

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
        else -> null
    }
    is ForgeParser.TypeParamContext -> TYPE_PARAMETER.takeIf { token === context.name }
    is ForgeParser.TraitDefContext -> TRAIT.takeIf { token === context.name }
    is ForgeParser.TraitMemberContext -> when (token) {
        context.name -> if (context.property != null) PROPERTY else FUNCTION_DECLARATION
        context.op -> SOFT_KEYWORD
        else -> null
    }
    is ForgeParser.ModDefContext -> MODULE.takeIf { token === context.name }
    is ForgeParser.UseDeclContext -> MODULE.takeIf { token === context.path }
    is ForgeParser.LambdaParamContext -> PARAMETER.takeIf { token === context.name }
    is ForgeParser.BindingContext -> LOCAL_VARIABLE.takeIf { token === context.name || context.names.any { it === token } }
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
        typeParameterDeclaration(context, token) != null -> TYPE_PARAMETER
        // the type that has the trait: a keyword of traits, as `this` is one of expressions
        token.text == "Self" && generateSequence(context as ParserRuleContext) { it.parent as? ParserRuleContext }.any { it is ForgeParser.TraitDefContext } -> SOFT_KEYWORD
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
    // what an identifier in an expression is, only the analysis knows (ForgeSemantics)
    is ForgeParser.IdentifierContext -> IDENTIFIER
    else -> null
}

/**
 * The declaration of the type parameter [token] names (`T` in `fun <T> List<T>.second(): T?`, `record Pair<A, B>`),
 * if it names one.
 */
fun typeParameterDeclaration(context: ParserRuleContext, token: Token): Token? =
    generateSequence(context) { it.parent as? ParserRuleContext }
        .mapNotNull { c ->
            when (c) {
                is ForgeParser.FunctionContext -> c.typeParams()
                is ForgeParser.RecordContext -> c.typeParams()
                is ForgeParser.EnumContext -> c.typeParams()
                else -> null
            }
        }.firstOrNull()
        ?.typeParam()?.map { it.name }?.firstOrNull { it.text == token.text }

/** Whether [token], a name with a [nameRole] directly below [context], declares something, or uses a declaration. */
fun isDeclaration(context: ParserRuleContext, token: Token): Boolean = when (context) {
    is ForgeParser.RecordContext -> token === context.name
    is ForgeParser.RecordFieldContext -> token === context.name
    is ForgeParser.EnumContext -> token === context.name
    is ForgeParser.EnumItemContext -> token === context.name
    is ForgeParser.ConstDefContext -> token === context.name
    is ForgeParser.ParameterContext -> token === context.name
    is ForgeParser.FunctionContext -> token === context.name
    is ForgeParser.TypeParamContext -> token === context.name
    is ForgeParser.TraitDefContext -> token === context.name
    is ForgeParser.TraitMemberContext -> token === context.name
    is ForgeParser.ModDefContext -> token === context.name
    is ForgeParser.LambdaParamContext -> token === context.name
    is ForgeParser.BindingContext -> true
    is ForgeParser.NodeDefContext -> token === context.name
    is ForgeParser.SynDefContext -> token === context.attrName
    is ForgeParser.InhDefContext -> token === context.attrName
    is ForgeParser.CircularDefContext -> token === context.attrName
    is ForgeParser.CollContext -> token === context.collName
    is ForgeParser.ContribContext -> token === context.alias
    is ForgeParser.PortDefContext -> true
    is ForgeParser.GraphDefContext -> token === context.name
    is ForgeParser.FlowBinderContext -> true
    is ForgeParser.FlowVertexContext -> token === context.name
    is ForgeParser.FlowForContext -> token === context.name
    is ForgeParser.ParserDefContext -> token === context.name
    is ForgeParser.ParseRuleDefContext -> token === context.ruleName
    is ForgeParser.ParserLabeledContext -> token === context.label
    is ForgeParser.PatternVariableContext -> true
    is ForgeParser.DefinedParameterContext -> true
    else -> false
}
