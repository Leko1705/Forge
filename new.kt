
abstract class $Node() {
    var $parent: $Node? = null

    // inh env: Type?
    val env: Type? by lazy { if ($parent != null) $parent.compute_env(this) else null }
    protected abstract fun compute_env(child: $Node): Type? = env

    // inh root: Root?
    val root: Root? by lazy { if ($parent != null) $parent.compute_root(this) else null }
    protected abstract fun compute_root(child: $Node): Root? = root

}

// enum Type
enum class Type {
    INT()  // variant Type.INT
    STR()  // variant Type.STR
    ERROR()  // variant Type.ERROR
    ALREADY_REPORTED_ERROR()  // variant Type.ALREADY_REPORTED_ERROR
}
// node Root
class Root(val decls: MutableList<TopLevelDecl>) : $Node() {
    // syn lower: MutableList<Int>
    val lower: MutableList<Int> by lazy {
        return mutableListOf<Int>()
    }
    // inh env: Type?
    override fun compute_env(child: $Node): Type? {
        if ($parent != null) $parent.compute_env(this) else null
    }
    // inh root: Root?
    override fun compute_root(child: $Node): Root? {
        if ($parent != null) $parent.compute_root(this) else null
    }
    // coll diags: MutableList<Diagnostic>
    val diags: MutableList<Diagnostic>
}
// interface node TopLevelDecl
sealed interface TopLevelDecl : $Node {
}
// node Function
class Function(val receiver: Type?, val name: String, val params: MutableList<Parameter>, val returnType: Type, val body: Expr) : $Node(), TopLevelDecl {
    // inh env: Type?
    override fun compute_env(child: $Node): Type? {
        if ($parent != null) $parent.compute_env(this) else null
    }
    // inh root: Root?
    override fun compute_root(child: $Node): Root? {
        if ($parent != null) $parent.compute_root(this) else null
    }
}
// node Parameter
class Parameter(val name: String, val type: Type) : $Node() {
    // inh env: Type?
    override fun compute_env(child: $Node): Type? {
        if ($parent != null) $parent.compute_env(this) else null
    }
    // inh root: Root?
    override fun compute_root(child: $Node): Root? {
        if ($parent != null) $parent.compute_root(this) else null
    }
}
// interface node Expr
sealed interface Expr : $Node {
    // syn type: Type
    val type: Type
}
// node Int
class Int(val value: Int) : $Node(), Expr {
    // syn type: Type
    override val type: Type by lazy {
        return Type.INT()
    }
    // inh env: Type?
    override fun compute_env(child: $Node): Type? {
        if ($parent != null) $parent.compute_env(this) else null
    }
    // inh root: Root?
    override fun compute_root(child: $Node): Root? {
        if ($parent != null) $parent.compute_root(this) else null
    }
}
// node String
class String(val value: String) : $Node(), Expr {
    // syn type: Type
    override val type: Type by lazy {
        return Type.STR()
    }
    // inh env: Type?
    override fun compute_env(child: $Node): Type? {
        if ($parent != null) $parent.compute_env(this) else null
    }
    // inh root: Root?
    override fun compute_root(child: $Node): Root? {
        if ($parent != null) $parent.compute_root(this) else null
    }
}
// node Plus
class Plus(val lhs: Expr, val rhs: Expr) : $Node(), Expr {
    // syn type: Type
    override val type: Type by lazy {
        return TODO()
    }
    // inh env: Type?
    override fun compute_env(child: $Node): Type? {
        if ($parent != null) $parent.compute_env(this) else null
    }
    // inh root: Root?
    override fun compute_root(child: $Node): Root? {
        if ($parent != null) $parent.compute_root(this) else null
    }
}
// node This
class This() : $Node(), Expr {
    // syn type: Type
    override val type: Type by lazy {
        return if ((this.env == null)) Type.ERROR("can not use 'this' in this context") else this.env
    }
    // inh env: Type?
    override fun compute_env(child: $Node): Type? {
        if ($parent != null) $parent.compute_env(this) else null
    }
    // inh root: Root?
    override fun compute_root(child: $Node): Root? {
        if ($parent != null) $parent.compute_root(this) else null
    }
}
// function repr
fun Type.repr(): String {
    return TODO()
}
// function compile
fun compile(program: String): CompilationResult {
    return program(program).compile(program(program))
}
// function compile
fun Root.compile(): CompilationResult {
    return CompilationResult(if (this.diags.size == 0) this.lower else null, this.diags)
}
