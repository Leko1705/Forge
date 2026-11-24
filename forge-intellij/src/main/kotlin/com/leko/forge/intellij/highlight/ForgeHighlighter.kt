package com.leko.forge.intellij.highlight

import ForgeLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.lexer.ForgeLexerAdapter
import com.leko.forge.intellij.lexer.ForgeTokenType
import com.leko.forge.intellij.lexer.ForgeTokenTypes

/** The colors of Forge; they default to the colors of the same constructs in other languages. */
object ForgeColors {
    private fun key(name: String, fallback: TextAttributesKey) = createTextAttributesKey("FORGE_$name", fallback)

    // tokens
    val KEYWORD = key("KEYWORD", Default.KEYWORD)
    val STRING = key("STRING", Default.STRING)
    val REGEX = key("REGEX", Default.VALID_STRING_ESCAPE)
    val NUMBER = key("NUMBER", Default.NUMBER)
    val LINE_COMMENT = key("LINE_COMMENT", Default.LINE_COMMENT)
    // in the color of doc comments (green in Darcula), as block comments are mostly documentation
    val BLOCK_COMMENT = key("BLOCK_COMMENT", Default.DOC_COMMENT)
    val OPERATOR = key("OPERATOR", Default.OPERATION_SIGN)
    val BRACES = key("BRACES", Default.BRACES)
    val BRACKETS = key("BRACKETS", Default.BRACKETS)
    val PARENTHESES = key("PARENTHESES", Default.PARENTHESES)
    val DOT = key("DOT", Default.DOT)
    val COMMA = key("COMMA", Default.COMMA)
    val SEMICOLON = key("SEMICOLON", Default.SEMICOLON)
    val IDENTIFIER = key("IDENTIFIER", Default.IDENTIFIER)
    val BAD_CHARACTER = key("BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // names, by their role in the grammar
    val NODE = key("NODE", Default.CLASS_NAME)
    val TYPE_DECLARATION = key("TYPE_DECLARATION", Default.CLASS_NAME)
    val TYPE_REFERENCE = key("TYPE_REFERENCE", Default.CLASS_REFERENCE)
    val BUILTIN_TYPE = key("BUILTIN_TYPE", Default.KEYWORD)
    // the cyan of generics in Java and Kotlin: the platform's color schemes define it, Kotlin's type parameters
    // inherit it too
    val TYPE_PARAMETER = key("TYPE_PARAMETER", TextAttributesKey.find("TYPE_PARAMETER_NAME_ATTRIBUTES"))
    val ENUM_CONSTANT = key("ENUM_CONSTANT", Default.STATIC_FIELD)
    val FUNCTION_DECLARATION = key("FUNCTION_DECLARATION", Default.FUNCTION_DECLARATION)
    val FUNCTION_CALL = key("FUNCTION_CALL", Default.FUNCTION_CALL)
    val METHOD_CALL = key("METHOD_CALL", Default.INSTANCE_METHOD)
    // vals and attributes are computed, not stored: italic like Kotlin's extension properties (static fields are
    // italic in the bundled schemes), so that they differ from the fields of nodes and records
    val PROPERTY = key("PROPERTY", Default.STATIC_FIELD)
    val CONSTANT = key("CONSTANT", Default.CONSTANT)
    val FIELD = key("FIELD", Default.INSTANCE_FIELD)
    val PARAMETER = key("PARAMETER", Default.PARAMETER)
    val LOCAL_VARIABLE = key("LOCAL_VARIABLE", Default.LOCAL_VARIABLE)
    val ATTRIBUTE = key("ATTRIBUTE", Default.STATIC_FIELD)
    val PORT = key("PORT", Default.CONSTANT)
    val GRAPH = key("GRAPH", Default.GLOBAL_VARIABLE)
    val PARSER = key("PARSER", Default.CLASS_NAME)
    val PARSER_RULE = key("PARSER_RULE", Default.FUNCTION_DECLARATION)
    val ANNOTATION = key("ANNOTATION", Default.METADATA)
    val SOFT_KEYWORD = key("SOFT_KEYWORD", Default.KEYWORD)
    // modules are like packages, traits like interfaces
    val MODULE = key("MODULE", Default.IDENTIFIER)
    val TRAIT = key("TRAIT", Default.INTERFACE_NAME)

    fun of(role: ForgeNameRole): TextAttributesKey = when (role) {
        ForgeNameRole.NODE_DECLARATION -> NODE
        ForgeNameRole.TYPE_DECLARATION -> TYPE_DECLARATION
        ForgeNameRole.TYPE_REFERENCE -> TYPE_REFERENCE
        ForgeNameRole.BUILTIN_TYPE -> BUILTIN_TYPE
        ForgeNameRole.TYPE_PARAMETER -> TYPE_PARAMETER
        ForgeNameRole.ENUM_CONSTANT -> ENUM_CONSTANT
        ForgeNameRole.FUNCTION_DECLARATION -> FUNCTION_DECLARATION
        ForgeNameRole.FUNCTION_CALL -> FUNCTION_CALL
        ForgeNameRole.METHOD_CALL -> METHOD_CALL
        ForgeNameRole.PROPERTY -> PROPERTY
        ForgeNameRole.CONSTANT -> CONSTANT
        ForgeNameRole.FIELD -> FIELD
        ForgeNameRole.PARAMETER -> PARAMETER
        ForgeNameRole.LOCAL_VARIABLE -> LOCAL_VARIABLE
        ForgeNameRole.ATTRIBUTE -> ATTRIBUTE
        ForgeNameRole.PORT -> PORT
        ForgeNameRole.GRAPH -> GRAPH
        ForgeNameRole.PARSER -> PARSER
        ForgeNameRole.PARSER_RULE -> PARSER_RULE
        ForgeNameRole.ANNOTATION -> ANNOTATION
        ForgeNameRole.SOFT_KEYWORD -> SOFT_KEYWORD
        ForgeNameRole.MODULE -> MODULE
        ForgeNameRole.TRAIT -> TRAIT
        ForgeNameRole.IDENTIFIER -> IDENTIFIER
    }
}

/** Colors the tokens of ForgeLexer; the [ForgeAnnotator] colors names by their role. */
class ForgeSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ForgeLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        pack(attributes(tokenType))

