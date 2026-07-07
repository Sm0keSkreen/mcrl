# Building Mcrl from source

This folder is the actual Gradle project, the Java agent's source, separate from the
install scripts and assets at the repo root so the top level stays focused on "how do
I install this."

## Requirements

- JDK 17 or newer to run Gradle itself (the agent is compiled targeting Java 8, see
  `build.gradle`, so the output jar still loads on much older runtimes, that's
  unrelated to what you need installed to build it)
- Network access, Gradle needs to pull down ASM and the Shadow plugin on first build

## Build

From this folder:

```
./gradlew shadowJar   # or: gradle shadowJar, or gradlew.bat on Windows
```

Output lands at `build/libs/mcrl.jar`.

## Layout

- `src/main/java/mcrl/agent/McrlAgent.java`, the `-javaagent` entry point
  (`premain`/`agentmain`)
- `src/main/java/mcrl/agent/ChatRestrictionTransformer.java`, the actual detection and
  patching logic, has a long class-level comment explaining the approach and why a few
  earlier approaches didn't work
- `build.gradle`, targets Java 8, relocates the bundled ASM package to avoid
  colliding with Fabric Loader's own bundled copy on the classpath

See the [main README](../README.md) for how the detection actually works and what it
touches.
