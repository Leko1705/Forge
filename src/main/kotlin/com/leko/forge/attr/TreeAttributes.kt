package com.leko.forge.attr

import ForgeParser
import com.leko.forge.attr.TreeAttributes.attributes
import com.leko.forge.model.*
import com.leko.forge.model.CallableSymbol
import com.leko.forge.model.CollSymbol
import com.leko.forge.model.EnumSymbol
import com.leko.forge.model.FunctionSymbol
import com.leko.forge.model.InhSymbol
import com.leko.forge.model.NodeSymbol
import com.leko.forge.model.ParserRuleSymbol
import com.leko.forge.model.ParserSymbol
import com.leko.forge.model.RecordSymbol
import com.leko.forge.model.Symbol
import com.leko.forge.model.SynSymbol
import com.leko.forge.model.VarSymbol
import com.leko.forge.types.Type
import org.antlr.v4.runtime.ParserRuleContext
import kotlin.reflect.KClass

@PublishedApi
internal object TreeAttributes {
    val attributes = mutableMapOf<ParserRuleContext, MutableMap<String, Any?>>()
}

inline fun <reified T> ParserRuleContext.attach(name: String, value: T) {
    attributes.getOrPut(this) { mutableMapOf() }[name] = value
}

fun ParserRuleContext.setType(type: Type): Type {
    attach<Type?>("type", type)
    return type
}

fun ParserRuleContext.setNullableType(type: Type?): Type? {
    attach<Type?>("type", type)
    return type
}

val ParserRuleContext.type: Type get() = attributes.getValue(this)["type"] as Type


fun ParserRuleContext.setSym(sym: Symbol): Symbol {
    attach<Symbol>("symbol", sym)
    return sym
}

val ForgeParser.EnumContext.symbol: EnumSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? EnumSymbol
}

val ForgeParser.RecordContext.symbol: RecordSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? RecordSymbol
}

val ForgeParser.NodeDefContext.symbol: NodeSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? NodeSymbol
}

val ForgeParser.InhDefContext.symbol: InhSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? InhSymbol
}

val ForgeParser.ExpressionContext.symbol: Symbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? Symbol
}

@Suppress("UNCHECKED_CAST")
val ForgeParser.ListComprehensionContext.symbols: Pair<VarSymbol, VarSymbol>? get() {
    val attrs = attributes[this]
    return attrs?.get("symbols") as? Pair<VarSymbol, VarSymbol>
}

val ForgeParser.IdentifierContext.symbol: Symbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? Symbol
}

val ForgeParser.FieldAccessContext.symbol: Symbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? Symbol
}

val ForgeParser.FunctionCallContext.symbol: CallableSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? CallableSymbol
}

val ForgeParser.MethodCallContext.symbol: Symbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? Symbol
}

val ForgeParser.FunctionContext.symbol: FunctionSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? FunctionSymbol
}

val ForgeParser.SynDefContext.symbol: SynSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? SynSymbol
}

val ForgeParser.CollContext.symbol: CollSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? CollSymbol
}

val ForgeParser.ContribContext.symbol: CollSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? CollSymbol
}

val ForgeParser.ParseRuleDefContext.symbol: ParserRuleSymbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? ParserRuleSymbol
}

val ForgeParser.TypePatternContext.symbol: Symbol? get() {
    val attrs = attributes[this]
    return attrs?.get("symbol") as? Symbol
}

val ForgeParser.CircularDefContext.symbol: CircularSymbol? get() = attributes[this]?.get("symbol") as? CircularSymbol

val ForgeParser.GraphDefContext.symbol: FlowGraphSymbol? get() = attributes[this]?.get("symbol") as? FlowGraphSymbol

val ForgeParser.FlowDefContext.symbol: FlowSymbol? get() = attributes[this]?.get("symbol") as? FlowSymbol

val ForgeParser.InhImplContext.symbol: InhSymbol? get() = attributes[this]?.get("symbol") as? InhSymbol

/** The index variable introduced by the key of an inh delegate (`decls[i] <- ...`), if any. */
val ForgeParser.InhDelegateContext.binder: VarSymbol? get() = attributes[this]?.get("binder") as? VarSymbol

/** Local variables declared by flow statements (`vertex v`, `for x in xs`) and flow binders. */
val ParserRuleContext.local: VarSymbol? get() = attributes[this]?.get("local") as? VarSymbol

/** The ports (`next`, `onTrue`, ...) an expression inside a flow refers to. */
val ParserRuleContext.port: PortSymbol? get() = attributes[this]?.get("port") as? PortSymbol

/** The built-in flow operation (`entry`, `sequence`, `bound`) a function call inside a flow refers to. */
val ParserRuleContext.flowBuiltin: String? get() = attributes[this]?.get("flowBuiltin") as? String

val ParserRuleContext.typeOrNull: Type? get() = attributes[this]?.get("type") as? Type
