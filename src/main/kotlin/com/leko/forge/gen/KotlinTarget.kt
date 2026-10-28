package com.leko.forge.gen

import com.leko.forge.context.Context
import com.leko.forge.llir.Function
import com.leko.forge.llir.*
import com.leko.forge.types.*

object KotlinTarget : Target {

    private val INDENTATION = " ".repeat(4)

    override val extension: String get() = ".kt"

    override val name: String get() = "kotlin"

    override fun generate(module: LLIRModule, context: Context): ByteArray {
        return module.generateImpl().toByteArray()
    }

    private fun LLIRModule.generateImpl(): String {
        val sb = StringBuilder()
        sb.appendLine()

        with(sb) {
            appendLine($$"interface $Node {")
            appendLine($$"    var $parent: $Node?")
            for (inh in inherited.values) {
                append("    val ${inh.name}: ").generateTypeRepr(inh.type).appendLine()
                append($$"    fun compute_$${inh.name}($child: $Node): ").generateTypeRepr(inh.type).appendLine()
            }
            appendLine("}\n")
            appendLine($$"abstract class $NodeBase : $Node {")
            appendLine($$"    override var $parent: $Node? = null")
            for (inh in inherited.values) {
                append("    override val ${inh.name}: ").generateTypeRepr(inh.type)
                    .append($$" by lazy { val p = $parent; if (p != null) p.compute_$${inh.name}(this) else ")
                    .generateValue(inh.init).appendLine(" }")
                append($$"    override fun compute_$${inh.name}($child: $Node): ").generateTypeRepr(inh.type).appendLine(" = ${inh.name}")
            }
            appendLine("}\n")
        }

        enums.forEach { sb.generateEnum(it) }
        nodes.forEach { sb.generateNode(this, it) }
        functions.forEach { sb.generateFunction(it) }
        return sb.toString()
    }

    private fun StringBuilder.generateNode(module: LLIRModule, node: Node) {
        if (node.isInterface) {
            appendLine("// interface node ${node.name}")
            append("sealed interface ${node.name} : ").append($$"$Node")
            node.parents.forEach { append(", ${it.name}") }
            appendLine(" {")
            for ((name, type) in node.synthetics.values) {
                append("${INDENTATION}// syn $name: ").generateTypeRepr(type).appendLine()
                append(INDENTATION)
                if (node.hasParent { parent -> parent.synthetics.values.any { it.name == name && it.type == type } }) {
                    append("override ")
                }
                append("val $name: ").generateTypeRepr(type).appendLine()

            }
            appendLine("}")
        }
        else {
            appendLine("// node ${node.name}")
            append("class ${node.name}(")
            if (node.fields.isNotEmpty()) {
                node.fields.entries.first().also { append("val ").generateParameter(it.key, it.value) }
                for ((name, type) in node.fields.entries.drop(1)) {
                    append(", ").append("val ").generateParameter(name, type)
                }
            }
            append($$") : $Node()")
            node.parents.forEach { append(", ${it.name}") }
            appendLine(" {")
            for ((name, type, body) in node.synthetics.values) {
                append("${INDENTATION}// syn $name: ").generateTypeRepr(type).appendLine()
                append(INDENTATION)
                if (node.hasParent { parent -> parent.synthetics.values.any { it.name == name && it.type == type } }) {
                    append("override ")
                }
                append("val $name: ").generateTypeRepr(type)..appendLine(" by lazy {")
                append("${INDENTATION.repeat(2)}return ").generateValue(body).appendLine()
                appendLine("${INDENTATION}}")
            }
            for (inh in node.inherited.values) {
                append("${INDENTATION}// inh $name: ").generateTypeRepr(inh.type).appendLine()

                val impl = node.inherited[name]

                append(INDENTATION).append($$"override fun compute_$${name}(child: $Node): ").generateTypeRepr(inh.type).appendLine(" {")
                    .append(INDENTATION.repeat(2)).append($$"if ($parent != null) $parent.compute_$${name}(this) else ").generateValue(inh.default.body).appendLine()
                    .appendLine("${INDENTATION}}")
            }
            for ((name, type) in node.collections.values) {
                append("${INDENTATION}// coll $name: ").generateTypeRepr(type).appendLine()
                append("${INDENTATION}val $name: ").generateTypeRepr(type).appendLine()
            }
            appendLine("}")
        }
    }

