package com.leko.forge.intellij.parser

import ForgeParser
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.leko.forge.intellij.ForgeLanguage
import org.antlr.v4.runtime.ParserRuleContext

/** A node of ForgeParser's parse tree: a rule, or an alternative labeled with `# name`; [ruleName] says which. */
class ForgeRuleType(val contextClass: Class<out ParserRuleContext>, val ruleName: String) :
    IElementType(ruleName, ForgeLanguage)

/** What a name is, e.g. the name of a node declaration or a type in a type reference; it decides its color. */
enum class ForgeNameRole {
    NODE_DECLARATION, TYPE_DECLARATION, TYPE_REFERENCE, BUILTIN_TYPE, TYPE_PARAMETER,
    ENUM_CONSTANT, FUNCTION_DECLARATION, FUNCTION_CALL, METHOD_CALL, PROPERTY,
    CONSTANT, FIELD, PARAMETER, LOCAL_VARIABLE, ATTRIBUTE, PORT, GRAPH,
    PARSER, PARSER_RULE, ANNOTATION, SOFT_KEYWORD,
    /** An identifier in an expression: a variable, a field, an attribute, a constant, ... */
    IDENTIFIER,
}

/** A name with a [role]; it holds one IDENT token, and [declares][declaration] or uses something. */
class ForgeNameType(val role: ForgeNameRole, val declaration: Boolean) :
    IElementType((if (declaration) "DECLARATION_" else "NAME_") + role, ForgeLanguage)

object ForgeElementTypes {
    @JvmField val FILE = IFileElementType(ForgeLanguage)

    /** The path of an `import`, the string: it refers to the imported file. */
    @JvmField val IMPORT_PATH = IElementType("IMPORT_PATH", ForgeLanguage)

    private val ruleTypes: Map<Class<*>, ForgeRuleType> =
        ForgeParser::class.java.declaredClasses
            .filter { ParserRuleContext::class.java.isAssignableFrom(it) }
            .associateWith {
                @Suppress("UNCHECKED_CAST")
                ForgeRuleType(it as Class<out ParserRuleContext>, it.simpleName.removeSuffix("Context"))
            }

    private val nameTypes: Map<Pair<ForgeNameRole, Boolean>, ForgeNameType> =
        ForgeNameRole.entries.flatMap { listOf(it to true, it to false) }.associateWith { ForgeNameType(it.first, it.second) }

    fun of(context: ParserRuleContext): IElementType =
        ruleTypes[context.javaClass] ?: error("not a context of ForgeParser: ${context.javaClass}")

    fun of(role: ForgeNameRole, declaration: Boolean): ForgeNameType = nameTypes.getValue(role to declaration)
}
