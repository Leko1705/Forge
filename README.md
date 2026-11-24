# Forge
A Compiler Definition Language (CDL), made for quickly protoyping compilers in just a few minutes.

A `.forge` file declares the tree of a language, its attributes (attribute grammars), graphs built from
the tree, and functions. Forge checks it and generates Kotlin.

```
./gradlew installDist
build/install/forge/bin/forge -f lang.forge -d out -p my.lang     # writes out/lang.kt in package my.lang
```

Options: `-f` files, `-d` output directory, `-o` output name, `-p` package (namespace for C++), `-t` target
(`kotlin`, `java`, `python`, `cpp`, `rust`, `ocaml`, `javascript`; default `kotlin`), `-opt` optimize, `-keep` names to keep.

`-opt` optimizes before code is generated, for every target:

- **Constant folding**: arithmetic, comparisons and logic on literals, string concatenation, `if` with a known
  condition, `?:`, `!!` and `== null` on literals, `size`, `[i]` and `in` on lists of literals, so
  `const limit = 4 * 3 + 1` becomes `13` where it is used. Nothing that fails at run time (a division by zero, an
  index out of bounds) is folded.
- **Dead code elimination**: everything the compiled file declares is kept; of the files it imports, only what is
  used: functions, attributes with their equations, graphs and parser rules. `-keep f,Recv.g,attr,P,P.rule` keeps
  more, e.g. a parser the program around the generated code uses (`-keep jvm::ClassFile` for the Kiln driver; a declaration of a module by its qualified name).

