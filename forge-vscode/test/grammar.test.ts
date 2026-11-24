// The TextMate grammar, tokenized with the libraries VS Code uses (vscode-textmate, vscode-oniguruma).
import assert from "node:assert/strict";
import * as fs from "node:fs";
import { createRequire } from "node:module";
import * as path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import oniguruma from "vscode-oniguruma";
import textmate from "vscode-textmate";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

async function loadGrammar(): Promise<textmate.IGrammar> {
    const wasm = fs.readFileSync(require.resolve("vscode-oniguruma/release/onig.wasm"));
    await oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength) as ArrayBuffer);
    const registry = new textmate.Registry({
        onigLib: Promise.resolve({
            createOnigScanner: (patterns: string[]) => new oniguruma.OnigScanner(patterns),
            createOnigString: (s: string) => new oniguruma.OnigString(s),
        }),
        loadGrammar: async () => {
            const file = path.join(here, "..", "syntaxes", "forge.tmLanguage.json");
            return textmate.parseRawGrammar(fs.readFileSync(file, "utf8"), file);
        },
    });
    const grammar = await registry.loadGrammar("source.forge");
    assert.ok(grammar);
    return grammar;
}

const grammarPromise = loadGrammar();

/** The tokens of [text]: their text and their innermost scope. */
async function tokenize(text: string): Promise<{ text: string; scope: string }[]> {
    const grammar = await grammarPromise;
    let state = textmate.INITIAL;
    const tokens: { text: string; scope: string }[] = [];
    for (const line of text.split("\n")) {
        const result = grammar.tokenizeLine(line, state);
        for (const token of result.tokens) {
            const part = line.substring(token.startIndex, token.endIndex);
            if (part.trim()) tokens.push({ text: part, scope: token.scopes[token.scopes.length - 1] });
        }
        state = result.ruleStack;
    }
    return tokens;
}

/** The scope of the token [part] (the [nth] of them) in [text]. */
async function scopeOf(text: string, part: string, nth = 0): Promise<string> {
    const found = (await tokenize(text)).filter((t) => t.text.trim() === part);
    assert.ok(found.length > nth, `no token '${part}' in ${JSON.stringify(await tokenize(text))}`);
    return found[nth].scope;
}

test("declarations name what they declare", async () => {
    const node = "node Num(value: int): Expr";
    assert.equal(await scopeOf(node, "node"), "storage.type.forge");
    assert.equal(await scopeOf(node, "Num"), "entity.name.type.forge");
    assert.equal(await scopeOf(node, "int"), "support.type.forge");
    assert.equal(await scopeOf(node, "Expr"), "entity.name.type.forge");
    assert.equal(await scopeOf("fun twice(x: int): int = x * 2", "twice"), "entity.name.function.forge");
    assert.equal(await scopeOf("fun <T> List<T>.second(): T? = this[1]", "second"), "entity.name.function.forge");
    assert.equal(await scopeOf("val Type.isError: bool = false", "isError"), "entity.name.function.forge");
    const syn = "syn Add.type: Type = this.left.type";
    assert.equal(await scopeOf(syn, "Add"), "entity.name.type.forge");
    assert.equal(await scopeOf(syn, "type"), "variable.other.property.attribute.forge");
    assert.equal(await scopeOf(syn, "this"), "variable.language.this.forge");
    assert.equal(await scopeOf("inh depth: int = 0", "depth"), "variable.other.property.attribute.forge");
    const rule = "rule Lang.expr: Num -> n: '[0-9]+' { Num(n.toInt()) }";
    assert.equal(await scopeOf(rule, "Lang"), "entity.name.type.class.parser.forge");
    assert.equal(await scopeOf(rule, "expr"), "entity.name.function.rule.forge");
    assert.equal(await scopeOf(rule, "[0-9]+"), "string.regexp.forge");
    assert.equal(await scopeOf("const limit = 3", "limit"), "variable.other.constant.forge");
});

test("comments, strings and numbers", async () => {
    assert.equal(await scopeOf("/** doc */ node A", "doc"), "comment.block.documentation.forge");
    assert.equal(await scopeOf("/* a\n still */ node A", "still"), "comment.block.forge");
    assert.equal(await scopeOf("node A // the end", "the end"), "comment.line.double-slash.forge");
    assert.equal(await scopeOf('const s = "a\\n"', "\\n"), "constant.character.escape.forge");
    assert.equal(await scopeOf("const h = 0x1F", "0x1F"), "constant.numeric.hex.forge");
    assert.equal(await scopeOf("const f = 1.5", "1.5"), "constant.numeric.float.forge");
});

test("keywords, enum constants and annotations", async () => {
    const expr = "fun f(t: Type): bool = match t { Type.ERROR(m) -> m !in xs  _ -> if true then false else null }";
    assert.equal(await scopeOf(expr, "match"), "keyword.control.forge");
    assert.equal(await scopeOf(expr, "ERROR"), "variable.other.enummember.forge");
    assert.equal(await scopeOf(expr, "!in"), "keyword.operator.word.forge");
    assert.equal(await scopeOf(expr, "null"), "constant.language.null.forge");
    assert.equal(await scopeOf("rule P.e: E @infixLeft(10)", "infixLeft"), "storage.type.annotation.forge");
    assert.equal(await scopeOf('import "jvm.forge"', "import"), "keyword.control.import.forge");
});

test("the Forge files of the repository have no invalid tokens", async () => {
    const root = path.join(here, "..", "..");
    const files = ["examples/kiln/kiln.forge", "examples/kiln/jvm.forge", "src/test/forge/conformance.forge", "src/main/resources/forge/std.forge"];
    for (const file of files) {
        const invalid = (await tokenize(fs.readFileSync(path.join(root, file), "utf8"))).filter((t) => t.scope.startsWith("invalid"));
        assert.deepEqual(invalid, [], file);
    }
});
