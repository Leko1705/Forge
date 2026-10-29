# Forge

A Compiler Definition Language (CDL) for prototyping compilers in minutes.

A Forge program describes a whole compiler: the AST (`node`), its syntax
(`parser` / `rule`), and its semantics as an attribute grammar (synthesized
`syn`, inherited `inh` and collection `coll` attributes, in the spirit of
Silver and JastAdd). Forge compiles it to a self-contained compiler in Kotlin:
AST classes with lazily evaluated attributes, and a scannerless LR(1) parser.

Everything is modular. Every file can add nodes, attribute equations, grammar
rules and whole parsers to an existing language without touching it
(see [`examples/whilelang`](examples/whilelang)).

```forge
node Expr
node Num(n: int): Expr
node Bin(op: str, lhs: Expr, rhs: Expr): Expr

parser Calc
rule Calc.expr: Expr
rule Calc.expr: Bin -> l:expr op:("+" | "-") r:expr { Bin(op, l, r) } @left(10)
                     | l:expr op:("*" | "/") r:expr { Bin(op, l, r) } @left(20)
rule Calc.expr: Num -> v:'[0-9]+' { Num(v.int()) }
rule Calc.expr: Expr -> "(" expr ")"

syn Expr.value: int
syn Num.value: int = this.n
syn Bin.value: int = match this.op {
    "+" -> this.lhs.value + this.rhs.value
    "-" -> this.lhs.value - this.rhs.value
    "*" -> this.lhs.value * this.rhs.value
    _ -> this.lhs.value / this.rhs.value
}

external fun str.int(): int
fun eval(source: str): int = Calc.expr(source).value
```

```kotlin
eval("1 + 2 * (3 - 1)") // 5
```

## Building and running

Requires JDK 21.

```bash
./gradlew installDist
```

```bash
build/install/forge/bin/forge -d out -p my.lang lang.forge more.forge
```

| option | meaning |
|---|---|
| `-d <dir>` | output directory (default `.`) |
| `-o <name>` | name of the generated file (default: first input file) |
| `-p <package>` | package of the generated code |
| `-t <target>` | target language (default and currently only: `kotlin`) |
| `--no-runtime` | don't write `ForgeRuntime.kt`, e.g. when the project already contains it |
| `-opt` | run the IR optimizer |

All files given on the command line form **one** program. The output is
`<name>.kt` (the compiler) and `ForgeRuntime.kt` (package `forge.runtime`: the
LR driver and attribute support, without dependencies).

```bash
./gradlew test       # unit tests (parser generator, diagnostics)
./gradlew e2eTest    # compiles every examples/* program with Forge, then runs src/e2eTest against the generated compilers
./gradlew check      # both
```

## Language

### Declarations

```forge
node Program(stmts: List<Stmt>)          // concrete node, fields are its children
node Stmt                                // interface node (no parentheses)
node Let(name: str, value: Expr): Stmt   // implements Stmt
record Diagnostic { msg: str, line: int }// plain data
enum Type { INT, STR, ERROR(msg: str) }  // algebraic data type
fun Type.repr(): str = ...               // function / method (receiver)
external fun str.int(): int              // implemented outside of Forge
```

Types: `int`, `float`, `bool`, `str`, `List<T>`, `Map<K, V>`, `T?` and the
declared nodes, records and enums. `int` converts implicitly to `float`.

Every node has the builtin members `line` and `column`: the 1-based source
position of its first token, or 0 if the node was not created by a parser.

### Attributes

```forge
syn Expr.type: Type                       // declaration on an interface
syn Expr.isConst: bool = false            // default equation on an interface
syn Num.type: Type = Type.INT             // equation on a concrete node

inh env: Map<str, int> = {}               // inherited attribute, value at the root
inh Program.env {                         // equations: what Program passes to its children
    stmts[i] <- this.envAt(i)             // list child; `i` is bound to the child's index
}
inh Function.env { body <- {} }           // single child

coll Program.errors: List<str> = []       // collection attribute, initial value
contrib Var -> this.program!!.errors =    // every Var in the tree contributes ...
    errors ++ ["undefined " + this.name]  // ... `errors` is the value collected so far
contrib Call -> this.root!!.errors as e = e ++ [...]   // `as` renames that value
```

