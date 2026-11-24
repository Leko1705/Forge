// The command line of the Forge compiler, as the IntelliJ run configuration builds it. No VS Code API here, so that it
// can be tested with plain Node.
import * as path from "node:path";

export const TARGETS = ["kotlin", "java", "python", "cpp", "rust", "ocaml", "javascript"] as const;

export interface CompileSettings {
    /** Where the generated code goes (`-d`); relative to the workspace folder, or to the file's directory without one. */
    outputDirectory: string;
    /** The package (`-p`), or empty. */
    packageName: string;
    /** The target language (`-t`). */
    target: string;
    /** `-opt` */
    optimize: boolean;
    /** `-keep`, with `-opt`. */
    keep: string[];
}

export const DEFAULT_OUTPUT = "build/generated/forge";

/** The output directory as an absolute path. */
export function outputPath(settings: CompileSettings, file: string, workspaceFolder: string | undefined): string {
    const dir = settings.outputDirectory.trim() || DEFAULT_OUTPUT;
    if (path.isAbsolute(dir)) return dir;
    return path.resolve(workspaceFolder ?? path.dirname(file), dir);
}

/** Why the settings can not be used, or undefined. */
export function checkSettings(settings: CompileSettings): string | undefined {
    if (!(TARGETS as readonly string[]).includes(settings.target)) {
        return `Unknown target '${settings.target}' (forge.compile.target): one of ${TARGETS.join(", ")}.`;
    }
    return undefined;
}

/** The arguments of `java` that compile [file]: `-cp <compiler> MainKt -f <file> -d <dir> ...`. */
export function compilerArguments(compilerJar: string, file: string, settings: CompileSettings, workspaceFolder: string | undefined): string[] {
    const args = ["-cp", compilerJar, "MainKt", "-f", path.resolve(file), "-d", outputPath(settings, file, workspaceFolder)];
    if (settings.packageName.trim()) args.push("-p", settings.packageName.trim());
    args.push("-t", settings.target);
    if (settings.optimize) {
        args.push("-opt");
        const keep = settings.keep.map((k) => k.trim()).filter((k) => k.length > 0);
        if (keep.length > 0) args.push("-keep", keep.join(","));
    }
    return args;
}
