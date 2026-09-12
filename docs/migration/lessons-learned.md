# S001 review lessons and prevention controls

## Findings from PR #16

Two valid P1 findings exposed evidence-quality gaps:

1. Replay fixtures were validated only as JSON shape. A wrong `expected` value
   could pass and become a false migration contract.
2. The baseline test command used the live Python checkout. Local edits could
   change the tests or imported source while the committed revision and tree
   hash still appeared correct.
3. The semantic replay used the exported snapshot on `PYTHONPATH`, but the
   final pytest process did not. If `codex_automation` was installed in the
   validation environment, pytest could import mutable installed code while
   running frozen tests.

## Root causes

The original validation treated presence and metadata as evidence of behavior,
and treated the checkout revision as equivalent to the files executed by the
test command. It also assumed interpreter-local import settings carried across
processes. Those assumptions are not safe for a migration baseline.

## Permanent controls

- Every replay fixture domain is executed against the frozen Python source and
  its result is compared with the recorded expected outcome.
- The Python revision is exported from its immutable Git object into a
  temporary snapshot before tests run. Mutable operator files cannot affect
  baseline evidence.
- Every Python process that imports the frozen automation source, including
  pytest, is launched with the snapshot source path on `PYTHONPATH`.
- Snapshot hashes, the revision/tree hash, fixture schemas, matrix coverage,
  and the complete frozen regression suite are checked on every run.
- Any mismatch fails closed; it cannot be reported as successful evidence.

## Review rule for future stories

For migration or compatibility work, distinguish three separate proofs:

1. provenance — exactly which source was inspected and executed;
2. semantics — recorded inputs produce independently verified outputs; and
3. completeness — every required artifact, boundary, negative case, and
   recovery contract is covered.

Artifact existence, a passing command, or a well-formed fixture alone is not
completion evidence.
