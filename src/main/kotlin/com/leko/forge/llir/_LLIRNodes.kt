package com.leko.forge.llir

import com.leko.forge.lr.ParserDefinition
import com.leko.forge.lr.Sem
import com.leko.forge.types.*

// ============================================================
// Module
// ============================================================

class LLIRModule(
    val sourceName: String?,
    val records: List<Record>,
    val enums: List<LLIREnum>,
    val nodes: List<Node>,
    val functions: List<Function>,
    /** global `inh` declarations (name -> declaration incl. init value) */
    val inherited: Map<String, InhAttribute>,
    val parsers: List<LLIRParser>,
)

// ============================================================
// Parsers
// ============================================================

class LLIRParser(
    val name: String,
    val definition: ParserDefinition,
    /** per production of [definition] */
    val productions: List<LLIRProduction>,
)

sealed interface LLIRProduction {
    /** reduction of a rule option: bind the labels, then produce the value */
    class User(val labels: List<Pair<Local, Sem>>, val producer: Value?, val value: Sem, val type: Type) : LLIRProduction
    class Helper(val value: Sem) : LLIRProduction
}

// ============================================================
// Structures. Plain classes ON PURPOSE: declarations are referenced from
// Value trees (e.g. a recursive syn contains an access to itself), so they need
// identity equality/hash. A data class would recurse forever.
// ============================================================

sealed interface Struct { val name: String }

sealed interface Container {
    val name: String
    val fields: Map<String, Type>
}

class Record(override val name: String) : Struct, Container {
    override val fields: MutableMap<String, Type> = linkedMapOf()
}

class LLIREnum(override val name: String) : Struct {
    val items: MutableList<EnumItem> = mutableListOf()
}

class EnumItem(val enumName: String, val itemName: String) : Container {
    override val name: String get() = itemName
    override val fields: MutableMap<String, Type> = linkedMapOf()
}

class Node(override val name: String) : Struct, Container {
    override val fields: MutableMap<String, Type> = linkedMapOf()
    /** contributions to collection attributes made by this node (or its subtypes, if it is an interface) */
    val contributions: MutableList<Contribution> = mutableListOf()
    val parents: MutableList<Node> = mutableListOf()
    var isInterface: Boolean = false

    val synthetics: MutableMap<String, SynAttribute> = linkedMapOf()
    val collections: MutableMap<String, CollAttribute> = linkedMapOf()
    /** attrName -> how THIS node hands inherited values down to its children */
    val inhEquations: MutableMap<String, InhEquation> = linkedMapOf()

    val isConcrete: Boolean get() = !isInterface

    fun ancestors(): Set<Node> {
        val seen = linkedSetOf<Node>()
        fun walk(n: Node) { for (p in n.parents) if (seen.add(p)) walk(p) }
        walk(this)
        return seen
    }

    fun inheritsSyn(attr: String): Boolean = ancestors().any { attr in it.synthetics }
}

class Function(
    val receiver: Type?,
    val name: String,
    val parameters: List<Parameter>,
    val returnType: Type,
    val isExternal: Boolean = false,
) {
    var body: Value? = null
}

// ============================================================
// Attributes (identity-equal declarations, created on first reference OR definition)
// ============================================================

sealed class Attribute {
    abstract val name: String
    abstract val type: Type
}

/** `syn Node.x: T [= body]`. body == null means abstract (declared on an interface). */
class SynAttribute(val owner: Node, override val name: String, override val type: Type) : Attribute() {
    var body: Value? = null
}

/** `inh x: T = init` (global). */
class InhAttribute(override val name: String, override val type: Type) : Attribute() {
    lateinit var init: Value
}

/** `coll Node.x: T = init` */
class CollAttribute(val owner: Node, override val name: String, override val type: Type) : Attribute() {
    lateinit var init: Value
    val contributions: MutableList<Contribution> = mutableListOf()
}

/** `contrib Source -> target.coll as alias = value` */
class Contribution(
    val source: Node,
    val coll: CollAttribute,
    /** evaluated with `this` == the contributing node; must resolve to the collecting node */
    val target: Value,
    /** the fold-so-far, bound to the user's alias name */
    val alias: Local,
    val value: Value,
)

