// The integration test, run in VS Code by run.mjs: the extension starts the language server, and VS Code's features
// get their answers from it.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vscode = require("vscode");

const workspace = process.env.FORGE_TEST_WORKSPACE;
const uriOf = (name) => vscode.Uri.file(path.join(workspace, name));

async function until(what, check, timeout = 90000) {
    const deadline = Date.now() + timeout;
    for (;;) {
        const result = await check();
        if (result) return result;
        if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
        await new Promise((resolve) => setTimeout(resolve, 200));
    }
}

/** The position after [marker] in [document]. */
function after(document, marker, delta = 0) {
    const index = document.getText().indexOf(marker);
    assert.ok(index >= 0, `no '${marker}'`);
    return document.positionAt(index + marker.length + delta);
}

const tests = {
    async "publishes the compiler's diagnostics"() {
        const document = await vscode.workspace.openTextDocument(uriOf("main.forge"));
        await vscode.window.showTextDocument(document);
        assert.equal(document.languageId, "forge");
        const diagnostics = await until("diagnostics", () => {
            const d = vscode.languages.getDiagnostics(document.uri);
            return d.length > 0 ? d : undefined;
        });
        assert.equal(diagnostics.length, 1, JSON.stringify(diagnostics));
        assert.equal(diagnostics[0].message, "expected type 'int' but got str");
        assert.equal(diagnostics[0].source, "forge");
        assert.equal(diagnostics[0].code, "E009");
        assert.equal(document.getText(diagnostics[0].range), '"text"');
    },

    async "updates the diagnostics on an edit"() {
        const document = await vscode.workspace.openTextDocument(uriOf("main.forge"));
        const editor = await vscode.window.showTextDocument(document);
        const start = document.getText().indexOf('"text"');
        await editor.edit((edit) => edit.replace(new vscode.Range(document.positionAt(start), document.positionAt(start + 6)), "3"));
        await until("no diagnostics", () => vscode.languages.getDiagnostics(document.uri).length === 0);
    },

    async "completes members"() {
        const document = await vscode.workspace.openTextDocument(uriOf("main.forge"));
        const list = await vscode.commands.executeCommand("vscode.executeCompletionItemProvider", document.uri, after(document, "this."));
        const value = list.items.find((item) => (typeof item.label === "string" ? item.label : item.label.label) === "value");
        assert.ok(value, JSON.stringify(list.items.map((item) => item.label)));
        assert.equal(value.kind, vscode.CompletionItemKind.Field);
    },

    async "goes to the definition in the imported file"() {
        const document = await vscode.workspace.openTextDocument(uriOf("main.forge"));
        const locations = await vscode.commands.executeCommand("vscode.executeDefinitionProvider", document.uri, after(document, "this."));
        assert.equal(locations.length, 1);
        const location = locations[0];
        const uri = location.uri ?? location.targetUri;
        const range = location.range ?? location.targetSelectionRange ?? location.targetRange;
        assert.equal(fs.realpathSync(uri.fsPath), fs.realpathSync(uriOf("lib.forge").fsPath));
        assert.equal(range.start.line, 1);
        assert.equal(range.start.character, "node Num(".length);
    },

    async "shows the documentation on hover"() {
        const document = await vscode.workspace.openTextDocument(uriOf("lib.forge"));
        const hovers = await vscode.commands.executeCommand("vscode.executeHoverProvider", document.uri, after(document, "node "));
        const text = hovers.flatMap((h) => h.contents.map((c) => (typeof c === "string" ? c : c.value))).join("\n");
        assert.ok(text.includes("node Num(value: int)") && text.includes("A number."), text);
    },

    async "lists the declarations"() {
        const document = await vscode.workspace.openTextDocument(uriOf("main.forge"));
        const symbols = await vscode.commands.executeCommand("vscode.executeDocumentSymbolProvider", document.uri);
        assert.deepEqual(symbols.map((s) => s.name), ["Num.twice", "Num.bad", "c"]);
        assert.equal(symbols[2].kind, vscode.SymbolKind.Constant);
    },

    async "colors the names by what they are"() {
        const document = await vscode.workspace.openTextDocument(uriOf("main.forge"));
        const legend = await vscode.commands.executeCommand("vscode.provideDocumentSemanticTokensLegend", document.uri);
        assert.ok(legend.tokenTypes.includes("attribute"), JSON.stringify(legend));
        const tokens = await vscode.commands.executeCommand("vscode.provideDocumentSemanticTokens", document.uri);
        assert.ok(tokens && tokens.data.length > 0);
        // the first name, `Num` of `syn Num.twice` on line 1: a type
        assert.equal(tokens.data[0], 1);
        assert.equal(legend.tokenTypes[tokens.data[3]], "type");
    },

    async "compiles a file"() {
        const file = uriOf("ok.forge");
        await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(file));
        await vscode.commands.executeCommand("forge.compile", file);
        const generated = path.join(workspace, "build", "generated", "forge", "ok.kt");
        await until(generated, () => fs.existsSync(generated) && fs.readFileSync(generated, "utf8").includes("twice"), 120000);
    },
};

exports.run = async function run() {
    const extension = vscode.extensions.getExtension("leko.forge-vscode");
    assert.ok(extension, "the extension is not installed");
    await extension.activate();
    const failures = [];
    for (const [name, test] of Object.entries(tests)) {
        try {
            await test();
            console.log(`ok - ${name}`);
        } catch (e) {
            console.log(`not ok - ${name}\n${e && e.stack ? e.stack : e}`);
            failures.push(name);
        }
    }
    if (failures.length > 0) throw new Error(`${failures.length} of ${Object.keys(tests).length} tests failed: ${failures.join(", ")}`);
};
