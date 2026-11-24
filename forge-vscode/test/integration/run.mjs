// Runs the integration test (suite.cjs) in a VS Code that it downloads (VSCODE_VERSION: "stable" by default, or e.g.
// "1.91.1"), on a workspace of Forge files. Needs the bundled extension (npm run build) and its server (npm run server);
// on Linux without a display, run it with xvfb-run.
import { runTests } from "@vscode/test-electron";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { fileURLToPath } from "node:url";

const extension = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
for (const needed of ["out/extension.cjs", "server/forge-lsp.jar"]) {
    if (!fs.existsSync(path.join(extension, needed))) throw new Error(`${needed} is missing: run npm run build and npm run server`);
}

const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "forge-vscode-test-"));
fs.writeFileSync(path.join(workspace, "lib.forge"), "/** A number. */\nnode Num(value: int)\n");
fs.writeFileSync(path.join(workspace, "main.forge"),
    'import "lib.forge"\nsyn Num.twice: int = this.value * 2\nsyn Num.bad: int = "text"\nconst c = 1\n');
fs.writeFileSync(path.join(workspace, "ok.forge"), "node Num(value: int)\nsyn Num.twice: int = this.value * 2\n");
const userData = fs.mkdtempSync(path.join(os.tmpdir(), "forge-vscode-user-"));

// Electron's sandbox does not run as root (e.g. in a container)
const asRoot = typeof process.getuid === "function" && process.getuid() === 0;

try {
    await runTests({
        version: process.env.VSCODE_VERSION || "stable",
        extensionDevelopmentPath: extension,
        extensionTestsPath: path.join(extension, "test", "integration", "suite.cjs"),
        launchArgs: [workspace, "--disable-extensions", `--user-data-dir=${userData}`, ...(asRoot ? ["--no-sandbox"] : [])],
        extensionTestsEnv: { FORGE_TEST_WORKSPACE: workspace },
    });
} finally {
    fs.rmSync(workspace, { recursive: true, force: true });
    fs.rmSync(userData, { recursive: true, force: true });
}