    private fun attributes(type: IElementType): TextAttributesKey? {
        if (type == TokenType.BAD_CHARACTER) return ForgeColors.BAD_CHARACTER
        if (type !is ForgeTokenType) return null
        return when {
            type in ForgeTokenTypes.KEYWORDS -> ForgeColors.KEYWORD
            type in ForgeTokenTypes.NUMBERS -> ForgeColors.NUMBER
            type in ForgeTokenTypes.OPERATORS -> ForgeColors.OPERATOR
            else -> when (type.antlrType) {
                ForgeLexer.STRING -> ForgeColors.STRING
                ForgeLexer.REGEX_STRING -> ForgeColors.REGEX
                ForgeLexer.LINE_COMMENT -> ForgeColors.LINE_COMMENT
                ForgeLexer.BLOCK_COMMENT -> ForgeColors.BLOCK_COMMENT
                ForgeLexer.LBRACE, ForgeLexer.RBRACE -> ForgeColors.BRACES
                ForgeLexer.LBRACKET, ForgeLexer.RBRACKET -> ForgeColors.BRACKETS
                ForgeLexer.LPAREN, ForgeLexer.RPAREN -> ForgeColors.PARENTHESES
                ForgeLexer.DOT -> ForgeColors.DOT
                ForgeLexer.COMMA -> ForgeColors.COMMA
                ForgeLexer.SEMI -> ForgeColors.SEMICOLON
                ForgeLexer.AT -> ForgeColors.ANNOTATION
                ForgeLexer.IDENT -> ForgeColors.IDENTIFIER
                ForgeLexer.UNDERSCORE -> ForgeColors.KEYWORD
                else -> null
            }
        }
    }
}

class ForgeSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        ForgeSyntaxHighlighter()
}
