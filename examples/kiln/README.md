# Kiln: a language compiled to JVM class files, in Forge

Kiln is a small imperative language. Its compiler is two Forge files: `kiln.forge` defines the language,
`jvm.forge` a JVM backend any Forge language can use. Kiln programs compile to `.class` files that
`java` runs.

```
fun fib(n: int): int {
    if n < 2 {
        return n
    }
    return fib(n - 1) + fib(n - 2)
}

fun main() {
    var i = 0
    while i <= 15 {
        print("fib(" + i + ") = " + fib(i))
        i = i + 1
    }
}
```

```bash
./gradlew kilnc --args="examples/kiln/programs/fib.kiln -d build/kiln"
```

```bash
java -cp build/kiln Fib
```

## The compiler in other languages

Forge generates the Kiln compiler in every target; `java`, `python`, `cpp`, `rust` and `ocaml` contain a
command line driver for each, and all of them write the same class files:

```bash
build/install/forge/bin/forge -f examples/kiln/kiln.forge -t rust -d build/kiln-rust
```

```bash
cp examples/kiln/rust/*.rs build/kiln-rust/ && rustc -O build/kiln-rust/kilnc.rs -o build/kiln-rust/kilnc
```

```bash
build/kiln-rust/kilnc examples/kiln/programs/fib.kiln -d build/kiln && java -cp build/kiln Fib
```

| Target   | Build and run                                                                        |
|----------|--------------------------------------------------------------------------------------|
| `python` | `python3 kilnc.py fib.kiln -d out`                                                   |
| `java`   | `javac -d classes *.java && java -Xss512m -cp classes Kilnc fib.kiln -d out`         |
| `cpp`    | `g++ -std=c++17 -O1 -pthread kilnc.cpp -o kilnc && ./kilnc fib.kiln -d out`          |
| `rust`   | `rustc -O kilnc.rs -o kilnc && ./kilnc fib.kiln -d out`                              |
| `ocaml`  | `ocamlopt kiln.ml kilnc.ml -o kilnc && ./kilnc fib.kiln -d out`                      |

## The Kotlin driver

`kilnc <file.kiln> [-d <dir>] [--listing] [--cfg] [--run]` writes `<File>.class`, named after the file.
`--listing` prints the JVM code, `--cfg` the control flow graph of every function (Graphviz dot), and
`--run` runs the class in the compiler's own JVM. `./gradlew test` compiles and runs Kiln programs
(`src/test/kotlin/KilnTest.kt`).

## The language

- Types `int`, `bool`, `str`. Functions with parameters and an optional return type. `main()` is the entry point.
- Statements: `var x = e`, `x = e`, `if c { } else if c { } else { }`, `while c { }`, `break`, `return e`,
  `print(e)`, calls, blocks `{ }` (with their own scope).
- Expressions: `+ - * / %`, `< <= > >= == !=`, `&&` and `||` (short-circuit), `-` and `!`, calls, literals.
  `+` with a `str` operand concatenates, `==` on strings compares their content.
- Errors: unknown names, type errors, wrong arguments, `break` outside a loop, a missing `return`,
  duplicate functions, no `main`. Warnings: unreachable code, values that are never read.

## How it works

```
source ──Kiln parser──▶ Kiln tree ──attributes──▶ diagnostics
                                  └──syn code──▶ JVM IR tree (jvm.forge) ──attributes──▶ class file bytes
```

`kiln.forge` starts with `import "jvm.forge"`, so both are one program. `compile(name, source)` wraps the
parsed program in a `Module` node, and `Module.compiled` returns the diagnostics and, if there are no
errors, the bytes of the class file.

### jvm.forge: the backend

The IR is a tree: `JClass(name, superName, methods)`, `JMethod(access, name, descriptor, maxLocals, code)`,
and instructions (`JOp`, `JPush`, `JLdc`, `JLoad`, `JStore`, `JJump`, `JLabel`, `JGetStatic`, `JInvoke`).
A front end builds the tree and reads `JClass.bytes`. The lowering is all attributes:

| What                          | How                                                                                     |
|-------------------------------|-----------------------------------------------------------------------------------------|
| constant pool                 | `coll JClass.constants`: every instruction contributes the constants it needs; `JClass.pool` adds their dependencies and removes duplicates, `JClass.index` numbers them |
| instruction sizes and bytes   | `syn JInsn.size`, `syn JInsn.bytes`, per instruction kind; `JPush` picks `iconst`, `bipush`, `sipush` or `ldc_w` |
| offsets and jumps             | `syn JMethod.offsets` (a fold), `syn JMethod.labels`; `JJump.bytes` is the label's offset minus its own |
| where an instruction is       | `inh jclass`, `inh jmethod`, `inh insnIndex` (with `code[i] <- i`)                       |
| control flow graph            | `syn JMethod.controlFlow = graphOf(...)`: edges to the next instruction and to jump targets |
| `max_stack`                   | `circular(0) JInsn.stackIn`: the deepest stack of the predecessors, a fixpoint around loops |
| stack effect of calls         | the `Descriptor` text parser reads method descriptors: `(I[ILjava/lang/String;)V`       |
| checking the IR               | `coll JClass.problems`: jumps to undefined labels, empty methods                        |
| reading class files back      | the `ClassFile` binary parser: `0xCAFEBABE`, `@u2`, `@u4`, `@bytes(n)`, `constant[count - 1]` |
| a listing like `javap -c`     | `syn JClass.listing`, `syn JInsn.text`                                                   |

Class files are version 49 (Java 5), which needs no `StackMapTable`; current JVMs still verify and load
them.

### kiln.forge: the front end

| What                          | How                                                                                     |
|-------------------------------|-----------------------------------------------------------------------------------------|
| syntax                        | `parser Kiln` with `skip` (comments) and `keywords`; an abstract rule `expr` with `@prefix` and `@infixLeft` operators; `?`, `*`, `!` predicates; `spanOf` for positions |
| scopes                        | `inh scope`: a `Block` passes the scope after statement `i - 1` to statement `i` (`stmts[i] <- ...`); `syn VarDecl.after` adds the variable and a JVM slot |
| locals per function           | `coll Function.slots`, contributed by every `var`                                       |
| types                         | `syn Expr.type`, with `match` on lists of types and guards                              |
| control flow                  | `graph cfg: Function` with `flow`s per statement; ports `onTrue`/`onFalse`, inherited ports `break`/`return`; `bound(onTrue)` makes an expression a condition; `Bool(value)` takes one branch only |
| missing returns, dead code    | `Function.fallsOff` (is `exit("next")` reachable?), `Stmt.reachable`                    |
| values that are never read    | `circular([]) Vertex.liveIn` / `liveOut` over the CFG, on JVM slots                      |
| diagnostics                   | `coll Program.diagnostics`, contributed by expressions, statements and functions        |
| code generation               | `syn Stmt.code` and `syn Expr.code` build JVM IR nodes; labels come from `syn Labeled.label`, an interface implemented by `If`, `While` and `Binary` (`for If impl Labeled`) |

Kiln needs no `external fun`s: string literals are decoded with slices and builtins
(`literal[1:-1].unescape()`), numbers converted with `str.toInt()` and `int.str()`, constants encoded with
`str.utf8()`. Kotlin supplies only the command line driver `Main.kt`.
