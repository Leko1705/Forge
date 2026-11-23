# Forge
A Compiler Definition Language (CDL), made for quickly protoyping compilers in just a few minutes.

A `.forge` file declares the tree of a language, its attributes (attribute grammars), graphs built from
the tree, and functions. Forge checks it and generates Kotlin.

```
./gradlew installDist
build/install/forge/bin/forge -f lang.forge -d out -p my.lang     # writes out/lang.kt in package my.lang
```

Options: `-f` files, `-d` output directory, `-o` output name, `-p` package (namespace for C++), `-t` target
(`kotlin`, `java`, `python`, `cpp`, `rust`, `ocaml`; default `kotlin`), `-opt` optimize, `-keep` names to keep.

`-opt` optimizes before code is generated, for every target:

- **Constant folding**: arithmetic, comparisons and logic on literals, string concatenation, `if` with a known
  condition, `?:`, `!!` and `== null` on literals, `size`, `[i]` and `in` on lists of literals, so
  `const limit = 4 * 3 + 1` becomes `13` where it is used. Nothing that fails at run time (a division by zero, an
  index out of bounds) is folded.
- **Dead code elimination**: everything the compiled file declares is kept; of the files it imports, only what is
  used: functions, attributes with their equations, graphs and parser rules. `-keep f,Recv.g,attr,P,P.rule` keeps
  more, e.g. a parser the program around the generated code uses (`-keep ClassFile` for the Kiln driver).

A file can use the declarations of others: `import "jvm.forge"` (relative to the importing file) at the top
makes the declarations of the imported file part of this one, which is then compiled to one Kotlin file.

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
external fun str.int(): int              // implemented in Kotlin
const greeting = "hello"                 // a constant, with an inferred type
const limit: int = 2 * width             // or a declared one; constants may use each other in any order
```

Constants are inlined: the generated code has no variable for a constant, every use of it is its value. The value
can not use `this` or local variables, and a constant whose value depends on itself is an error.

Types: `int`, `float`, `bool`, `str`, `List<T>`, `Set<T>`, `Map<K, V>`, `T?`, nodes, enums, records, `Vertex`,
`Graph`, `Edge`. A list of different nodes has their most specific common interface as element type, e.g.
`[Push(1), Label("end")]` is a `List<Insn>` if both are `Insn`s.
Expressions: arithmetic, shifts `<<`, `>>` (keeps the sign) and `>>>`, `++` (lists, sets, maps), `x in xs`, `a ?: b`,
`x!!`, `v as T`, `v is T`, `if .. then .. else`, `match` (enum variants, lists `[a, b]`, `head :: tail`, values, guards
`p if cond`), comprehensions (`f(x) for x in xs`, folds `acc + x for x in xs with acc = 0`, also over sets).
Comments: `//` and `/* */`.

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
op fun str.times(n: int): str = acc + this for _i in [0:n] with acc = ""  // "ab" * 3
```

| Operator               | Function                                  |
|------------------------|-------------------------------------------|
| `+ - * / % ++`         | `plus minus times div rem concat`         |
| `<< >> >>>`            | `shl shr ushr`                            |
| `< > <= >=`            | `compareTo(other): int`                   |
| `-x`, `!x`             | `neg()`, `not()`                          |
| `x[i]`, `x[a:b]`       | `get(i)`, `slice(lo: int?, hi: int?)` (an omitted bound is `null`) |
| `e in x`, `e !in x`    | `x.contains(e): bool`                     |

### Builtin functions

Every target implements these; a function the program defines with the same receiver and name comes first.

| Of        | Functions                                                                                             |
|-----------|-------------------------------------------------------------------------------------------------------|
| `int`, `float`, `bool` | `str()`: as text (floats like Kotlin's `Float.toString`)                                |
| `str`     | `size`, `toInt()`, `toFloat()` (fail unless the whole text is a number), `indexOf(t)` (-1 if absent), `startsWith(t)`, `endsWith(t)`, `replace(a, b)`, `upper()`, `lower()`, `trim()`, `split(separator)`, `code(i)`, `utf8()` (the bytes), `unescape()` (`\n \t \r \0`; `\x` is x) |
| `List<T>` | `indexOf(x)`, `reversed()`, `toSet()`; `List<str>.join(separator)`                                   |
| `Set<T>`  | `size`, `toList()`, `remove(x)`                                                                        |
| `Map<K, V>` | `keys()`, `values()` (lists, in the order of the map), `remove(key)`                                 |
| -         | `char(code)`: a character as a str; `fail(message)`: stops with an error (`ForgeError`, `IllegalStateException` in Kotlin, a panic in Rust). Its type is `Nothing`, which fits wherever a value is expected: `x ?: fail("missing")`, `if b == 0 then fail("division by zero") else a / b` |

`Nothing` can also be written: a function or attribute of type `Nothing` never returns, so its value must be of type
`Nothing` too (a `fail`, or a call of another such function), and a call of it fits wherever a value is expected:

```
fun impossible(what: str): Nothing = fail("impossible: " + what)
fun checked(n: int): int = if n >= 0 then n else impossible("negative")
fun wrong(): Nothing = 1      // error: expected type 'Nothing' but got int
```

Strings are UTF-16 in Kotlin and Java, code points in Python and bytes of UTF-8 in C++, Rust and OCaml: sizes,
indexes, `code`, `upper`, `lower` and `trim` agree for ASCII text.

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
circular([]) Vertex.liveOut: List<str> = union(acc, s.liveIn) for s in cfg.succ(this) with acc = []
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

- Functions keep their names; methods are named after their receiver: `fun Type.repr()` is `Type_repr`
  (`f_Type_repr` in OCaml), an external `fun int.text()` is `int_text`. Names that are keywords of the target
  get a `_` appended.
- A parser rule `P.rule` is `P.rule(source)` in Kotlin, Java and Python, `parse_P_rule(input)` elsewhere. A
  failed parse throws `ParseException` (`rt::ParseException` in C++, `Rt.Parse_error` in OCaml); in Rust it
  panics with an `rt::ParseError`, which `rt::catch_parse(|| ...)` returns as an `Err`.
- Attribute evaluation recurses deeply; the C++ and Rust runtimes have `rt::with_stack(megabytes, f)` to run
  on a thread with a large stack.
- Regular expressions are Java's. The C++, Rust and OCaml targets compile them at generation time into a program
  for a small backtracking matcher of their runtime (all of Java's syntax except lookbehind, Unicode classes
  and flags); they match bytes of UTF-8 text.

## Building and testing

```
./gradlew test        # compiles src/test/forge/*.forge to Kotlin and runs the tests on the generated code
```

`TargetsTest` also generates the conformance program and the Kiln compiler for every other target, builds them
with `python3`, `javac`, `g++`, `rustc` and `ocamlopt` and compares what they compute with the Kotlin target; a
target whose toolchain is not installed is skipped.