- `syn` attributes are evaluated lazily and cached.
- A node without an equation for an `inh` attribute passes its own value on to
  its children (copy rule). Inherited attributes can only be read on `this`.
- A `coll` attribute folds the contributions of all nodes of the tree, in
  pre-order, whose target expression evaluates to the collecting node. A source
  may be an interface node.
- Circular attribute dependencies are detected at runtime and reported with
  `ForgeCircularityException`. A static check would reject legal recursion
  such as `syn Cons.len = ... this.tail.len ...`.

### Expressions

Literals `1`, `1.5`, `"text"`, `true`, `null`, `[a, b]`, `{k: v}`;
operators `+ - * / %`, `++` (lists, maps), `== != < <= > >=`, `in` / `!in`
(list elements, map keys), `&& || ^`, `& |` (int), `!`, `-`, `x ?: y`, `x!!`,
`x as T`; access `a.field`, `a.method(..)`, `xs[i]`, `Enum.ITEM`,
`Enum.ITEM(args)`, `Parser.rule(source)`;

```forge
if c then a else b
f(x) for x in xs                        // map
acc + x for x in xs with acc = 0        // fold
expr where fun helper(n: int): int = .. // local (recursive) function, sees `this`
match value {
    Type.ERROR(msg) if msg != "" -> msg // enum patterns with guards
    [a, b] -> ..                        // list of exactly two elements
    head :: rest -> ..                  // non-empty list
    "literal" -> ..                     // equality
    _ -> ..
}
```

Comments: `// ...` and `/* ... */`.

## Parsers

```forge
parser Lang @skip('\s+', '//[^\n]*')   // layout between tokens; default @skip('\s+')
parser Ext : Lang                       // inherits (and can extend) all rules of Lang

rule Lang.expr: Expr                    // declares a rule and its type
rule Lang.expr: Num -> v:'[0-9]+' { Num(v.int()) }   // adds alternatives to it
rule Lang.ident: str -> '[a-z]+'        // no producer: the value is inferred
```

Every rule becomes a parse function: `Lang.expr(source)` returns an `Expr` or
throws `forge.runtime.ForgeParseException` (with `line`, `column`, and the
expected tokens).

A rule may be defined several times, and every definition adds alternatives,
if the rule is declared without productions (`rule Lang.expr: Expr`) or
inherited from a parent parser. That is how languages are extended without
touching them.

### Terminals

- `"text"` matches the text literally.
- `'regex'` is a Java regular expression. A regex without meta characters
  (`'fun'`, `'\+'`) is a literal too, so `'\+'` and `"+"` are the same terminal.
  A `'...'` that is not a valid regex (`'('`) is matched literally (warning).

### Grammar operators and values

| syntax | meaning | value |
|---|---|---|
| `"a"` / `'re'` | terminal | the matched text (`str`) |
| `rule` | nonterminal | the rule's result |
| `a b c` | sequence | see below |
| `a \| b` | alternatives | the common super type of the alternatives' values |
| `(...)` | group | the value of its content |
| `x?` | optional | `T?` |
| `x*`, `x+` | repetition | `List<T>` |
| (empty) | nothing, e.g. `-> { [] }` or `("a" \| )` | `null` |
| `name:x` | label | binds the value of `x` for the producer |

The value of a sequence is the value of its only element that is not an
unlabeled terminal (so `"(" expr ")"` is the expression). If all elements are
terminals, it's the concatenated text. If all value producing elements are
`T` or `List<T>`, it's their concatenation (so `item ("," item)*` is a
`List<Item>`). Otherwise write a producer `{ ... }`.

Labels may appear anywhere. A label inside `?`, or in only some alternatives,
has an optional type; inside `*`/`+` it has a list type:
`(names:ident ",")*` binds `names: List<str>`.