    private fun StringBuilder.generateEnum(enum: LLIREnum) {
        appendLine("// enum ${enum.name}")
        appendLine("enum class ${enum.name} {")
        enum.enumItems.forEach { generateEnumItem(it) }
        appendLine("}")
    }

    private fun StringBuilder.generateEnumItem(enum: EnumItem) {
        append("$INDENTATION${enum.itemName}(")
        enum.fields.forEach { generateParameter(it.key, it.value) }
        appendLine(")  // variant ${enum.enumName}.${enum.name}")
    }

    private fun StringBuilder.generateParameter(name: String, type: Type): StringBuilder {
        append(name).append(": ").generateTypeRepr(type)
        return this
    }

    private fun StringBuilder.generateFunction(function: Function): StringBuilder {
        if (function.body == null) return this
        appendLine("// function ${function.name}")
        append("fun ")
        if (function.receiver != null) generateTypeRepr(function.receiver).append(".")
        append("${function.name}(")
        function.parameters.forEach { generateParameter(it.name, it.type) }
        append("): ").generateTypeRepr(function.returnType).appendLine(" {")
        append("${INDENTATION}return ").generateValue(function.body!!).appendLine()
            .appendLine("}")
        return this
    }

    private fun StringBuilder.generateTypeRepr(type: Type): StringBuilder {
        when (type) {
            BoolType -> append("Boolean")
            is FunctionType -> {
                append("(")
                var first = true
                for (param in type.parameters) {
                    if (!first) append(", ")
                    generateTypeRepr(param)
                    first = false
                }
                append(") -> ")
                generateTypeRepr(type.returnType)
            }
            is ComplexType -> append(type.name)
            FloatType -> append("Float")
            IntType -> append("Int")
            is ListType -> append("MutableList<").generateTypeRepr(type.elem).append(">")
            is MapType -> "Map<${generateTypeRepr(type.key)}, ${generateTypeRepr(type.value)}>"
            NullType -> append("Unit?")
            is OptionalType -> generateTypeRepr(type.elem).append("?")
            is ParserType -> append(type.name)
            StrType -> append("String")
            else -> throw IllegalStateException("should not reach here for $type")
        }
        return this
    }

