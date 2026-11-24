import assert from "node:assert/strict";
import * as path from "node:path";
import { test } from "node:test";
import { findJava, javaCandidates, javaOfHome, parseJavaVersion } from "../src/java.ts";

test("reads the feature version of java -version", () => {
    assert.equal(parseJavaVersion('openjdk version "21.0.2" 2024-01-16\nOpenJDK Runtime Environment'), 21);
    assert.equal(parseJavaVersion('openjdk version "25" 2025-09-16'), 25);
    assert.equal(parseJavaVersion('java version "1.8.0_402"'), 8);
    assert.equal(parseJavaVersion("no version here"), undefined);
});

test("looks in the setting, JAVA_HOME and the PATH, in this order", () => {
    const java = process.platform === "win32" ? "java.exe" : "java";
    assert.deepEqual(javaCandidates("/opt/jdk", { JAVA_HOME: "/usr/lib/jvm/21" }), [
        path.join("/opt/jdk", "bin", java), path.join("/usr/lib/jvm/21", "bin", java), java,
    ]);
    assert.deepEqual(javaCandidates("  ", {}), [java]);
    assert.equal(javaOfHome("/jdk"), path.join("/jdk", "bin", java));
});

test("takes the first Java 21 or newer, and says what it found otherwise", async () => {
    const versions: Record<string, string> = { old: 'java version "1.8.0_1"', new: 'openjdk version "21.0.1"' };
    const probe = async (executable: string) => versions[executable];
    assert.deepEqual(await findJava(["missing", "old", "new"], probe), { executable: "new", version: 21 });
    const none = await findJava(["missing", "old"], probe);
    assert.ok("error" in none && none.error.includes("old (Java 8)") && none.error.includes("forge.java.home"), JSON.stringify(none));
    const nothing = await findJava(["missing"], probe);
    assert.ok("error" in nothing && nothing.error.includes("No Java was found"));
});
