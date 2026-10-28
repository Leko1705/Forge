package com.leko.forge.analysis

import com.leko.forge.attr.Resolve
import com.leko.forge.attr.SymTab
import com.leko.forge.context.Context
import com.leko.forge.model.CallableSymbol
import com.leko.forge.model.SourceSpan
import com.leko.forge.types.*
import com.leko.forge.util.Log

class Check private constructor(context: Context) {
    companion object {
        fun instance(context: Context): Check =
            context.getServices(Check::class) { Check(context) }
    }

    val resolve = Resolve.instance(context)

    val types = Types.instance(context)

    val log = Log.instance(context)

    val symTab = SymTab.instance(context)


    fun checkAssignable(expected: Type, got: Type, span: SourceSpan? = null): Type {
        if (expected == ErrorType || got == ErrorType) return ErrorType
        if (!types.isAssignable(expected, got)) {
            log.diag(typeMismatch(span, expected, got))
            return ErrorType
        }
        return expected
    }

    fun checkSplit(got: Type): Type {
        if (got == ErrorType) return ErrorType
        if (got !is ListType) {
            log.diag(canNotSplit(null, got))
            return ErrorType
        }
        return got
    }

    fun checkAllAssignable(checked: List<Type>, expectedType: Type?): Type {
        if (checked.isEmpty()) {
            if (expectedType != null) return expectedType
            log.diag(listTypeIsAmbiguous(null))
            return ArbitraryType
        }
        var res = checked.first()
        for (curr in checked) {
            if (res == curr) continue
            val sharedSuperType = checkShareCommonSuperType(curr, res, expectedType)
            if (sharedSuperType is ErrorType) {
                if (expectedType != null) {
                    return checkAssignable(expectedType, res)
                }
                return ErrorType
            }
            res = sharedSuperType
        }

        return res
    }

    fun checkExists(type: Type): Type {
        return when (type) {
            ArbitraryType -> type
            BoolType -> type
            is FunctionType -> FunctionType(
                type.parameters.map { checkExists(it) },
                checkExists(type.returnType)
            )
            is ComplexType -> {
                if (resolve.resolve(type) == null) {
                    log.diag(canNotFindIdentifier(null, type.name))
                    ErrorType
                } else {
                    type
                }
            }
            NullType -> type
            ErrorType -> type
            FloatType -> type
            IntType -> type
            is ListType -> ListType(checkExists(type.elem))
            is MapType -> MapType(checkExists(type.key), checkExists(type.value))
            is OptionalType -> OptionalType(checkExists(type.elem))
            is ParserType -> {
                if (resolve.resolve(type) == null) {
                    log.diag(canNotFindParser(null, type.name))
                    ErrorType
                } else {
                    type
                }
            }
            StrType -> type
            is TerminalRuleProduction -> TerminalRuleProduction(checkExists(type.actual))
        }
    }

    fun resolveFunction(name: String, span: SourceSpan? = null): CallableSymbol? {
        val sym: CallableSymbol? = symTab.functions[name] ?: symTab.table.values
            .filterIsInstance<CallableSymbol>()
            .find { it.name == name }
        if (sym == null) {
            log.diag(functionNotFound(span, name))
        }
        return sym
    }

    fun checkAdditive(t1: Type, t2: Type): Type {
        if (t1 == ErrorType || t2 == ErrorType) return ErrorType
        if (t1 == IntType && t2 == IntType) return IntType
        if (t1 == FloatType && t2 == IntType) return FloatType
        if (t1 == IntType && t2 == FloatType) return FloatType
        if (t1 == FloatType && t2 == FloatType) return FloatType
        if (t1 == StrType && t2 == StrType) return StrType
        log.diag(canNotPerformBinaryOperation(null, t1, t2, "+"))
        return ErrorType
    }

    fun checkNegation(type: Type): Type {
        if (type == ErrorType) return ErrorType
        if (type == IntType) return IntType
        if (type == FloatType) return FloatType
        log.diag(canNotPerformUnaryOperation(null, type, "negate"))
        return ErrorType
    }

    fun checkInversion(type: Type): Type {
        if (type == ErrorType) return ErrorType
        if (type == IntType) return IntType
        if (type == BoolType) return BoolType
        log.diag(canNotPerformUnaryOperation(null, type, "invert"))
        return ErrorType
    }

    fun checkBoolOrInt(type: Type): Type {
        if (type == ErrorType) return ErrorType
        if (type == IntType) return IntType
        if (type == BoolType) return BoolType
        log.diag(typeMismatch(null, listOf(IntType, BoolType), type))
        return ErrorType
    }

    fun checkNumericOp(t1: Type, t2: Type, operation: String): Type {
        if (t1 == ErrorType || t2 == ErrorType) return ErrorType
        if (t1 == IntType && t2 == IntType) return IntType
        if (t1 == FloatType && t2 == IntType) return FloatType
        if (t1 == IntType && t2 == FloatType) return FloatType
        if (t1 == FloatType && t2 == FloatType) return FloatType
        log.diag(canNotPerformBinaryOperation(null, t1, t2, operation))
        return ErrorType
    }

    fun checkConcatOp(t1: Type, t2: Type): Type {
        if (t1 == ErrorType || t2 == ErrorType) return ErrorType
        if (t1 is ListType && t2 is ListType) {
            val sharedSuperType = types.sharedSuperType(t1.elem, t2.elem)
            if (sharedSuperType != null) {
                return ListType(sharedSuperType)
            }
        }
        if (t1 is MapType && t2 is MapType) {
            val sharedSuperKeyType = types.sharedSuperType(t1.key, t2.key)
            val sharedSuperValueType = types.sharedSuperType(t1.value, t2.value)
            if (sharedSuperKeyType != null && sharedSuperValueType != null) {
                return MapType(sharedSuperKeyType, sharedSuperValueType)
            }
        }
        log.diag(canNotPerformBinaryOperation(null, t1, t2, "++"))
        return ErrorType
    }

    fun checkContainerAccess(container: Type, key: Type): Type {
        if (container == ErrorType || key == ErrorType) return ErrorType
        if (container is ListType) {
            checkAssignable(IntType, key)
            return container.elem
        }
        else if (container is MapType) {
            checkAssignable(container.key, key)
            return container.value
        }
        log.diag(typeMismatch(null, listOf(ListType(ArbitraryType), MapType(ArbitraryType, ArbitraryType)), container))
        return ErrorType
    }

    fun checkShareCommonSuperType(t1: Type, t2: Type, expectedType: Type?): Type {
        val sharedSuperTypes = types.sharedSuperType(t1, t2)
        if (sharedSuperTypes == null) {
            if (expectedType != null) {
                checkAssignable(expectedType, t1)
                checkAssignable(expectedType, t2)
                return expectedType
            }
            log.diag(typeMismatch(null, t1, t2))
            return t1
        }
        return sharedSuperTypes
    }

}
