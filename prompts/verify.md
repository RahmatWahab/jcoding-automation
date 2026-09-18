{{COMMON}}

[Asking clarify from agent]
- Did you implement fully according to the story ticket #<number> and EPIC ticket #<ticket> ?
- No half-baking, no guessing and no assuming.
- Can you re-confirm from the source codes and ticket and EPIC?
- Is my instruction clear ?
- If you're understand then proceed.

Phase: VERIFY. Run the repository's complete relevant formatting, lint,
compile, build, unit, integration, static-analysis, dependency, security,
migration, and contract checks. Fix failures within story scope and repeat the
suite. Do not claim success when required checks are skipped or failing.

Use an evidence ladder rather than starting every expensive lane immediately:
focused changed-behavior tests first, then component/integration/contract
tests, then the full repository suite, and finally E2E only when its complete
dependency path is implemented and isolated. Record the reason for every
expensive lane. If a lane cannot run within the phase budget, preserve its
failure output and prove the current story with the strongest safe substitute;
never hide the deferred lane or label a repository-owned failure external.

Determine the required validation scope from the current story's acceptance
criteria and available interfaces. Run real E2E only when the complete
dependency path exists. If a broader E2E path depends on a later story, do not
block this story solely because that path cannot exist yet. Instead verify the
current story with realistic contract, component, mock/fake, simulator,
replay, fixture, and negative-path tests as applicable. Record the unavailable
E2E scenario, its owning future dependency, the substitute evidence, and the
remaining integration risk. The substitute must prove business outcomes and
safety behavior, not merely execution or call counts.

Record each acceptance criterion and each required command as PASS, FAIL, or
NOT RUN with the reason. NOT RUN is not success. Environment failures are
blockers unless the repository explicitly documents a safe equivalent check.

Treat repository-owned validation infrastructure as part of the implementation
when it prevents an in-scope test from running. Repair fixed host-port
collisions, leaked containers/networks/volumes, unsafe traps, and non-isolated
fixtures in the repository test harness. Do not stop or modify unrelated
containers or services. Prefer ephemeral host-port mapping, explicit resource
ownership, and cleanup that is safe on success, failure, and interruption.
After the repair, rerun the failed check and the complete relevant validation
suite.

For every acceptance criterion, produce an evidence row containing: criterion,
business value, validation level, exact command/test or fixture, result, and
any explicitly deferred broader integration. A deferred broader E2E scenario
does not waive a current-story criterion; if the criterion itself cannot be
proven at any available level, report it as unmet.
