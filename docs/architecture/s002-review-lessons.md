# S002 Review Lessons

## Findings from PR #17

Three valid P2 findings exposed foundation-contract gaps:

1. `WorkflowPhase` used coarse or invented phases instead of preserving the
   exact frozen Python phase contract from `python-behavior-inventory.md`.
2. `ArchitectureFitnessCheck` detected package edges only through imports and
   missed fully qualified class references.
3. `dependencySecurityCheck` rejected unsafe version strings but did not reject
   Gradle external module dependencies marked `changing = true`.

## Root Causes

The original implementation translated intent instead of copying the exact
frozen contract for workflow phases. The architecture scanner tested the common
Java import path but not semantically equivalent fully qualified references.
The dependency governance check focused on coordinate text and missed Gradle's
mutable artifact flag.

## Permanent Controls

- Workflow phase values are now the exact frozen Python inventory values, with
  a regression test that fails on missing, reordered, or invented phases.
- Architecture fitness checks parse both import statements and fully qualified
  package references, with negative tests for forbidden domain-to-infrastructure
  dependencies and package cycles expressed without imports.
- Dependency governance now rejects `ExternalModuleDependency.changing` and has
  a self-test wired into `check`.
