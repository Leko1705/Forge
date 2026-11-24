// The Forge extension: starts the Forge language server (forge-lsp, a Java program) and adds the commands
// "Forge: Compile" and "Forge: Restart Language Server".
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { LanguageClient, type LanguageClientOptions, type ServerOptions, TransportKind } from "vscode-languageclient/node";
import { checkSettings, compilerArguments, type CompileSettings, outputPath } from "./compile.ts";
import { findJava, type Java, javaCandidates } from "./java.ts";

let client: LanguageClient | undefined;
let output: vscode.LogOutputChannel;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    output = vscode.window.createOutputChannel("Forge", { log: true });
    context.subscriptions.push(
        output,
        vscode.commands.registerCommand("forge.restartServer", () => restart(context)),
        vscode.commands.registerCommand("forge.compile", (uri?: vscode.Uri) => compile(context, uri)),
        vscode.workspace.onDidChangeConfiguration((event) => {
            if (event.affectsConfiguration("forge.java") || event.affectsConfiguration("forge.server")) void restart(context);
        }),
    );
    await start(context);
}

export async function deactivate(): Promise<void> {
    await client?.stop();
    client = undefined;
}

/** The Java that runs the server and the compiler; undefined (and an error shown) if there is none. */
async function java(): Promise<Java | undefined> {
    const home = vscode.workspace.getConfiguration("forge").get<string>("java.home");
    const found = await findJava(javaCandidates(home, process.env));
    if ("error" in found) {
        output.error(found.error);
        const choice = await vscode.window.showErrorMessage(found.error, "Open Settings");
        if (choice === "Open Settings") void vscode.commands.executeCommand("workbench.action.openSettings", "forge.java.home");
        return undefined;
    }
    return found;
}

/** The jar of the language server, which also contains the compiler; undefined (and an error shown) if it is missing. */
function serverJar(context: vscode.ExtensionContext): string | undefined {
    const configured = vscode.workspace.getConfiguration("forge").get<string>("server.path")?.trim();
    const jar = configured || context.asAbsolutePath(path.join("server", "forge-lsp.jar"));
    if (!fs.existsSync(jar)) {
        const message = configured
            ? `The Forge language server ${jar} (forge.server.path) does not exist.`
            : `The Forge language server is missing from the extension (${jar}); build it with "npm run server".`;
        output.error(message);
        void vscode.window.showErrorMessage(message);
        return undefined;
    }
    return jar;
}

async function start(context: vscode.ExtensionContext): Promise<void> {
    const runtime = await java();
    const jar = serverJar(context);
    if (!runtime || !jar) return;
    const vmArguments = vscode.workspace.getConfiguration("forge").get<string[]>("server.vmArguments") ?? [];
    output.info(`Starting the Forge language server: ${runtime.executable} (Java ${runtime.version}) -jar ${jar}`);

    const serverOptions: ServerOptions = {
        command: runtime.executable,
        args: [...vmArguments, "-jar", jar],
        transport: TransportKind.stdio,
    };
    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { scheme: "file", language: "forge" },
            { scheme: "untitled", language: "forge" },
        ],
        synchronize: {
            // files changed on disk, e.g. an imported file edited elsewhere
            fileEvents: vscode.workspace.createFileSystemWatcher("**/*.forge"),
        },
        outputChannel: output,
    };
    client = new LanguageClient("forge", "Forge Language Server", serverOptions, clientOptions);
    try {
        await client.start();
    } catch (e) {
        void vscode.window.showErrorMessage(`The Forge language server did not start: ${e}`);
    }
}

async function restart(context: vscode.ExtensionContext): Promise<void> {
    if (client) {
        await client.stop().catch(() => undefined);
        client = undefined;
    }
    await start(context);
}

/** The compile settings of the workspace folder of [uri]. */
function compileSettings(uri: vscode.Uri): CompileSettings {
    const config = vscode.workspace.getConfiguration("forge.compile", uri);
    return {
        outputDirectory: config.get<string>("outputDirectory") ?? "",
        packageName: config.get<string>("package") ?? "",
        target: config.get<string>("target") ?? "kotlin",
        optimize: config.get<boolean>("optimize") ?? false,
        keep: config.get<string[]>("keep") ?? [],
    };
}

/** Runs the Forge compiler on [uri] (the active editor's file if none) as a task, with its output in a terminal. */
async function compile(context: vscode.ExtensionContext, uri?: vscode.Uri): Promise<void> {
    const target = uri ?? vscode.window.activeTextEditor?.document.uri;
    if (!target || target.scheme !== "file" || !target.fsPath.endsWith(".forge")) {
        void vscode.window.showErrorMessage("Forge: Compile needs a .forge file that is saved on disk.");
        return;
    }
    const open = vscode.workspace.textDocuments.find((d) => d.uri.toString() === target.toString());
    if (open?.isDirty) await open.save();

    const settings = compileSettings(target);
    const problem = checkSettings(settings);
    if (problem) {
        void vscode.window.showErrorMessage(problem);
        return;
    }
    const runtime = await java();
    const jar = serverJar(context);
    if (!runtime || !jar) return;

    const folder = vscode.workspace.getWorkspaceFolder(target);
    const file = target.fsPath;
    const outDir = outputPath(settings, file, folder?.uri.fsPath);
    const args = compilerArguments(jar, file, settings, folder?.uri.fsPath);
    const name = `Compile ${path.basename(file)}`;
    const task = new vscode.Task(
        { type: "forge", file },
        folder ?? vscode.TaskScope.Workspace,
        name,
        "Forge",
        new vscode.ProcessExecution(runtime.executable, args, { cwd: folder?.uri.fsPath ?? path.dirname(file) }),
    );
    task.presentationOptions = { reveal: vscode.TaskRevealKind.Always, clear: true };

    const execution = await vscode.tasks.executeTask(task);
    const ended = vscode.tasks.onDidEndTaskProcess((event) => {
        if (event.execution !== execution) return;
        ended.dispose();
        if (event.exitCode === 0) {
            void vscode.window.showInformationMessage(`Forge: compiled ${path.basename(file)} to ${outDir}`, "Reveal").then((choice) => {
                if (choice === "Reveal") void vscode.commands.executeCommand("revealFileInOS", vscode.Uri.file(outDir));
            });
        } else {
            void vscode.window.showErrorMessage(`Forge: ${path.basename(file)} did not compile (see the terminal).`);
        }
    });
}
