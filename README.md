# jcoding-automation

Java 21 migration target for the frozen Python Codex coding automation system.

The migration is governed by [EPIC #1](https://github.com/RahmatWahab/jcoding-automation/issues/1).
The Python repository is the behavioral reference. Java behavior must not be
invented, simplified, or declared equivalent without characterization and
parity evidence.

S001 records the frozen Python baseline. S002 establishes the Java 21 modular
monolith foundation used by later parity stories.

## Build

The project targets Java 21 and uses Gradle Wrapper as the required build entry
point.

```bash
./gradlew clean check installDist
build/install/jcoding-automation/bin/jcoding-automation --version
```

## Runtime Contract

- `--version` prints the application name, version, Java runtime, and operating
  system.
- `--help` prints the supported bootstrap commands.
- `run` currently starts the S002 bootstrap context and exits after verifying
  dependency wiring and graceful shutdown hooks. Workflow behavior is delivered
  by later stories in EPIC #1.

## Architecture

The application is a modular monolith. External side effects must stay behind
interfaces and adapters; workflow decisions must remain pure wherever possible.
See `docs/architecture/adr-0001-java-21-gradle-foundation.md`,
`docs/architecture/adr-0002-modular-monolith-boundaries.md`, and
`docs/architecture/c4.md`.
