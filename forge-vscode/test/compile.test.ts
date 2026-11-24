import assert from "node:assert/strict";
import * as path from "node:path";
import { test } from "node:test";
import { checkSettings, compilerArguments, type CompileSettings, outputPath } from "../src/compile.ts";

const defaults: CompileSettings = { outputDirectory: "", packageName: "", target: "kotlin", optimize: false, keep: [] };
const file = path.resolve("/work/lang/src/lang.forge");

test("the command line of the compiler, as the IntelliJ run configuration builds it", () => {
    assert.deepEqual(compilerArguments("/x/forge-lsp.jar", file, defaults, path.resolve("/work/lang")), [
        "-cp", "/x/forge-lsp.jar", "MainKt", "-f", file, "-d", path.resolve("/work/lang/build/generated/forge"), "-t", "kotlin",
    ]);
    const all = { outputDirectory: "gen", packageName: " my.lang ", target: "rust", optimize: true, keep: ["ClassFile", " Recv.g ", ""] };
    assert.deepEqual(compilerArguments("c.jar", file, all, undefined).slice(5), [
        "-d", path.resolve("/work/lang/src/gen"), "-p", "my.lang", "-t", "rust", "-opt", "-keep", "ClassFile,Recv.g",
    ]);
    // -keep only with -opt
    assert.ok(!compilerArguments("c.jar", file, { ...all, optimize: false }, undefined).includes("-keep"));
});

test("an absolute output directory stays as it is", () => {
    const dir = path.resolve("/tmp/out");
    assert.equal(outputPath({ ...defaults, outputDirectory: dir }, file, "/work"), dir);
});

test("an unknown target is reported", () => {
    assert.equal(checkSettings(defaults), undefined);
    assert.match(checkSettings({ ...defaults, target: "cobol" }) ?? "", /Unknown target 'cobol'/);
});