    private fun StringBuilder.generateValue(value: Value): StringBuilder {

        fun Value.accessField(name: String) {
            this@generateValue.generateValue(this).append(".$name")
        }

        fun Value.binaryOp(other: Value, op: String) {
            this@generateValue.generateValue(this).append(" $op ").generateValue(other)
        }

        fun StringBuilder.generateCommaSeparated(values: List<Value>): StringBuilder {
            if (values.isEmpty()) return this
            generateValue(values.first())
            for (value in values.drop(1)) {
                append(", ").generateValue(value)
            }
            return this
        }

        when (value) {
            is AccessEnumField -> value.accessed.accessField(value.name)
            is AccessListContainer -> generateValue(value.container).append("[").generateValue(value.key).append("]")
            is AccessMapContainer -> generateValue(value.container).append("[").generateValue(value.key).append("]")
            is AccessNodeAttr -> value.receiver.accessField(value.attr.name)
            is AccessNodeField -> value.accessed.accessField(value.name)
            is AccessParentInhAttr -> append(value.name)
            is AccessRecordField -> value.accessed.accessField(value.name)
            is AddFloat -> value.lhs.binaryOp(value.rhs, "+")
            is AddInt -> value.lhs.binaryOp(value.rhs, "+")
            is AndBool -> value.lhs.binaryOp(value.rhs, "and")
            is AndInt -> value.lhs.binaryOp(value.rhs, "and")
            is AppendAllListItems -> generateValue(value.list).append(".also { addAll(").generateValue(value.elements).append(") }")
            is AppendListItem -> generateValue(value.list).append(".also { add(").generateValue(value.element).append(") }")
            is BoolEquals -> value.lhs.binaryOp(value.rhs, "==")
            is Call -> (if (value.receiver != null) generateValue(value.receiver).append(".") else this)
                .append(value.target.name).append('(').generateCommaSeparated(value.args).append(")")
            is Cast -> generateValue(value.value).append(" as ").append(value.target.name)
            is CreateEnum -> append("${value.target.enumName}.${value.target.itemName}").append("(").generateCommaSeparated(value.args).append(")")
            is CreateNode -> append(value.target.name).append("(").generateCommaSeparated(value.args).append(")")
            is CreateRecord -> append(value.target.name).append("(").generateCommaSeparated(value.args).append(")")
            is DivFloat -> value.lhs.binaryOp(value.rhs, "/")
            is DivInt -> value.lhs.binaryOp(value.rhs, "/")
            is EnumEquals -> value.lhs.binaryOp(value.rhs, "==")
            is Float2Int -> generateValue(value.value).append(".toInt()")
            is FloatEquals -> value.lhs.binaryOp(value.rhs, "==")
            is FoldAcc -> append(value.name)
            is FoldItem -> append(value.name)
            is FoldOp -> generateValue(value.iterable).append(".map { ${value.acc.name}, ${value.item.name} -> ").generateValue(value.body).append(" }")
            is GeqFloat -> value.lhs.binaryOp(value.rhs, ">=")
            is GeqInt -> value.lhs.binaryOp(value.rhs, ">=")
            is GtFloat -> value.lhs.binaryOp(value.rhs, ">")
            is GtInt -> value.lhs.binaryOp(value.rhs, ">")
            is IAnd -> value.lhs.binaryOp(value.rhs, "and")
            is IOr -> value.lhs.binaryOp(value.rhs, "or")
            is IfElse -> append("if (").generateValue(value.cond).append(") ").generateValue(value.then).append(" else ").generateValue(value.alt)
            is InList -> value.element.binaryOp(value.list, "in")
            is InMapKeys -> value.key.binaryOp(value.map, "in")
            is Int2Float -> generateValue(value.value).append(".toFloat()")
            is IntEquals -> value.lhs.binaryOp(value.rhs, "==")
            is InvertBool -> append("!").append(value.value)
            is InvertInt -> append("~").append(value.value)
            is IsInstanceOf -> generateValue(value.value).append(" is ").append(value.target.name)
            is IsNull -> append("(").generateValue(value.checked).append(" == null)")
            is LeqFloat -> value.lhs.binaryOp(value.rhs, "<=")
            is LeqInt -> value.lhs.binaryOp(value.rhs, "<=")
            is ListEquals -> value.lhs.binaryOp(value.rhs, "==")
            is ListLiteral -> append("mutableListOf<").generateTypeRepr(value.elementType).append(">(").generateCommaSeparated(value.list).append(")")
            is LtFloat -> value.lhs.binaryOp(value.rhs, "<")
            is LtInt -> value.lhs.binaryOp(value.rhs, "<")
            is MapEquals -> value.lhs.binaryOp(value.rhs, "==")
            is MapLiteral -> TODO()
            is MapOp -> generateValue(value.iterable).append(".map { ${value.ref.name} -> ").generateValue(value.body).append(" }")
            is MapRef -> append(value.name)
            is Match -> append("TODO()")  // TODO
            is MergeLists -> value.lhs.binaryOp(value.rhs, "+")
            is MergeMaps -> value.lhs.binaryOp(value.rhs, "+")
            is ModInt -> value.lhs.binaryOp(value.rhs, "%")
            is MulFloat -> value.lhs.binaryOp(value.rhs, "*")
            is MulInt -> value.lhs.binaryOp(value.rhs, "*")
            is NegateFloat -> append("-").append(value.value)
            is NegateInt -> append("-").append(value.value)
            is NodeEquals -> value.lhs.binaryOp(value.rhs, "==")
            is OrBool -> value.lhs.binaryOp(value.rhs, "or")
            is OrInt -> value.lhs.binaryOp(value.rhs, "or")
            is Panic -> append("error(").append(value.message).append(")")
            is Parameter -> append(value.name)
            is PutAllMapItems -> value.map.binaryOp(value.elements, "+")
            is PutMapItem -> value.map.binaryOp(MapLiteral(mapOf(value.key to value.value), MapType(value.key.type, value.value.type)), "%")
            is RecordEquals -> value.lhs.binaryOp(value.rhs, "==")
            is StringEquals -> value.lhs.binaryOp(value.rhs, "==")
            is StringValue -> append(value.value)
            is SubFloat -> value.lhs.binaryOp(value.rhs, "-")
            is SubInt -> value.lhs.binaryOp(value.rhs, "-")
            is This -> append("this")
            is XorBool -> value.lhs.binaryOp(value.rhs, "xor")
            is XorInt -> value.lhs.binaryOp(value.rhs, "xor")
            is BoolValue -> append(value.value)
            is FloatValue -> append(value.value)
            is IntValue -> append(value.value)
            NullValue -> append("null")
            is ConcatStr -> value.lhs.binaryOp(value.rhs, "+")
        }
        return this
    }

}