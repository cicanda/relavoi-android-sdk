# gradle-wrapper.jar — binary placeholder

The actual `gradle-wrapper.jar` is a binary file and is **not committed** to this
repository. To generate it on a fresh checkout, install Gradle 8.7+ once
(`brew install gradle` on macOS) and run from this directory:

```bash
gradle wrapper --gradle-version 8.7
```

That writes `gradle-wrapper.jar` next to this file, plus `gradlew` and
`gradlew.bat` at the repo root. After that, build with the wrapper
(`./gradlew :relavoi-sdk:assembleRelease`).