### Precedence and associativity

Ambiguous expression grammars are written directly and disambiguated with
annotations on rule options. A higher level binds tighter.

```forge
rule L.expr: Bin -> l:expr op:("+" | "-") r:expr { Bin(op, l, r) } @left(10)
                  | l:expr op:"^" r:expr { Bin(op, l, r) } @right(30)
                  | l:expr op:"<" r:expr { Bin(op, l, r) } @nonassoc(5)  // a < b < c is an error
rule L.expr: Neg -> "-" e:expr { Neg(e) } @prec(25)                     // no associativity
```

Levels are plain numbers, so an extension can slot operators in between.
Shift/reduce conflicts compare the level of the production to reduce with the
level of the productions the lookahead would be shifted into. Remaining
conflicts are resolved yacc-style (shift, or the production declared first)
and reported as warnings, with the conflicting items and the input prefix that
leads to them.

### How parsing works

Forge generates one scannerless LR parser per `parser`: there is no separate
lexer. The grammar is lowered to BNF by expanding `?` and alternatives inline
(no artificial conflicts, e.g. `a? b | a c` is fine) and turning `x+` into
left-recursive helper rules. LR(1) tables are built with Pager's method:
canonical LR(1) power, about LALR(1) size.

When the parser needs a token, the scanner only tries the terminals that are
valid in the current parse state (context-aware scanning, as in Silver/Copper):

- The longest match wins; on equal length, literal terminals beat regexes.
- If several terminals match the same text, the parser picks the first one
  after which the next token can still be parsed. So keywords are only
  keywords where a keyword is expected: `let print = 3; print print;` works.
- Layout is skipped before every token unless a valid terminal matches a
  longer text.

## Using a generated compiler

```kotlin
import my.lang.*

val program = Lang.program(File("input.txt").readText())   // parse
println(program.errors)                                     // attributes are properties
println(program.stmts.first().line)                         // source positions
```

External functions are resolved by Kotlin: write them as top-level functions
in the generated package, or rely on the Kotlin standard library
(`external fun str.trim(): str`, `external fun List<str>.joinToString(separator: str): str`)
or the builtins in `ForgeRuntime.kt`: `str.int()`, `str.float()`, `str.bool()`,
`str.length()`, `str.characters()`, `str.unquote()`, `int.str()`,
`int.float()`, `float.str()`, `float.int()`, `bool.str()`.

Names starting with `forge`/`Forge` are reserved for generated code.

## Examples

- [`examples/calc`](examples/calc): expression language with variables, error collection
- [`examples/whilelang`](examples/whilelang): an imperative language compiled to
  stack machine code, plus an extension in a separate file (`parser WhileExt : WhileLang`
  with new statements and operators)
- [`examples/cfg`](examples/cfg): a control flow graph built from attributes, liveness
  analysis as a fixpoint, dead store / unreachable code warnings, code generation from
  the graph and Graphviz export
- [`examples/sample`](examples/sample): the type checker of `sample.forge`
- [`examples/features`](examples/features): tour of the expression language

## Architecture

The compiler follows javac: one `Context` per compilation holds every phase as
a service (`X.instance(context)`).

| phase | package | job |
|---|---|---|
| parse | `parse` | ANTLR front end (`src/main/antlr`), merges all files into one tree |
| `Enter`, `MemberEnter` | `enter` | symbols for nodes, enums, records, functions, attributes, parsers, rules |
| `Attr` | `attr` | type checking of all expressions; typing of parser rules (`ParseInfo`) |
| `Dependency` | `analysis` | completeness of `syn` equations |
| `ParserGen`, `LRBuilder` | `lr` | grammar lowering, LR(1) tables, conflict resolution |
| `LLIR` | `llir` | typed intermediate representation |
| `Generator`, `KotlinTarget` | `gen` | code generation |
| runtime | `forge.runtime` | compiled into Forge *and* shipped with generated code |