A file can use the declarations of others: `import "jvm.forge"` (relative to the importing file) at the top
makes the declarations of the imported file part of this one, which is then compiled to one Kotlin file.
The [standard library](#the-standard-library) is part of every program, without an import.

For a complete example, see [examples/kiln](examples/kiln): a small language whose compiler is written in
Forge and generates JVM class files, using a reusable JVM backend (`jvm.forge`).

## The language

```
node Program(decls: List<Decl>)          // a node with children and fields
node Decl                                // an interface node (no parameter list)
node Function(name: str, body: Expr): Decl
for Function impl Named                  // more interfaces

enum Type { INT, STR, ERROR(msg: str) }
record Diagnostic { msg: str, line: int }
fun Type.repr(): str = match this { Type.INT -> "int"  Type.STR -> "str"  Type.ERROR(m) -> m }
ext fun str.int(): int                   // implemented in the target language
val Type.isError: bool = match this { Type.ERROR(_) -> true  _ -> false }   // a property: t.isError
const greeting = "hello"                 // a constant, with an inferred type
const limit: int = 2 * width             // or a declared one; constants may use each other in any order
```

A `val` is a property of a record, an enum or a builtin type (`val <T> List<T>.last: T?`), read like a field
(`t.isError`); its value is computed at each use. Nodes have attributes instead: `syn N.name`, which are cached. An
`ext val` is implemented like an `ext fun` without parameters (`int_text` for `fun int.text()`).

Constants are inlined: the generated code has no variable for a constant, every use of it is its value. The value
can not use `this` or local variables, and a constant whose value depends on itself is an error.

Types: `int`, `long`, `float`, `bool`, `str`, `List<T>`, `Set<T>`, `Map<K, V>`, `T?`, tuples `(int, str)`,
functions `(int) -> str`, nodes, enums and records (also generic ones: `Pair<int, str>`), `Vertex`, `Graph`, `Edge`. A list of different nodes has their most specific common interface as element type, e.g.
`[Push(1), Label("end")]` is a `List<Insn>` if both are `Insn`s.
Expressions: arithmetic, shifts `<<`, `>>` (keeps the sign) and `>>>`, `++` (lists, sets, maps), `x in xs`, `a ?: b`,
`x!!`, `v as T`, `v is T`, `if .. then .. else`, `match` (enum variants, lists `[a, b]`, `head :: tail`, values, guards
`p if cond`), local bindings `e where x = 1`, lambdas `{ x -> x + 1 }`, tuples `(a, b)` and `t.0`, strings with
expressions `"n = ${n}"`. There are no loops: lists (and sets) are gone through with `map` and `fold` of the
[standard library](#the-standard-library), `xs.map { it * 2 }`, `xs.fold(0) { acc, x -> acc + x }`, and functions may
be recursive.
Comments: `//` and `/* */`. Each of these is described [below](#local-bindings-where).

| Literal or access     | Meaning                                                                                 |
|-----------------------|-----------------------------------------------------------------------------------------|
| `[1, 2]`, `[0:n]`     | a list; the ints `0` to `n - 1`                                                         |
| `{1, 2}`, `{}`        | a set (no element twice, in the order they were added); the empty set                  |
| `{"a": 1}`, `{:}`     | a map; the empty map                                                                    |
| `xs[i]`, `s[i]`       | an element, a character of a str; below 0 counts from the end (`xs[-1]` is the last)    |
| `xs[a:b]`, `s[a:b]`   | elements (characters) `a` to `b - 1`; a bound may be omitted (`xs[1:]`), below 0 counts from the end, bounds out of range are clamped |
| `t in s`              | also for strs: whether `t` is part of `s`                                               |

### Operator functions

`op fun` makes an operator work for a type, Kotlin style; the builtin meaning comes first, so they are for the
types that have none (records, nodes, enums) and for builtin types an operator does not apply to:

```
record Vec { x: int, y: int }
op fun Vec.plus(o: Vec): Vec = Vec(this.x + o.x, this.y + o.y)      // a + b
op fun Vec.get(i: int): int = if i == 0 then this.x else this.y     // v[i]
op fun Vec.compareTo(o: Vec): int = this.x - o.x                     // a < b is a.compareTo(b) < 0
op fun str.times(n: int): str = [0:n].fold("") { acc, _ -> acc + this }  // "ab" * 3
```

| Operator               | Function                                  |
|------------------------|-------------------------------------------|
| `+ - * / % ++`         | `plus minus times div rem concat`         |
| `<< >> >>>`            | `shl shr ushr`                            |
| `< > <= >=`            | `compareTo(other): int`                   |
| `-x`, `!x`             | `neg()`, `not()`                          |
| `x[i]`, `x[a:b]`       | `get(i)`, `slice(lo: int?, hi: int?)` (an omitted bound is `null`) |
| `e in x`, `e !in x`    | `x.contains(e): bool`                     |

### The standard library

[std.forge](src/main/resources/forge/std.forge) is bundled with Forge and part of every program, like Kotlin's
`kotlin` package (`import "std"` is allowed but changes nothing). Its `ext fun`s are implemented by the runtime of
every target; the rest is Forge code, which a generated compiler contains only if it uses it, also without `-opt`.
A function or `val` the program declares with the same receiver and name (or the same name, for `char` and `fail`)
comes first.

| Of        | Functions and properties                                                                                       |
|-----------|-------------------------------------------------------------------------------------------------------|
| `int`, `long`, `float`, `bool` | `str()`: as text (floats like Kotlin's `Float.toString`)                        |
| `int`, `long`, `float` | `max(other)`, `min(other)`, `compareTo(other)`; `int.abs()`, `long.abs()`                |
| `str`     | `str()` (itself), `compareTo(other)` (by the codes of the characters), `size`, `toInt()`, `toLong()`, `toFloat()` (fail unless the whole text is a number), `indexOf(t)` (-1 if absent), `startsWith(t)`, `endsWith(t)`, `replace(a, b)`, `upper()`, `lower()`, `trim()`, `split(separator)`, `code(i)`, `utf8()` (the bytes), `unescape()` (`\n \t \r \0`; `\x` is x) |
| `List<T>` | `size`, `indexOf(x)`, `reversed()`, `toSet()`; `List<str>.join(separator)`; `union(other)`: the elements, then those of `other` not in it (no duplicates); `minus(other)`, also `xs - ys`; `distinct()` (first occurrences, in order); `disjoint(other)` |
| `List<T>`, with functions | `map(f)`, `filter(p)`, `flatMap(f)`, `fold(init, f)`, `any(p)`, `all(p)`, `count(p)`, `find(p)` (`null` if none), `groupBy(key)`, `sortedWith(compare)`, `sortedBy(key)` (a `Comparable` key), `maxBy(key)`, `minBy(key)`, `joinWith(separator, f)`; `sorted()` of `Comparable` elements (all sorts are stable) |
| `List<T>`, with tuples | `withIndex()`: `[(0, a), (1, b)]`; `zip(other)`: `[(a, x), (b, y)]`                       |
| `List<int>`, `List<long>`, `List<float>` | `sum()`; `max()`, `min()` of ints and floats (fail for an empty list)   |
| `Set<T>`  | `size`, `toList()`, `remove(x)`, `map(f)` (a list), `fold(init, f)` (in the order the elements were added)                                                                                |
| `Map<K, V>` | `size`, `keys()`, `values()` (lists, in the order of the map), `remove(key)`, `entries()` (`(key, value)` tuples), `mapValues(f)` |
| traits    | `Comparable` (`compareTo(other: Self): int`: int, long, float, str), `Printable` (`str(): str`: int, long, float, bool, str) |
| -         | `char(code)`: a character as a str; `fail(message)`: stops with an error (`ForgeError`, `IllegalStateException` in Kotlin, a panic in Rust). Its type is `Nothing`, which fits wherever a value is expected: `x ?: fail("missing")`, `if b == 0 then fail("division by zero") else a / b` |

`map` and `fold` are Forge code too, recursive ones (they halve the list, so the depth of the recursion is the
logarithm of its size); the other functions on lists are made of them. A program can write its own the same way:
functions may call themselves, only attributes and constants must not depend on themselves.

Part of the language rather than of std.forge: the operators and the graph declarations (`Graph`, `Edge`, `Vertex`, `graphOf`, `Span`, `spanOf`).

`Nothing` can also be written: a function or attribute of type `Nothing` never returns, so its value must be of type
`Nothing` too (a `fail`, or a call of another such function), and a call of it fits wherever a value is expected:

```
fun impossible(what: str): Nothing = fail("impossible: " + what)
fun checked(n: int): int = if n >= 0 then n else impossible("negative")
fun wrong(): Nothing = 1      // error: expected type 'Nothing' but got int
```

### Generic functions

Type parameters come after `fun`; they are inferred at every call, from the receiver and the arguments:

```
fun <T> firstOf(xs: List<T>, otherwise: T): T = match xs { first :: _ -> first  _ -> otherwise }
fun <T> List<T>.second(): T? = if this.size > 1 then this[1] else null
firstOf([], "y")              // a str: the empty list is a List<str>, as "y" says
```

A generic method applies to every receiver it fits: `List<T>.second()` to a `List<int>` as to a `List<Expr>`.
A method declared for a specific receiver (`List<int>.second()`) comes first.

Strings are UTF-16 in Kotlin, Java and JavaScript, code points in Python and bytes of UTF-8 in C++, Rust and OCaml: sizes,
indexes, `code`, `upper`, `lower` and `trim` agree for ASCII text.

### Local bindings: `where`

`e where x = 1, y: int = x + 1` names values for `e`. A binding may have a type (`y: int`); without one its type is
that of its value. The bindings see each other in any order: their values are computed once, in the order of their
dependencies (a binding that depends on itself is an error). A tuple can be taken apart: `where (name, age) = person`.

```
fun area(d: int): int = r * r * 3 where r = d / 2
fun norm(v: Vec): int = x + y where x = v.x.abs(), y = v.y.abs()
fun swap(t: (int, str)): (str, int) = (b, a) where (a, b) = t
```

A `where` is for the whole expression before it, also the whole `if ... else` it ends:
`if c then x else x + 1 where x = f()` sees `x` in both branches.
`a where x = 1 where y = 2` is `a where x = 1, y = 2`; in parentheses a `where` is just for what is in them.

### Functions as values

A function type is written `(int, str) -> bool`; `() -> int` takes nothing. A lambda is `{ x, y -> x + y }`; the types
of its parameters come from where it is used (`xs.map({ x -> x * 2 })`), or are written: `{ x: int -> x * 2 }`. A
function value is called like a function, `f(x)`, also as a field of a record (`r.handler(x)`) or as any expression
in parentheses (`(make())(x)`). A top level function is a value too: `xs.map(double)`; a generic one gets its type
arguments from the type expected there. Lambdas see the variables, parameters and `this` where they are written, also
in attribute equations (`syn Add.scaled: (int) -> int = { k -> k * this.value }`). Function values can not be compared.

```
fun twice(f: (int) -> int, x: int): int = f(f(x))
fun adder(n: int): (int) -> int = { x -> x + n }
fun <A, B, C> compose(f: (A) -> B, g: (B) -> C): (A) -> C = { x -> g(f(x)) }
twice(adder(3), 1)                                    // 7
[3, 1, 2].map({ x -> x * x }).filter({ x -> x > 1 })  // [9, 4]
```

As in Kotlin, a lambda that is the last argument can follow the call: `xs.map() { x -> x * 2 }`, also without the
parentheses, `xs.map { x -> x * 2 }`, and with other arguments before it: `xs.fold(0) { acc, x -> acc + x }`. Such a
lambda can leave out its parameter and `->`; it then has one parameter, `it`, if a function of one parameter is
expected (`xs.map { it * 2 }`, `xs.filter { it > 0 }`), else none (`run { 42 }`). In parentheses a lambda always has
its `->`: `{ x }` there is a set.

```
fun repeat(n: int, f: (int) -> str): str = [0:n].fold("") { acc, i -> acc + f(i) }
repeat(3) { i -> "<${i}>" }                            // "<0><1><2>"
[1, 2, 3].map { it * 10 }.filter { it > 10 }           // [20, 30]
names.joinWith(", ") { it.upper() }
```

In Kotlin a function value is a Kotlin function type (`(Int) -> Int`); in the other targets a value of the runtime
(`__Fn` in Java, `rt::fn` in C++, `rt::func` in Rust, `Fn` in OCaml, a function in Python and JavaScript).

### Generic records and enums, tuples

Records and enums can have type parameters; they are inferred where a value is made, from its fields and from the
type expected there. A type parameter that neither binds is `Nothing`: `Option.NONE` is an `Option<Nothing>`, which
is an `Option<int>`, as generic records and enums are covariant (their values are immutable).

```
record Pair<A, B> { first: A, second: B }
enum Option<T> { NONE, SOME(value: T) }
enum Result<T, E> { OK(value: T), ERR(error: E) }

fun <A, B> Pair<A, B>.swap(): Pair<B, A> = Pair(this.second, this.first)
fun <T> Option<T>.orElse(other: T): T = match this { Option.SOME(v) -> v  _ -> other }
fun parse(s: str): Result<int, str> = if s == "" then Result.ERR("empty") else Result.OK(s.size)
```

A tuple `(1, "a")` has the type `(int, str)`; `t.0`, `t.1` are its elements (`t.1.0` of nested ones). Tuples are equal
if their elements are, can be keys of maps and are taken apart by patterns, `match t { (0, s) -> s  (n, _) -> ... }`,
and by `where (a, b) = t`. Nodes can not be generic: they are the tree that attributes are computed on.

### Traits and bounded type parameters

A type parameter can have bounds: `fun <T: Named & Sized>`. A bound is a trait or a type. A **trait** names members
that a type must have, with `Self` for the type itself:

```
trait Named { fun name(): str }
trait Sized { val weight: int }

record Dog { label: str }
fun Dog.name(): str = "dog " + this.label
val Dog.weight: int = this.label.size

fun <T: Named & Sized> heaviest(xs: List<T>): str = xs.fold(xs[0]) { acc, x -> if x.weight > acc.weight then x else acc }.name()
fun <T: Comparable> largest(a: T, b: T): T = if a > b then a else b      // `<` and `>` are compareTo
```

A type has a trait if it has its members, as methods, `op fun`s or `val`s of its own or of the standard library; it
does not declare that it has it. So `largest(3, 8)`, `largest("pear", "apple")` and `largest(Money(150), Money(99))`
(with a `fun Money.compareTo(other: Money): int`) all work, while `largest([1], [2])` is an error that names the missing
`fun List<int>.compareTo(other: List<int>): int`. Inside the function, the members of the traits can be used on values
of the type parameter, also through operators (`a < b` with `compareTo`) and in strings (`"${x}"` with `str()`); a
generic function that calls another passes its bounds on, and must have them itself (`<T: Named>`).

A function receives the members of the traits of its type parameters as hidden parameters (witnesses), so they work
the same in every target, without virtual dispatch: `largest(3, 8)` passes `int.compareTo`.

A type as bound, `fun <T: Expr>`, makes the members of `Expr` (fields, attributes, methods) members of `T`, and a `T` an
`Expr`: `fun <T: Expr> sum(xs: List<T>): int = xs.fold(0) { acc, x -> acc + x.value }`.

The standard library has the traits `Comparable` and `Printable` (`fun <T: Comparable> List<T>.sorted()`).

### Modules

`mod name { ... }` groups declarations; outside of it they are named `name::Decl`. Modules can be nested
(`geometry::shapes::area`) and inside a module the names of the enclosing modules are visible. `use geometry` makes
the declarations of a module usable by their names, `use geometry::shapes::Shape` only one of them; a `use` at the top
of a file is for that file, one in a module for the module. A name that two `use`d modules declare is ambiguous and
needs its module.

```
mod geometry {
    record Point { x: int, y: int }
    fun origin(): Point = Point(0, 0)
    mod shapes {
        enum Shape { CIRCLE(r: int), RECT(w: int, h: int) }
        fun area(s: Shape): int = match s { Shape.CIRCLE(r) -> 3 * r * r  Shape.RECT(w, h) -> w * h }
        fun corner(): Point = Point(1, 1)          // Point of the enclosing module
    }
}
mod catalog { record Item { code: int } }
mod inventory { record Item { name: str } }       // another Item
use geometry::shapes

fun f(): int = geometry::origin().x + area(Shape.RECT(2, 3)) + catalog::Item(7).code
```

Modules name types, functions, constants, parsers, graphs and traits. Members are found through their owner and need
no module: fields, attributes, methods (`fun Point.plus(...)` in a module is a method of `Point` everywhere), rules
of parsers; inherited attributes and ports have global names. A qualified name is written without spaces; `head :: tail`
in a list pattern has them. In the generated code, `geometry::Point` is `geometry__Point`, so names in modules may not
contain `__`. A Kotlin program around the generated code can import such a name under its own:
`import kiln.jvm__ClassFile as ClassFile`.

### long

`long` is a 64-bit integer, as Kotlin's `Long`: literals `1L`, and an int literal where a long is expected is one
(`val x: long = 1`). Arithmetic on an int and a long is on longs, with a float on floats; `as` converts (`x as int`
keeps the low 32 bits, `2.5 as long` is 2). Operations wrap around as on the JVM in every target (a `BigInt` in
JavaScript, `Int64` in OCaml). `long.str()`, `str.toLong()`.

### Strings with expressions

`"total: ${a + b}!"` puts the values of expressions into a string: strs as they are, other values with their `str()`
method (`int`, `long`, `float`, `bool`, and the types that declare one: `fun Money.str(): str = ...`). A value without
one, like a list or an optional value, is an error. The expressions may contain strings themselves:
`"${names.join(", ")}"`. `\$` is a `$`: `"\${x}"` is the text `${x}`.

### Attributes

| Kind                | Declaration                                                     | Meaning                                                       |
|---------------------|-----------------------------------------------------------------|---------------------------------------------------------------|
| Synthesized         | `syn Expr.type: Type` and `syn Plus.type: Type = ...`           | computed from the node; dispatched by the node type           |
| Synthesized default | `syn Expr.size: int = 1` (on an interface)                      | equation for all subtypes that don't define their own         |
| Inherited           | `inh env: Env = init` and `inh Block.env { stmts[i] <- ... }`   | computed by the parent; `this` is the parent                  |
| Collection          | `coll Program.diags: List<D> = []` and `contrib Expr -> target.diags = diags ++ ...` | folded from contributions of the whole tree |
| Circular            | `circular([]) Vertex.liveIn: List<str> = ...`                   | may depend on itself; computed as a fixpoint                  |

Values are cached per node. An attribute that depends on itself (through any number of attributes and
functions) is an error, unless the cycle goes through a circular attribute.

#### Circular attributes

`circular(init) Node.attr: T = equation` declares an attribute whose equation may depend on itself, also
through other nodes and other circular attributes, e.g. on a cyclic graph. Evaluation starts with `init`
for every node and evaluates all equations involved again until no value changes (compared with `==`).
The equations must be monotone, as in every data flow analysis, e.g. growing sets starting empty.
While a fixpoint is computed, no other attribute caches its value, because it may depend on approximations.
A circular attribute can be declared on several node types (the most specific one applies).

```
circular([]) Vertex.liveOut: List<str> = cfg.succ(this).fold(none) { acc, s -> union(acc, s.liveIn) } where none: List<str> = []
circular([]) Vertex.liveIn: List<str> = union(this.uses, minus(this.liveOut, this.defs))
```

### Graphs

- `Vertex` is an interface node that every node implements, as do the synthetic and exit vertices of flows.
  Attributes can be declared on it: `syn Vertex.uses: List<str> = []`.
- `Graph` has `entry`, `vertices`, `edges`, `root`, `succ(v)`, `pred(v)`, `outEdges(v)`, `inEdges(v)`,
  `contains(v)`, `indexOf(v)`, `exit(port)`, `reachable()`, `postorder()`, `reversePostorder()`, `toDot()`.
  Vertices are compared by identity.
- `Edge(from, to, label)` is a record (`label: str?`).
- `graphOf(entry, edges)` builds a graph from edges, e.g. a call graph.

### Flows: graphs from trees

A graph definition builds one graph per root node from the *flows* of the nodes below it, e.g. a control
flow graph per function:

```
port onTrue, onFalse                  // named exits of flows; `next` is built in
inh port break, return                // inherited: looked up through the enclosing flows

graph cfg: Function

flow cfg: Function(_, body) { entry(body); body -> next }
flow cfg: Block(stmts) { sequence(stmts) }
flow cfg: Assign { entry(this); this -> next }
flow cfg: While(cond, body) {
    entry(cond)
    onTrue(cond) -> body
    onFalse(cond) -> next
    body -> cond
    break(body) -> next
}
flow cfg: Break { entry(this); this -> break }
flow cfg: Expr {
    entry(this)
    if bound(onTrue) { this -> onTrue with "true"; this -> onFalse with "false" } else { this -> next }
}
```

Inside a flow, `this` (the node) and the vertices declared with `vertex name` (or `vertex name = "label"`)
are vertices of the graph. Every other node is a child and stands for the child's own flow.

| Statement                  | Meaning                                                                         |
|----------------------------|---------------------------------------------------------------------------------|
| `entry(x)`                 | control enters this flow at `x` (a vertex, a child, or a port such as `next`)   |
| `a -> b`, `a -> b with "l"`| `a` a vertex: an edge (with a label); `a` a child: its `next` leads to `b`      |
| `port(child) -> b`         | that port of the child leads to `b`, e.g. `onTrue(cond) -> body`                |
| `sequence(xs)`             | `xs` one after another, then `next`                                             |
| `if c { .. } else { .. }`, `for x in xs { .. }` | conditional and repeated statements                        |
| `bound(port)`              | whether the parent connected this port of the node (inherited ports: or further up) |

The fields of the node can be bound by position (`While(cond, body)`, `_` skips one). The most specific
flow applies to a node. Ports that are unbound at the root lead to exit vertices (`g.exit("return")`).
Graphs are used through the graph definition: `cfg.get(f)`, `cfg.of(v)`, `cfg.succ(v)`, `cfg.pred(v)`.

### Parsers

A packrat parser: results are memoized per rule and position, so backtracking is cheap, and left
recursion works (`rule P.path: str -> l:path '\.' r:ident { l + "." + r } | ident`).

```
parser Calc skip '(\s|//[^\n]*)+' keywords 'let' 'while'   // skipped before terminals (default '\s+')

rule Calc.program: Program -> stmts:stmt* { Program(stmts) }
rule Calc.ident: str -> '[A-Za-z_]\w*'                     // never matches a keyword
rule Calc.args: List<Expr> -> first:expr rest:(',' expr)* { [first] ++ rest }

rule Calc.expr: Expr                                         // abstract rule: alternatives and operators
rule Calc.expr: Num -> n:'\d+' { Num(n.toInt()) }
rule Calc.expr: Expr -> '\(' e:expr '\)' { e }
rule Calc.expr: Expr @prefix(30) -> '-' e:expr { Neg(e) }
rule Calc.expr: Expr @infixLeft(10) -> l:expr op:('\+' | '-') r:expr { Bin(op, l, r) }
rule Calc.expr: Expr @infixRight(40) -> l:expr '\^' r:expr { Pow(l, r) }
rule Calc.expr: Expr @postfix(50) -> e:expr '!' { Fact(e) }
rule Calc.expr: Expr @infixRight(2) -> c:expr '\?' a:expr ':' b:expr { Cond(c, a, b) }
```

| Syntax                     | Meaning                                                                         |
|----------------------------|---------------------------------------------------------------------------------|
| `'regex'`                  | a terminal (Java regular expression); returns the matched text                  |
| `name` / `label:x`         | another rule; a label names a value for the producer `{ ... }`                  |
| `x?` `x*` `x+` `x[n]`      | optional, many, at least one, exactly `n` (an expression, may use earlier labels) |
| `(a b \| c)`               | grouping and ordered choice (the first alternative that matches)                |
| `&x` `!x`                  | lookahead: `x` must (not) match here; consumes nothing                          |

- **Values.** Without a producer, a sequence returns its only unlabeled element with a value (`'(' expr ')'`),
  the text of a single terminal, or the texts of a sequence of terminals.
- **Terminals.** A terminal that is a plain literal (`'fun'`, `'\+'`) does not match the start of a longer word
  (`fun` does not match `funny`). Other terminals never match a keyword of their parser. A terminal that is
  not a valid regular expression is matched literally, with a warning.
- **Operators** are alternatives of an abstract rule with a precedence (higher binds stronger) and an
  associativity: `@prefix(p)`, `@infixLeft(p)`, `@infixRight(p)`, `@postfix(p)`. The rule itself at the start
  of the sequence is the left operand, at the end the operand (parsed with the right binding strength), and
  in the middle a whole expression. Parsing works by precedence climbing, so no rule per level is needed:
  `1 + 2 * 3 ^ 4 ^ 5` is `1 + (2 * (3 ^ (4 ^ 5)))`.
- **Binary input.** `parser P binary` parses bytes (`List<int>`, or a `ByteArray` from Kotlin), big-endian,
  at any bit position: bit patterns `0xCAFE` and `0b0101`, and `@u1`, `@u2`, `@u4`, `@bit`, `@uint(n)`,
  `@sint(n)` (two's complement), `@bytes(n)`. Text terminals work at byte boundaries, one character per byte.
  Nothing is skipped by default.
- **Calls.** `Calc.program(source)` parses the whole input. From Kotlin there is a second parameter, the
  name of the input for error messages.
- **Errors.** A failed parse throws a `ParseException` for the furthest position reached, with `line`,
  `column`, `offset` and `expected`: `main.calc:2:11: expected number or ident or '(', found end of input`
  (binary: `byte 0: expected 0xCAFE, found 0xCA`). Tokens (rules that are a single regular expression) are
  named by their rule.
- **Positions.** `spanOf(node)` returns the `Span` (`source`, `line`, `column`, `offset`, `length`, `text`) a
  node was parsed from, or `null`.

## Generated Kotlin

One file (JVM) with a small runtime: nodes become classes (constructing a node sets the parent of its
children), interface nodes interfaces, enums sealed classes, records data classes, attributes properties,
graph definitions `__FlowGraph` values, parsers objects with one function per rule. Names starting with
`__` are reserved.

## Targets

Every target generates one file that contains the whole compiler with a small runtime and needs nothing but the
language's standard library. All targets compute the same results: a conformance program
(`src/test/forge/conformance.forge`) that uses every feature, and the Kiln compiler (`examples/kiln`), produce
identical output in each of them.

| Target   | File                     | Values                                                         | Externals                                         |
|----------|--------------------------|----------------------------------------------------------------|---------------------------------------------------|
| `kotlin` | `name.kt`                | Kotlin classes, `Int`, `String`, `List`, ...                   | extension functions in the same package           |
| `java`   | `Name.java` (one class)  | nested classes with typed fields and attribute accessors (`num.type()`); `Integer`, `String`, `java.util.List<Object>`, ... | static methods of `NameExternals`: `public static String int_text(Integer self)` |
| `python` | `name.py`                | classes with attributes as properties (`num.type`); `int`, `str`, `list`, `dict`, ... | functions in `name_externals.py`: `def int_text(self)` |
| `cpp`    | `name.hpp` (C++17)       | `rt::V`: `rt::I(1)`, `rt::S("x")`, `rt::L({...})`; attributes are functions: `attr_type(node)` | functions declared in the namespace, defined by the program: `rt::V name::int_text(rt::V self)` |
| `rust`   | `name.rs` (a module)     | `rt::V`: `V::Int(1)`, `rt::s("x")`, `rt::list(vec![...])`; attributes are functions | functions of the sibling module `name_externals`: `pub fn int_text(this: V) -> V` |
| `ocaml`  | `name.ml` (OCaml 4.14+)  | `Rt.v`: `Int 1`, `Str "x"`, `List [| ... |]`; attributes are functions | references to set: `Name.ext_int_text := (fun v -> ...)` |
| `javascript` | `name.js` (an ES module, ES2020) | classes with attributes as getters (`num.type`); numbers, strings, arrays, and maps and sets of the runtime that compare keys by value | exported functions of `name_externals.js`: `export function int_text(self)` |

- Functions keep their names; methods are named after their receiver: `fun Type.repr()` is `Type_repr`
  (`f_Type_repr` in OCaml), an `ext fun int.text()` is `int_text`. Names that are keywords of the target
  get a `_` appended.
- A parser rule `P.rule` is `P.rule(source)` in Kotlin, Java, Python and JavaScript, `parse_P_rule(input)` elsewhere. A
  failed parse throws `ParseException` (`rt::ParseException` in C++, `Rt.Parse_error` in OCaml); in Rust it
  panics with an `rt::ParseError`, which `rt::catch_parse(|| ...)` returns as an `Err`.
- Attribute evaluation recurses deeply; the C++ and Rust runtimes have `rt::with_stack(megabytes, f)` to run
  on a thread with a large stack.
- Regular expressions are Java's. The C++, Rust and OCaml targets compile them at generation time into a program
  for a small backtracking matcher of their runtime (all of Java's syntax except lookbehind, Unicode classes
  and flags); they match bytes of UTF-8 text. The JavaScript target translates them into JavaScript's syntax
  (`\s`, `.`, `$`, `\Q...\E`, `\p{Alpha}` and the other POSIX classes, `\h`, `\R`, a leading `(?i)`, ... mean
  what they mean in Java); possessive quantifiers and atomic groups are matched like greedy ones.

### JavaScript: compilers for the web

`-t javascript` generates one ES module without dependencies, so the generated compiler runs in the browser:

```html
<script type="module">
    import { Calc, attr_type } from "./calc.js";
    const program = Calc.program(source, "input.calc");   // throws a ParseException with line and column
    console.log(program.type);                              // attributes are getters (or attr_type(program))
</script>
```

- Everything the program declares is exported: node, record and enum classes (`new Num(1)`, `Type.INT`,
  `new Type.ERROR("x")`), interface nodes for `instanceof` (`x instanceof Expr`), functions, parsers, graphs,
  `attr_<name>`, `inh_<name>` and `coll_<name>`, and `ForgeError`, `ParseException`, `Graph`, `Edge`, `Span`,
  `Vertex`, `graphOf`, `spanOf`.
- `int` is a number that wraps at 32 bits like on the JVM, `float` a number rounded to 32 bits
  (`Math.fround`), `null` is `null`, lists are arrays. Binary parsers take an array of ints, a `Uint8Array` or
  an `ArrayBuffer`.
- Browsers load modules over HTTP, not from `file://`: serve the directory (`python3 -m http.server`). In
  Node.js, put `{ "type": "module" }` in a `package.json` next to the file (or rename it to `.mjs`).
- The depth of recursion is the engine's: very deep trees can exceed the stack of a browser (Node.js:
  `node --stack-size=...`).
- [examples/kiln/javascript](examples/kiln/javascript) has a page that compiles Kiln to class files in the
  browser (`index.html`) and a command line driver for Node.js (`kilnc.js`).

## Building and testing

```
./gradlew test        # compiles src/test/forge/*.forge to Kotlin and runs the tests on the generated code
```

`TargetsTest` also generates the conformance program, the program of the language features
(`src/test/forge/language.forge`) and the Kiln compiler for every other target, builds them
with `python3`, `javac`, `g++`, `rustc`, `ocamlopt` and `node` and compares what they compute with the Kotlin target; a
target whose toolchain is not installed is skipped.

## IntelliJ plugin

[forge-intellij](forge-intellij) is a plugin for IntelliJ-based IDEs (2026.2 or newer) for `.forge` files. It uses
the compiler itself, so the editor sees what `forge -f` sees:

- **Highlighting** with the compiler's lexer (keywords, strings, regular expressions, numbers, comments) and
  parser: names are colored by their role in the grammar (nodes, records and enums, type references, builtin types,
  attributes, functions and calls, constants, ports and graphs, parsers, rules and `@` annotations, ...). The colors
  are in Settings | Editor | Color Scheme | Forge.
- **Syntax errors** of the compiler's parser while typing.
- **Errors and warnings of the analysis** (Enter, MemberEnter, Attr, Dependency, with the imported files and the
  standard library) where the compiler reports them; an error in an imported file is shown on its `import`.
- **Navigation and usages**: Ctrl+Click (Go to Declaration) on a name goes to what the compiler resolves it to,
  also into imported files and the standard library; Ctrl+Click on a declaration, Find Usages (`Alt+F7`) and
  Rename (`Shift+F6`) find its uses. The names are resolved by the compiler's analysis, so while a file has syntax
  errors, it resolves nothing.
- **Computed values are italic**: attributes (`syn`, `inh`, `circular`, `coll`) and `val` properties, where they are
  declared and where they are used, so `this.type` (an attribute) and `this.left` (a child) look different.
- **Gutter icons** for implementations and overrides, as in Java and Kotlin: interface nodes and the nodes that
  implement them, a `syn` definition and the equations of the nodes below it, a `circular` attribute and its
  overrides, `inh attr` and its `inh Node.attr { ... }` equations, an abstract rule and its alternatives. Find
  Usages of a definition also lists its implementations and their uses.
- **Documentation on hover** (and `Ctrl+Q`): the highlighted signature, the `/** ... */` comment before the
  declaration, what it is, the type of variables and how it relates to other declarations.
- **Completion** (`Ctrl+Space`, and on its own after `.` and `@`): after `.` the members the compiler resolves for
  the receiver's type (fields, attributes, `val`s, methods of the program and the standard library, enum constants,
  rules); in expressions the locals in scope, constants, functions, nodes and records (with parentheses), and
  keywords; types in type positions; the nodes, attributes, parsers and rules a declaration names (`syn Num.`
  offers the attributes `Num` implements); `@u2`, `@infixLeft`, ...; top level keywords at the start of a line.
- **Parameter info** (`Ctrl+P`, and on its own after `(`) in calls of functions, methods, constructors of nodes and
  records and enum constants, with the argument of the caret; also while the call is typed.
- **Quick fixes** (`Alt+Enter`) for errors of the compiler: `left` -> `this.left` for a child or an attribute, create
  a missing node, implement a `syn` attribute a node is missing (`fail("not implemented")` until it is written).
- **Structure view** (`Alt+7`) of the declarations, with the fields, children and enum constants of a type, the
  declarations of modules and the members of traits, and
  **Go to Symbol / Go to Class** (`Ctrl+Alt+Shift+N`, `Ctrl+N`) over the declarations of all .forge files.
- **Formatting** (Reformat Code) and indentation on Enter, in the style of the examples: declarations at the start
  of a line, their further lines indented once, and the insides of brackets that span lines once more; spaces after
  commas and around `=`, `->`, `<-` and the comparison and logic operators.
- **Folding** of declaration bodies, blocks in braces, block comments and the imports.
- **Compiling in the IDE**: a Forge run configuration runs the compiler of the plugin on a file (output directory,
  package, target, `-opt`, `-keep`). Right click on a .forge file | Run, or the run icon at its first declaration,
  creates one; the positions in the compiler's messages are links to the code.
- **Project wizard** (File | New | Project... | Forge): a sample (an expression language with attributes and a
  parser) or an empty program, the target language and package, and a Gradle build. The build runs the Forge
  compiler of the plugin (copied to `lib/forge-compiler.jar`) on `src/main/forge`; for Kotlin it compiles the
  generated code with `src/main/kotlin` and `./gradlew run` runs it. The Gradle build is imported when the IDE has
  the Gradle plugin.
- Modules, traits, lambda parameters and `where` bindings have colors of their own roles, and names in modules are
  resolved and completed by their qualified names (`geometry::Point`). A string with expressions (`"${x}"`) is one
  token in the editor; the expressions in it are checked and their errors shown, but not colored.
- Block comments have the color of doc comments.
- Brace matching, commenting (`Ctrl+/`) and quote pairing.

```
./gradlew :forge-intellij:buildPlugin   # forge-intellij/build/distributions/forge-intellij-<version>.zip
./gradlew :forge-intellij:runIde        # starts an IDE with the plugin
./gradlew :forge-intellij:test
```

Install the zip with Settings | Plugins | ⚙ | Install Plugin from Disk. The build downloads the IntelliJ Platform
from JetBrains.

The part of the editor support that does not depend on IntelliJ is in [forge-ide-core](forge-ide-core): the lexer
and parser as editors need them, the compiler's diagnostics, what names resolve to, the hierarchy of declarations,
completion and the project template. The plugin adapts it to IntelliJ; other editors (e.g. through a language
server) can build on it too. `./gradlew :forge-ide-core:test` tests it without an IDE.

## Language server

[forge-lsp](forge-lsp) is a language server (LSP, over stdin and stdout) for `.forge` files, built on
forge-ide-core, for VS Code and other editors (Neovim, Helix, Zed, Emacs, ...). It needs Java 21.

- **Diagnostics**: the syntax errors of the compiler's parser and the errors and warnings of the analysis, with their
  codes (`E009`); a change to an open file rechecks the open files that import it, also before it is saved.
- **Completion** of members, names in scope and keywords, as in IntelliJ (after `.` and `@`).
- **Go to definition** (also into imported files, the standard library and the files of `import`s), **find
  references** (in the open files) and **hover** documentation (signature, doc comment, kind, type, what a
  declaration implements or overrides).
- **Document symbols** (the outline, with fields, children and enum constants, modules with their declarations, traits
  with their members), **semantic tokens** (names colored by what the compiler resolves them to: `attribute`, `port`,
  `graph`, `parser` and `rule` are token types of their own; modules are `namespace`s, traits `interface`s)
  and **folding** of declarations and block comments.

```
./gradlew :forge-lsp:serverJar   # forge-lsp/build/libs/forge-lsp-<version>-all.jar
java -jar forge-lsp/build/libs/forge-lsp-1.0-SNAPSHOT-all.jar
./gradlew :forge-lsp:test
```

## VS Code extension

[forge-vscode](forge-vscode) is the extension for VS Code: it starts the language server (with Java 21 or newer, from
`forge.java.home`, `JAVA_HOME` or the `PATH`), highlights with a TextMate grammar until the server's semantic tokens
are there, and has **Forge: Compile**, which runs the compiler on a file with the `forge.compile.*` settings.

```
cd forge-vscode
npm install
npm run server && npm run build   # the server jar (with Gradle) and the bundled extension
npm test                          # the grammar, finding Java, the command line of the compiler
xvfb-run -a npm run test:integration   # in a VS Code it downloads (VSCODE_VERSION, default stable)
npm run package                   # forge-vscode-1.0.0.vsix: code --install-extension forge-vscode-1.0.0.vsix
```
