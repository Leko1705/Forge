package com.leko.forge.intellij.parser

import ForgeParser
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.leko.forge.intellij.ForgeLanguage
import org.antlr.v4.runtime.ParserRuleContext

/** A node of ForgeParser's parse tree: a rule, or an alternative labeled with `# name`. */
class ForgeRuleType(val contextClass: Class<out ParserRuleContext>, debugName: String) :
    IElementType(debugName, ForgeLanguage)

/** What a name is, e.g. the name of a node declaration or a type in a type reference; it decides its color. */
enum class ForgeNameRole {
    NODE_DECLARATION, TYPE_DECLARATION, TYPE_REFERENCE, BUILTIN_TYPE, TYPE_PARAMETER,
    ENUM_CONSTANT, FUNCTION_DECLARATION, FUNCTION_CALL, METHOD_CALL, PROPERTY,
    CONSTANT, FIELD, PARAMETER, LOCAL_VARIABLE, ATTRIBUTE, PORT, GRAPH,
    PARSER, PARSER_RULE, ANNOTATION, SOFT_KEYWORD,
}

/** A name with a [role]; it holds one IDENT token. */
class ForgeNameType(val role: ForgeNameRole) : IElementType("NAME_$role", ForgeLanguage)

object ForgeElementTypes {
    @JvmField val FILE = IFileElementType(ForgeLanguage)

    private val ruleTypes: Map<Class<*>, ForgeRuleType> =
        ForgeParser::class.java.declaredClasses
            .filter { ParserRuleContext::class.java.isAssignableFrom(it) }
            .associateWith {
                @Suppress("UNCHECKED_CAST")
                ForgeRuleType(it as Class<out ParserRuleContext>, it.simpleName.removeSuffix("Context"))
            }

    private val nameTypes: Map<ForgeNameRole, ForgeNameType> = ForgeNameRole.entries.associateWith(::ForgeNameType)

    fun of(context: ParserRuleContext): IElementType =
        ruleTypes[context.javaClass] ?: error("not a context of ForgeParser: ${context.javaClass}")

    fun of(role: ForgeNameRole): ForgeNameType = nameTypes.getValue(role)
}
