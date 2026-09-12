# ADR-0001: Java 21 and Gradle Wrapper Foundation

## Status

Accepted for S002.

## Context

EPIC #1 requires a Java migration target that is maintainable, reliable,
persistent, and equivalent to the frozen Python automation. S002 must establish
the build and runtime foundation before behavior stories begin.

## Decision

Use Java 21 LTS and the Gradle Wrapper as the only supported build, test,
distribution, and launch entry point.

The project pins the Gradle distribution in `gradle/wrapper/gradle-wrapper.properties`
with a SHA-256 checksum. Dependency versions are declared explicitly in
`build.gradle`, and Gradle dependency locking is enabled so dependency changes
are reviewable.

## Consequences

- Developers and automation must use `./gradlew`, not a machine-local Gradle.
- Java 21 is the minimum runtime and compile target for S002.
- Later stories must add behavior behind the package boundaries established in
  this story instead of bypassing the build foundation.

## Evidence

- `./gradlew clean test`
- `./gradlew installDist`
- `build/install/jcoding-automation/bin/jcoding-automation --version`
