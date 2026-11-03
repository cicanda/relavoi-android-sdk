# Doc sample compile check

`scripts/check-doc-samples.py` takes markdown files, pulls every ```kotlin
fence out of them, generates a throwaway Gradle project with one source file
per sample, and compiles it against the SDK sources. A sample that would not
compile for an integrator fails the build.

It runs over this repository's `README.md` in CI, and the docs repository runs
it on a schedule over `docs/sdks/android/`.

The generated project lives in `$TMPDIR` and includes `:relavoi-sdk` from a
checkout by path, which `--sdk` chooses. That is not incidental: the docs
repository runs the script from this branch against the SDK's newest released
tag, because the tag is the version a reader of the docs has installed, while
the harness itself should be the current one. It uses this repository's Gradle
wrapper, which pins the version its AGP needs, so the wrapper jar has to exist
(it is not committed; `gradle wrapper --gradle-version 8.7` makes it).

## Annotating a fence

The fence info string carries the annotation, after the language:

| Mark | Meaning | What is checked |
|---|---|---|
| (none) | A complete, copy-pasteable sample. | Compiles as its own file. |
| `fragment` | A snippet that needs surrounding context: a few statements, or members lifted out of an Activity. | Compiles, inside a generated `AppCompatActivity`. Members go in the class body and statements go in a `suspend fun`, so the permissions page can mix both in one fence. |
| `reference` | A listing of API this SDK provides, shown so a reader can see the shape. Not for copying. | Every type and function it names must exist in `relavoi-sdk/src/main`, with the properties, parameters and enum entries it lists. An enum listing must also be complete. |
| `manifest` | An excerpt of a Gradle file: `settings.gradle.kts`, `app/build.gradle.kts`. | Not compiled. It is not app code. |
| `no-compile <reason>` | Last resort. The reason is required and is printed in the census. | Nothing. |

So:

    ```kotlin fragment
    Relavoi.events.disconnect()
    ```

`reference` is not a free pass. It is a different check, and it is the one that
would have caught `recordingEnabled` surviving in the docs for two releases
after 0.3.0 removed it from `create`.

## Imports

Android docs elide platform imports, because Android Studio adds them and a
page that listed `android.os.Bundle` every time would be worse to read. So the
harness supplies a fixed set of platform imports, listed in the script.

It supplies nothing from `com.relavoi.sdk` to a complete sample: that import
is the page's job, and keeping it the page's job is how a rename in this SDK
shows up as a failing doc sample. A `fragment` gets `Relavoi` and
`RelavoiConfig`, and only those two, because a fragment is lines out of a file
that already calls the SDK.

A sample also inherits the imports shown by earlier fences on the same page,
which is the reader's situation working down a page.

## The prelude

A sample is written for an app that already exists, so it reaches for symbols
this SDK does not provide: `myBackend`, a `banner` TextView, the per-event
handler functions the events page dispatches to. Those are declared once in
`prelude/`, and copied into each sample's generated package.

A prelude file is left out of a sample that declares something with the file's
own name, so a page can both use a symbol and, further down, show how to write
it. That matching is by file name, so a file holding several symbols (like
`handlers.kt`) must only hold symbols no fence ever declares.

Adding to the prelude is a decision worth making deliberately: it means the
docs invented a name and the reader is expected to supply it. Keep the stub as
thin as the sample needs.
