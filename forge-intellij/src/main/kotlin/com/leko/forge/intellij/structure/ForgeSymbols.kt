package com.leko.forge.intellij.structure

import com.intellij.icons.AllIcons
import com.leko.forge.intellij.parser.ForgeNameRole
import com.leko.forge.intellij.psi.ForgeDeclaredName
import javax.swing.Icon

/** How declarations look in the structure view and in Go to Symbol. */
object ForgeSymbols {

    /** What Go to Class finds: the types. */
    val TYPE_ROLES = setOf(ForgeNameRole.NODE_DECLARATION, ForgeNameRole.TYPE_DECLARATION, ForgeNameRole.PARSER)

    /** What Go to Symbol finds: what can be used from elsewhere (not locals and parameters). */
    val SYMBOL_ROLES = TYPE_ROLES + setOf(
        ForgeNameRole.FUNCTION_DECLARATION, ForgeNameRole.PROPERTY, ForgeNameRole.CONSTANT, ForgeNameRole.ATTRIBUTE,
        ForgeNameRole.PARSER_RULE, ForgeNameRole.ENUM_CONSTANT, ForgeNameRole.FIELD, ForgeNameRole.PORT, ForgeNameRole.GRAPH,
    )

    /** A declaration in the parameters of a node is a child (a field of the node), elsewhere a parameter. */
    fun isNodeChild(name: ForgeDeclaredName): Boolean =
        name.role == ForgeNameRole.PARAMETER && name.parent?.parent?.let { (it.node.elementType as? com.leko.forge.intellij.parser.ForgeRuleType)?.ruleName } == "NodeDef"

    fun icon(name: ForgeDeclaredName): Icon = when (name.role) {
        ForgeNameRole.NODE_DECLARATION -> if (name.parent?.text?.contains("(") == true) AllIcons.Nodes.Class else AllIcons.Nodes.Interface
        ForgeNameRole.TYPE_DECLARATION -> if (name.parent?.text?.startsWith("enum") == true) AllIcons.Nodes.Enum else AllIcons.Nodes.Record
        ForgeNameRole.PARSER -> AllIcons.Nodes.Class
        ForgeNameRole.FUNCTION_DECLARATION -> AllIcons.Nodes.Function
        ForgeNameRole.PROPERTY, ForgeNameRole.ATTRIBUTE -> AllIcons.Nodes.Property
        ForgeNameRole.CONSTANT, ForgeNameRole.PORT -> AllIcons.Nodes.Constant
        ForgeNameRole.PARSER_RULE -> AllIcons.Nodes.Method
        ForgeNameRole.ENUM_CONSTANT -> AllIcons.Nodes.Enum
        ForgeNameRole.FIELD -> AllIcons.Nodes.Field
        ForgeNameRole.GRAPH -> AllIcons.Nodes.DataTables
        ForgeNameRole.PARAMETER -> if (isNodeChild(name)) AllIcons.Nodes.Field else AllIcons.Nodes.Parameter
        else -> AllIcons.Nodes.Variable
    }

    /** The text of the declaration up to its body, on one line: `syn Add.type: Type`, `node Num(value: int): Expr`. */
    fun header(name: ForgeDeclaredName): String {
        val decl = name.parent ?: return name.text
        val stop = decl.node.getChildren(null).firstOrNull {
            val type = it.elementType.toString()
            type == "ASSIGN" || (type == "ARROW" && decl.text.startsWith("rule")) ||
                (type == "LBRACE" && !decl.text.startsWith("record") && !decl.text.startsWith("enum"))
        }
        val end = stop?.startOffset ?: decl.textRange.endOffset
        val text = decl.containingFile.text.substring(decl.textRange.startOffset, end).replace(Regex("\\s+"), " ").trim()
        return if (text.length > 100) text.take(97) + "..." else text
    }
}