/** `inh Node.attr { delegates }` */
class InhEquation(val attrName: String, val delegates: List<InhDelegate>)

/**
 * `field <- value`            key == null
 * `field[keyExpr] <- value`   key != null; `binder` is the index variable the key introduces (if any)
 */
class InhDelegate(
    val field: String,
    val key: Value?,
    val binder: Local?,
    val value: Value,
)

// ============================================================
// Values
// ============================================================

sealed interface Value { val type: Type }

// ---- literals & references ----
data class IntValue(val value: Int) : Value { override val type: Type get() = IntType }
data class FloatValue(val value: Double) : Value { override val type: Type get() = FloatType }
data class BoolValue(val value: Boolean) : Value { override val type: Type get() = BoolType }
data class StringValue(val value: String) : Value { override val type: Type get() = StrType }
data object NullValue : Value { override val type: Type get() = NullType }

data class Parameter(val name: String, override val type: Type) : Value
data class This(override val type: Type) : Value
/** a local variable: bound by a match pattern, comprehension, label, delegation index, ... */
data class Local(val name: String, override val type: Type) : Value

data class ListLiteral(val list: List<Value>, override val type: Type) : Value
data class MapLiteral(val entries: List<Pair<Value, Value>>, override val type: Type) : Value

// ---- construction & calls ----
data class Call(val target: Function, val receiver: Value?, val args: List<Value>) : Value {
    override val type: Type get() = target.returnType
}
data class CreateRecord(val target: Record, val args: List<Value>) : Value {
    override val type: Type get() = ComplexType(target.name)
}
data class CreateEnum(val target: EnumItem, val args: List<Value>) : Value {
    override val type: Type get() = ComplexType(target.enumName)
}
data class CreateNode(val target: Node, val args: List<Value>) : Value {
    override val type: Type get() = ComplexType(target.name)
}

// ---- access ----
data class AccessRecordField(val target: Record, val accessed: Value, val name: String, override val type: Type) : Value
data class AccessNodeField(val target: Node, val accessed: Value, val name: String, override val type: Type) : Value
/** syn / inh / coll attribute of a node */
data class AccessNodeAttr(val receiver: Value, val attr: Attribute) : Value {
    override val type: Type get() = attr.type
}
data class AccessListContainer(val container: Value, val key: Value, override val type: Type) : Value
data class AccessMapContainer(val container: Value, val key: Value, override val type: Type) : Value
/** builtin members of primitive types, e.g. `List.size` */
data class AccessBuiltinField(val receiver: Value, val name: String, override val type: Type) : Value

// ---- conversions ----
data class Int2Float(val value: Value) : Value { override val type: Type get() = FloatType }
data class Float2Int(val value: Value) : Value { override val type: Type get() = IntType }
data class Cast(val value: Value, override val type: Type) : Value
data class IsInstanceOf(val value: Value, val target: Struct) : Value { override val type: Type get() = BoolType }

// ---- unary ----
data class NegateInt(val value: Value) : Value { override val type: Type get() = IntType }
data class NegateFloat(val value: Value) : Value { override val type: Type get() = FloatType }
data class InvertBool(val value: Value) : Value { override val type: Type get() = BoolType }
data class InvertInt(val value: Value) : Value { override val type: Type get() = IntType }
data class IsNull(val checked: Value) : Value { override val type: Type get() = BoolType }
/** `x!!` */
data class NotNull(val value: Value, override val type: Type) : Value
/** `a ?: b` (single evaluation of `a`, unlike lowering to IfElse(IsNull(a), b, a)) */
data class Elvis(val value: Value, val alt: Value, override val type: Type) : Value

// ---- binary ----
sealed interface BinaryValue : Value {
    val lhs: Value
    val rhs: Value
}
sealed class IntBinary : BinaryValue { override val type: Type get() = IntType }
sealed class FloatBinary : BinaryValue { override val type: Type get() = FloatType }
sealed class BoolBinary : BinaryValue { override val type: Type get() = BoolType }
sealed class SameTypeBinary : BinaryValue { override val type: Type get() = lhs.type }

