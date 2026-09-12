# S001 baseline environment snapshot

All values below were captured while characterizing frozen Python revision
`c8eaaf2756df3e3dcaf946d995a1ed1db56e93bf` on 2026-09-12 in Europe/Berlin.

| Item | Value |
|---|---|
| OS | Ubuntu 24.04.4 LTS (Noble Numbat) |
| Python | 3.12.3 |
| pytest | 7.4.4 |
| Git | 2.43.0 |
| Python packaging | setuptools >= 68; project requires Python >= 3.11 |
| Test command | `python3 -m pytest -q /home/algo/codex-coding-automation/tests` |
| Reference checkout | `/home/algo/codex-coding-automation` |
| Java target baseline | `b58bf1f2d2bdc9593927542e234c13fc71aeff16` |

The committed revision and tree hash are authoritative. Operator-controlled
runtime files in the live reference checkout are not used as snapshots; the
manifest hashes the committed configuration and prompt contents.
