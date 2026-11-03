#!/usr/bin/env python3
"""Compile every Kotlin sample in a markdown file against this SDK.

Reads ```kotlin fences out of the files given on the command line, generates a
throwaway Gradle project with one source file per sample, and compiles it
against the SDK sources. A sample an integrator could not compile fails here.

The SDK is included by path, so --sdk can point at a different checkout: the
docs repository runs this script from the SDK's main branch against the SDK's
newest released tag, because the tag is the version its readers install.

See scripts/doc-samples/README.md for the fence annotations and the prelude.

    check-doc-samples.py README.md
    check-doc-samples.py ../relavoi-docs/docs/sdks/android
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field

LANG = "kotlin"
MARKS = {"fragment", "reference", "manifest", "no-compile"}
HERE = pathlib.Path(__file__).resolve().parent.parent

# Imports every sample gets. Android docs elide platform imports because the
# IDE adds them; what matters is whether the sample uses *our* API correctly.
# Nothing from com.relavoi.sdk is supplied to a complete sample: one that names
# one of our types has to show the import, or inherit it from a fence above it
# on the same page, so a rename still breaks the build.
#
# A `fragment` is the exception, and only for the two root names below. A
# fragment is by definition a few lines out of a file that already calls the
# SDK, so `Relavoi` is already imported there; failing every snippet on the one
# line a reader never has to write would teach nobody anything. Everything
# narrower than the root -- DirectionMode, SessionState, RelavoiException -- is
# still the page's job to import, which is where a rename would show up.
PLATFORM_IMPORTS = [
    "android.Manifest",
    "android.app.Application",
    "android.content.Context",
    "android.content.Intent",
    "android.content.pm.PackageManager",
    "android.graphics.Color",
    "android.net.Uri",
    "android.os.Build",
    "android.os.Bundle",
    "android.provider.Settings",
    "android.util.Log",
    "android.widget.TextView",
    "androidx.activity.result.contract.ActivityResultContracts",
    "androidx.appcompat.app.AppCompatActivity",
    "androidx.core.content.ContextCompat",
    "androidx.fragment.app.Fragment",
    "androidx.lifecycle.ViewModel",
    "androidx.lifecycle.lifecycleScope",
    "androidx.lifecycle.viewModelScope",
    "com.relavoi.docsamples.BuildConfig",
    "kotlinx.coroutines.CoroutineScope",
    "kotlinx.coroutines.Dispatchers",
    "kotlinx.coroutines.launch",
]

FRAGMENT_SDK_IMPORTS = ["com.relavoi.sdk.Relavoi", "com.relavoi.sdk.RelavoiConfig"]

# What makes a top-level chunk a class *member* rather than a local statement.
# A bare `val session = ...` in a snippet is a local: the sessions page writes
# exactly that and then awaits on it. A member is marked as one, by a
# visibility or an annotation, or it is a fun or a type.
MEMBER_HEAD = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:"
    r"(?:public|private|internal|protected|override|abstract|final|open|lateinit|const)\s"
    r"|(?:data|sealed|annotation|value|inline|suspend)?\s*"
    r"(?:fun|class|object|interface|enum|typealias|companion)\b"
    r")"
)
NAMED = re.compile(
    r"\b(?:data\s+|sealed\s+|enum\s+|annotation\s+|value\s+)*"
    r"(class|object|interface|fun|val|var|typealias)\s+([A-Za-z_]\w*)"
)
IMPORT = re.compile(r"^\s*import\s+([\w.]+)")


@dataclass
class Sample:
    path: pathlib.Path
    line: int
    mark: str
    reason: str
    code: str
    pkg: str = ""
    declares: set[str] = field(default_factory=set)
    linemap: dict[int, int] = field(default_factory=dict)

    @property
    def where(self) -> str:
        return f"{self.path}:{self.line}"


def extract(path: pathlib.Path) -> list[Sample]:
    out: list[Sample] = []
    lines = path.read_text().split("\n")
    i = 0
    while i < len(lines):
        m = re.match(r"^```(\S+)(.*)$", lines[i])
        if not m:
            i += 1
            continue
        lang, meta = m.group(1), m.group(2).strip()
        j = i + 1
        body: list[str] = []
        while j < len(lines) and not re.match(r"^```\s*$", lines[j]):
            body.append(lines[j])
            j += 1
        if lang == LANG:
            words = meta.split()
            mark, reason = "", ""
            if words and words[0] in MARKS:
                mark, reason = words[0], " ".join(words[1:])
            elif words:
                print(
                    f"::error file={path},line={i + 1}::unknown fence annotation "
                    f"{words[0]!r}. Use one of: {', '.join(sorted(MARKS))}"
                )
                sys.exit(2)
            s = Sample(path=path, line=i + 1, mark=mark, reason=reason, code="\n".join(body))
            s.declares = {n for _k, n in NAMED.findall(s.code)}
            out.append(s)
        i = j + 1
    return out


def markdown_files(args: list[str]) -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for a in args:
        p = pathlib.Path(a)
        files += sorted(p.rglob("*.md")) if p.is_dir() else [p]
    return [f for f in files if f.name != "CLAUDE.md"]


# ── depth-aware chunking ─────────────────────────────────────────────────────
#
# A Kotlin fragment is either statements ("Relavoi.events.disconnect()") or
# class members ("private val launcher = registerForActivityResult(...)"), and
# the permissions page mixes both in one fence. Members cannot go in a function
# body and statements cannot go in a class body, so the two are separated here
# and the generated host class takes members directly and statements in a
# suspend function.


def chunks(code: str) -> list[tuple[bool, list[tuple[int, str]]]]:
    """Split top-level chunks, each flagged True when it is a declaration."""
    out: list[tuple[bool, list[tuple[int, str]]]] = []
    depth = 0
    cur: list[tuple[int, str]] = []
    is_decl = False
    for n, line in code:
        if depth == 0 and not cur:
            is_decl = bool(MEMBER_HEAD.match(line))
        cur.append((n, line))
        stripped = re.sub(r'"[^"]*"', '""', line)
        stripped = re.sub(r"//.*$", "", stripped)
        depth += stripped.count("{") + stripped.count("(") + stripped.count("[")
        depth -= stripped.count("}") + stripped.count(")") + stripped.count("]")
        if depth <= 0:
            depth = 0
            if line.strip():
                out.append((is_decl, cur))
                cur = []
            elif out:
                out[-1][1].append((n, line))
                cur = []
            else:
                cur = []
    if cur:
        out.append((is_decl, cur))
    return out


# ── the reference check ──────────────────────────────────────────────────────


def brace_body(src: str, from_index: int) -> str:
    start = src.find("{", from_index)
    if start < 0:
        return ""
    depth, k = 0, start
    while k < len(src):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[start : k + 1]
        k += 1
    return src[start:]


def paren_body(src: str, from_index: int) -> str:
    start = src.find("(", from_index)
    if start < 0:
        return ""
    depth, k = 0, start
    while k < len(src):
        if src[k] == "(":
            depth += 1
        elif src[k] == ")":
            depth -= 1
            if depth == 0:
                return src[start : k + 1]
        k += 1
    return src[start:]


def declarations(src: str) -> dict[str, dict]:
    """name -> {kind, params: set, props: set, entries: set}.

    A Kotlin data class declares its properties in the constructor
    parentheses, so both the parentheses and the braces are read.
    """
    out: dict[str, dict] = {}
    for m in NAMED.finditer(src):
        kind, name = m.group(1), m.group(2)
        if kind in ("class", "object", "interface", "typealias"):
            parens = paren_body(src, m.end())
            braces = brace_body(src, m.end())
            props = set(re.findall(r"\b(?:val|var)\s+(\w+)\s*:", parens + braces))
            # Enum entries: bare capitalised identifiers at the top of the body.
            entries = set(re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*[,;]?\s*$", braces, re.M))
            out.setdefault(name, {"kind": kind, "props": props, "entries": entries,
                                  "params": set()})
        elif kind == "fun":
            parens = paren_body(src, m.end())
            params = set(re.findall(r"(?:^|[(,])\s*(?:vararg\s+)?(\w+)\s*:", parens, re.M))
            out.setdefault(name, {"kind": "fun", "props": set(), "entries": set(),
                                  "params": params})
    return out


def sdk_declarations(sdk: pathlib.Path) -> dict[str, dict]:
    found: dict[str, dict] = {}
    for f in sorted((sdk / "relavoi-sdk" / "src" / "main").rglob("*.kt")):
        for name, info in declarations(f.read_text()).items():
            if name in found:
                for k in ("props", "entries", "params"):
                    found[name][k] |= info[k]
            else:
                found[name] = info
    return found


def check_reference(s: Sample, decls: dict[str, dict]) -> list[str]:
    problems: list[str] = []
    for name, info in declarations(s.code).items():
        real = decls.get(name)
        if real is None:
            problems.append(
                f"{s.where}: reference listing declares {info['kind']} {name}, which is "
                f"not declared in relavoi-sdk/src/main. Either the SDK renamed it or the "
                f"listing invented it."
            )
            continue
        for prop in sorted(info["props"] - real["props"]):
            problems.append(
                f"{s.where}: reference listing gives {name} a property `{prop}` "
                f"that {name} does not have."
            )
        for param in sorted(info["params"] - real["params"]):
            problems.append(
                f"{s.where}: reference listing gives {name}() a parameter `{param}` "
                f"that {name}() does not take."
            )
        for entry in sorted(info["entries"] - real["entries"]):
            problems.append(
                f"{s.where}: reference listing gives {name} an entry `{entry}` "
                f"that {name} does not have."
            )
        if info["entries"] and real["entries"] - info["entries"]:
            missing = ", ".join(sorted(real["entries"] - info["entries"]))
            problems.append(
                f"{s.where}: reference listing of {name} is missing {missing}. "
                f"A reader writing an exhaustive `when` from this listing will not compile."
            )
    return problems


# ── generation ───────────────────────────────────────────────────────────────

HOST_HEAD = [
    "// Wrapped by check-doc-samples.py: this fence is marked `fragment`, so it",
    "// is members and statements out of a class rather than a whole file. The",
    "// host is an Activity because that is what most of these snippets live in.",
    "@Suppress(\"unused\", \"UNUSED_VARIABLE\", \"UNUSED_PARAMETER\")",
    "class DocSampleHost : AppCompatActivity() {",
]


def render(s: Sample, inherited: set[str]) -> tuple[str, dict[int, int]]:
    body = [(s.line + 1 + i, t) for i, t in enumerate(s.code.split("\n"))]
    own = {m.group(1) for t in (t for _, t in body) if (m := IMPORT.match(t))}
    baseline = set(PLATFORM_IMPORTS) | inherited
    if s.mark == "fragment":
        baseline |= set(FRAGMENT_SDK_IMPORTS)
    supply = sorted(baseline - own)
    out: list[tuple[int | None, str]] = [
        (None, f"// {s.where} — generated from the markdown; do not edit."),
        (None, f"package {s.pkg}"),
        (None, ""),
    ]
    out += [(None, f"import {m}") for m in supply]
    code = [(n, t) for n, t in body if not IMPORT.match(t)]
    out += [(n, t) for n, t in body if IMPORT.match(t)]
    out += [(None, "")]
    if s.mark == "fragment":
        parts = chunks(code)
        members = [p for decl, p in parts if decl]
        stmts = [p for decl, p in parts if not decl]
        out += [(None, h) for h in HOST_HEAD]
        for p in members:
            out += p
        if stmts:
            out += [(None, "    suspend fun __docSample() {")]
            for p in stmts:
                out += p
            out += [(None, "    }")]
        out += [(None, "}")]
    else:
        out += code
    text = "\n".join(t for _, t in out) + "\n"
    return text, {i + 1: n for i, (n, _) in enumerate(out) if n is not None}


SAMPLES_BUILD = """// Generated by scripts/check-doc-samples.py. Nothing here is published.
//
// The dependencies are the ones the samples reach for, and they are the same
// libraries the docs tell an integrator to have: an Activity, a Fragment, a
// ViewModel, the permission launchers and coroutines. If a sample needs
// something that is not here, that is worth a moment's thought, because it
// means the docs are assuming a dependency they never mention.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.relavoi.docsamples"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        // The initialization page tells the reader to add exactly these three
        // fields to their own build file, so the samples that read them
        // compile here for the same reason they compile there.
        buildConfigField("String", "RELAVOI_API_KEY", "\\"rk_test_0000\\"")
        buildConfigField("String", "RELAVOI_API_SECRET", "\\"rs_0000\\"")
        buildConfigField("String", "RELAVOI_TENANT_ID", "\\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":relavoi-sdk"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
}
"""

SETTINGS = """// Generated by scripts/check-doc-samples.py.
//
// :relavoi-sdk is included from the SDK checkout by path rather than resolved
// as an artifact, so the samples compile against exactly the sources under
// test. The repository block is the SDK's own, because its dependencies are
// what have to resolve here.
pluginManagement {{
    repositories {{
        google()
        mavenCentral()
        gradlePluginPortal()
    }}
}}

