# Forge
A Compiler Definition Language (CDL), made for quickly protoyping compilers in just a few minutes.

A `.forge` file declares the tree of a language, its attributes (attribute grammars), graphs built from
the tree, and functions. Forge checks it and generates Kotlin.

```
./gradlew installDist
build/install/forge/bin/forge -f lang.forge -d out -p my.lang     # writes out/lang.kt in package my.lang
```

Options: `-f` files, `-d` output directory, `-o` output name, `-p` package, `-t` target (`kotlin`), `-opt`.

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
```

Types: `int`, `float`, `bool`, `str`, `List<T>`, `Map<K, V>`, `T?`, nodes, enums, records, `Vertex`, `Graph`, `Edge`.
Expressions: arithmetic, `++` (lists, maps), `x in xs`, `a ?: b`, `x!!`, `v as T`, `v is T`, `if .. then .. else`,
`match` (enum variants, lists `[a, b]`, `head :: tail`, values, guards `p if cond`), comprehensions
(`f(x) for x in xs`, folds `acc + x for x in xs with acc = 0`). Comments: `//` and `/* */`.

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

## Building and testing

```
./gradlew test        # compiles src/test/forge/*.forge to Kotlin and runs the tests on the generated code
```
