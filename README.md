# jcoding-automation

Java 21 migration target for the frozen Python Codex coding automation system.

The migration is governed by [EPIC #1](https://github.com/RahmatWahab/jcoding-automation/issues/1).
The Python repository is the behavioral reference. Java behavior must not be
invented, simplified, or declared equivalent without characterization and
parity evidence.

This commit is only the repository bootstrap. Implementation begins with
[S001](https://github.com/RahmatWahab/jcoding-automation/issues/2).

## Build

The project targets Java 21 and uses Gradle Wrapper as the required build entry
point. The wrapper is delivered by S002 because the empty repository had no
existing Gradle installation or wrapper artifacts.