dependencyResolutionManagement {{
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {{
        google()
        mavenCentral()
    }}
}}

rootProject.name = "relavoi-doc-samples"

include(":relavoi-sdk")
project(":relavoi-sdk").projectDir = file("{sdk_module}")

include(":samples")
"""


def write_project(out: pathlib.Path, sdk: pathlib.Path, samples: list[Sample],
                  prelude: dict[str, str]) -> None:
    if out.exists():
        shutil.rmtree(out)
    gen = out / "samples" / "src" / "main" / "kotlin"
    gen.mkdir(parents=True)
    (out / "settings.gradle.kts").write_text(
        SETTINGS.format(sdk_module=(sdk / "relavoi-sdk").resolve())
    )
    # The SDK's root build file declares the plugin versions and nothing else,
    # and its gradle.properties carries the publish coordinates its module
    # reads. Both are copied rather than restated so they cannot drift.
    shutil.copy(sdk / "build.gradle.kts", out / "build.gradle.kts")
    shutil.copy(sdk / "gradle.properties", out / "gradle.properties")
    (out / "samples" / "build.gradle.kts").write_text(SAMPLES_BUILD)
    # Where the Android SDK is. On a laptop that is local.properties, which is
    # gitignored and so differs per machine; on a runner it is ANDROID_HOME,
    # which AGP reads by itself.
    local = sdk / "local.properties"
    if local.exists():
        shutil.copy(local, out / "local.properties")
    elif not (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")):
        print("::warning::no local.properties in the SDK checkout and no ANDROID_HOME; "
              "the Android Gradle plugin will not find an SDK.")

    inherited: dict[pathlib.Path, set[str]] = {}
    for i, s in enumerate(samples, start=1):
        stem = re.sub(r"\W+", "", s.path.stem.replace("-", "")).lower()
        s.pkg = f"docsample.s{i:03d}{stem}"
        d = gen / pathlib.Path(*s.pkg.split("."))
        d.mkdir(parents=True)
        for name, text in prelude.items():
            if name in s.declares:
                continue
            (d / f"{name}.kt").write_text(f"package {s.pkg}\n\n{text}")
        page = inherited.setdefault(s.path, set())
        text, s.linemap = render(s, set(page))
        page |= {m.group(1) for line in s.code.split("\n") if (m := IMPORT.match(line))}
        (d / "Sample.kt").write_text(text)


KOTLIN_DIAG = re.compile(r"^e: file://(\S+\.kt):(\d+):(\d+) (.*)$")
KOTLIN_WARN = re.compile(r"^w: file://(\S+\.kt):(\d+):(\d+) (.*)$")


def compile_project(out: pathlib.Path, sdk: pathlib.Path, samples: list[Sample]) -> int:
    by_pkg = {s.pkg: s for s in samples}
    # The SDK's own wrapper, because it pins the Gradle version its AGP needs;
    # a system `gradle` on the host is routinely a major version ahead.
    gradlew = sdk / "gradlew"
    if not gradlew.exists():
        print(f"::error::{gradlew} is missing. The wrapper jar is not committed in "
              f"that repository; run `gradle wrapper --gradle-version 8.7` in it first.")
        return 2
    cmd = [str(gradlew), "--project-dir", str(out), "--console=plain", "-q",
           ":samples:compileDebugKotlin"]
    print("$ " + " ".join(cmd))
    proc = subprocess.run(cmd, cwd=sdk, capture_output=True, text=True)
    text = proc.stdout + proc.stderr
    errors: dict[str, list[str]] = {}
    warnings: dict[str, list[str]] = {}
    for line in text.split("\n"):
        line = line.strip()
        for rx, bucket in ((KOTLIN_DIAG, errors), (KOTLIN_WARN, warnings)):
            m = rx.match(line)
            if not m:
                continue
            f, ln, _col, msg = m.groups()
            p = pathlib.Path(f)
            s = by_pkg.get(".".join(p.parent.parts[-2:]))
            if s is None:
                continue
            if p.name == "Sample.kt":
                orig = s.linemap.get(int(ln))
                where = f"{s.path}:{orig}" if orig else f"{s.where} (generated line {ln})"
            else:
                where = f"{s.where} (via prelude {p.name})"
            entry = f"  {where}: {msg}"
            if entry not in bucket.setdefault(s.where, []):
                bucket[s.where].append(entry)
            break

    if warnings:
        print("\nWarnings (not fatal):")
        for where, msgs in sorted(warnings.items()):
            print(f"  {where}")
            print("\n".join(msgs))
    if not errors:
        if proc.returncode != 0:
            print("\nGradle failed without a Kotlin diagnostic this script could place:")
            print(text[-6000:])
            return 1
        return 0
    print("\nSamples that do not compile:\n")
    for where, msgs in sorted(errors.items()):
        print(f"::error::{where} does not compile")
        print("\n".join(msgs))
    return 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--sdk", default=str(HERE),
                    help="the SDK checkout to compile against; defaults to this one")
    ap.add_argument("--out", default="")
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()
    sdk = pathlib.Path(args.sdk).resolve()

    prelude_dir = pathlib.Path(__file__).resolve().parent / "doc-samples" / "prelude"
    prelude = {p.stem: p.read_text() for p in sorted(prelude_dir.glob("*.kt"))}

    files = markdown_files(args.files)
    samples: list[Sample] = []
    for f in files:
        samples += extract(f)
    if not samples:
        print(f"::error::no ```{LANG} fences found in {', '.join(map(str, files))}")
        return 2

    census: dict[str, int] = {}
    for s in samples:
        census[s.mark or "compiled"] = census.get(s.mark or "compiled", 0) + 1
    print(f"{len(samples)} kotlin fences in {len(files)} file(s):")
    for k in ("compiled", "fragment", "reference", "manifest", "no-compile"):
        if census.get(k):
            print(f"  {census[k]:3d}  {k}")
    for s in samples:
        if s.mark == "no-compile":
            if not s.reason:
                print(f"::error file={s.path},line={s.line}::`no-compile` needs a reason.")
                return 2
            print(f"  not checked: {s.where}: {s.reason}")

    decls = sdk_declarations(sdk)
    problems: list[str] = []
    for s in samples:
        if s.mark == "reference":
            problems += check_reference(s, decls)
    if problems:
        print("\nReference listings that disagree with relavoi-sdk/src/main:\n")
        for p in problems:
            print(f"::error::{p}")
        return 1
    print(f"\nReference listings agree with the SDK source "
          f"({census.get('reference', 0)} checked).")

    todo = [s for s in samples if s.mark in ("", "fragment")]
    if not todo:
        return 0
    out = pathlib.Path(args.out) if args.out else pathlib.Path(
        os.environ.get("TMPDIR", "/tmp")
    ) / "relavoi-doc-samples-kotlin"
    write_project(out, sdk, todo, prelude)
    rc = compile_project(out, sdk, todo)
    if rc == 0:
        print(f"\nAll {len(todo)} compiled samples build against {sdk}.")
        if not args.keep:
            shutil.rmtree(out, ignore_errors=True)
    else:
        print(f"\nGenerated project left at {out} for inspection.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
