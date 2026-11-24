// Builds the language server (./gradlew :forge-lsp:serverJar) and copies it into the extension: server/forge-lsp.jar.
import { execFileSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";

const extension = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const root = path.resolve(extension, "..");
const windows = process.platform === "win32";
const gradlew = path.join(root, windows ? "gradlew.bat" : "gradlew");

execFileSync(gradlew, [":forge-lsp:serverJar", "--console=plain"], { cwd: root, stdio: "inherit", shell: windows });

const libs = path.join(root, "forge-lsp", "build", "libs");
const jar = fs.readdirSync(libs).find((name) => name.endsWith("-all.jar"));
if (!jar) throw new Error(`no -all.jar in ${libs}`);
fs.mkdirSync(path.join(extension, "server"), { recursive: true });
fs.copyFileSync(path.join(libs, jar), path.join(extension, "server", "forge-lsp.jar"));
console.log(`copied ${jar} to server/forge-lsp.jar`);