data class MulInt(override val lhs: Value, override val rhs: Value) : IntBinary()
data class DivInt(override val lhs: Value, override val rhs: Value) : IntBinary()
data class ModInt(override val lhs: Value, override val rhs: Value) : IntBinary()
data class AddInt(override val lhs: Value, override val rhs: Value) : IntBinary()
data class SubInt(override val lhs: Value, override val rhs: Value) : IntBinary()
data class AndInt(override val lhs: Value, override val rhs: Value) : IntBinary()
data class OrInt(override val lhs: Value, override val rhs: Value) : IntBinary()
data class XorInt(override val lhs: Value, override val rhs: Value) : IntBinary()
data class IAnd(override val lhs: Value, override val rhs: Value) : IntBinary()
data class IOr(override val lhs: Value, override val rhs: Value) : IntBinary()

data class MulFloat(override val lhs: Value, override val rhs: Value) : FloatBinary()
data class DivFloat(override val lhs: Value, override val rhs: Value) : FloatBinary()
data class AddFloat(override val lhs: Value, override val rhs: Value) : FloatBinary()
data class SubFloat(override val lhs: Value, override val rhs: Value) : FloatBinary()

data class LtInt(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class LtFloat(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class GtInt(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class GtFloat(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class GeqInt(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class GeqFloat(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class LeqInt(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class LeqFloat(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class AndBool(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class OrBool(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class XorBool(override val lhs: Value, override val rhs: Value) : BoolBinary()

data class IntEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class FloatEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class BoolEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class StringEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class ListEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class MapEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class EnumEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class RecordEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
/** structural equality of any two values that are not nodes (optionals, enums, records, lists, ...) */
data class ValueEquals(override val lhs: Value, override val rhs: Value) : BoolBinary()
data class NodeEquals(
    override val lhs: Value, val lhsIsConcrete: Boolean,
    override val rhs: Value, val rhsIsConcrete: Boolean,
) : BoolBinary()

data class ConcatStr(override val lhs: Value, override val rhs: Value) : SameTypeBinary()
data class MergeLists(override val lhs: Value, override val rhs: Value) : SameTypeBinary()
data class MergeMaps(override val lhs: Value, override val rhs: Value) : SameTypeBinary()

// ---- collections ----
data class InList(val list: Value, val element: Value) : Value { override val type: Type get() = BoolType }
data class InMapKeys(val map: Value, val key: Value) : Value { override val type: Type get() = BoolType }
data class AppendListItem(val list: Value, val element: Value) : Value { override val type: Type get() = list.type }
data class AppendAllListItems(val list: Value, val elements: Value) : Value { override val type: Type get() = list.type }
data class PutMapItem(val map: Value, val key: Value, val value: Value) : Value { override val type: Type get() = map.type }
data class PutAllMapItems(val map: Value, val elements: Value) : Value { override val type: Type get() = map.type }

// ---- control ----
data class IfElse(val cond: Value, val then: Value, val alt: Value, override val type: Type) : Value
data class Panic(val message: String) : Value { override val type: Type get() = ArbitraryType }
/** `body where fun f(..) = ..` */
data class WithLocalFunction(val function: Function, val body: Value) : Value { override val type: Type get() = body.type }
/** `Parser.rule(source)` */
data class ParseCall(val parser: String, val rule: String, val source: Value, override val type: Type) : Value

// ---- comprehensions ----
/** `body for item in iterable` */
data class MapOp(val iterable: Value, val item: Local, val body: Value, override val type: Type) : Value
/** `body for item in iterable with acc = init` */
data class FoldOp(val iterable: Value, val initAcc: Value, val item: Local, val acc: Local, val body: Value) : Value {
    override val type: Type get() = acc.type
}

// ---- match ----
sealed interface MatchPattern
data object WildcardPattern : MatchPattern
data class BindPattern(val variable: Local) : MatchPattern
data class ValuePattern(val value: Value) : MatchPattern
data class EnumVariant(val item: EnumItem)
data class ConstructorPattern(val variant: EnumVariant, val args: List<MatchPattern>) : MatchPattern
data class ListPattern(val elements: List<MatchPattern>) : MatchPattern
data class SplitPattern(val head: MatchPattern, val tail: MatchPattern) : MatchPattern

data class MatchCase(val pattern: MatchPattern, val guard: Value?, val body: Value)
data class Match(val scrutinee: Value, val cases: List<MatchCase>, val alt: Value?, override val type: Type) : Value