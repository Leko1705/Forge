// Finding the Java (21 or newer) that runs the language server and the compiler. No VS Code API here, so that it can
// be tested with plain Node.
import { execFile } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";

/** The version of Java the server needs: the compiler is built for Java 21. */
export const REQUIRED_JAVA = 21;

export interface Java {
    /** The `java` executable. */
    executable: string;
    /** Its feature version, e.g. 21. */
    version: number;
}

const javaName = process.platform === "win32" ? "java.exe" : "java";

/** The `java` of a Java installation directory (a JAVA_HOME). */
export function javaOfHome(home: string): string {
    return path.join(home, "bin", javaName);
}

/**
 * Where to look for `java`, in this order: the setting `forge.java.home`, `JAVA_HOME`, `java` on the `PATH`.
 * An installation that is set but has no `java` is still a candidate, so that the error names it.
 */
export function javaCandidates(settingHome: string | undefined, env: NodeJS.ProcessEnv): string[] {
    const candidates: string[] = [];
    if (settingHome && settingHome.trim()) candidates.push(javaOfHome(settingHome.trim()));
    if (env.JAVA_HOME && env.JAVA_HOME.trim()) candidates.push(javaOfHome(env.JAVA_HOME.trim()));
    candidates.push(javaName);
    return candidates;
}

/**
 * The feature version in the output of `java -version`: `openjdk version "21.0.2" 2024-01-16` is 21,
 * `java version "1.8.0_402"` is 8.
 */
export function parseJavaVersion(output: string): number | undefined {
    const match = /version "(\d+)(?:\.(\d+))?[^"]*"/.exec(output);
    if (!match) return undefined;
    const major = Number(match[1]);
    return major === 1 && match[2] !== undefined ? Number(match[2]) : major;
}

/** Runs [executable] -version; its output (Java writes it to stderr), or undefined if it can not be run. */
export type VersionProbe = (executable: string) => Promise<string | undefined>;

export const probeJava: VersionProbe = (executable) =>
    new Promise((resolve) => {
        if (path.isAbsolute(executable) && !fs.existsSync(executable)) {
            resolve(undefined);
            return;
        }
        execFile(executable, ["-version"], { timeout: 20000 }, (error, stdout, stderr) => {
            if (error && !stderr) resolve(undefined);
            else resolve(`${stderr}\n${stdout}`);
        });
    });

/**
 * The first Java of [candidates] that is 21 or newer; otherwise why there is none, naming what was found, so that the
 * user knows what to set.
 */
export async function findJava(candidates: string[], probe: VersionProbe = probeJava): Promise<Java | { error: string }> {
    const found: string[] = [];
    for (const executable of candidates) {
        const output = await probe(executable);
        if (output === undefined) continue;
        const version = parseJavaVersion(output);
        if (version !== undefined && version >= REQUIRED_JAVA) return { executable, version };
        found.push(`${executable} (${version !== undefined ? `Java ${version}` : "unknown version"})`);
    }
    const what = found.length > 0 ? `Found only ${found.join(", ")}.` : "No Java was found.";
    return {
        error: `Forge needs Java ${REQUIRED_JAVA} or newer. ${what} Set "forge.java.home" to a Java installation, or JAVA_HOME.`,
    };
}
