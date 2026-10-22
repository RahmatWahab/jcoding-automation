# Spec: Architecture Fundamentals Hardening

Status: Draft

A spec-driven design (SDD) document set describing the refactor of the
`jcoding-automation` controller toward an explicit, testable workflow
architecture (typed workflow state, a pure Progress Guard, and per-phase
handlers replacing the monolithic `reconcileAndExecute`).

## Documents

Read in order:

1. [requirements.md](requirements.md) — problem statement and acceptance criteria.
2. [design.md](design.md) — target architecture and component decomposition.
3. [tasks.md](tasks.md) — incremental, behavior-preserving delivery plan.

## Conventions

- One directory per spec, named as a lowercase, hyphenated slug.
- The three documents are always `requirements.md`, `design.md`, `tasks.md`.
- Draft status is recorded in each document's header, not in the directory name.
