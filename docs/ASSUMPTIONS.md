# Assumptions

Every assumption found in the code and the tests, from a deliberate audit
(2026-08-05), with what guards it or why it is accepted. An assumption is not a
defect — software cannot be written without them — but an *unexamined* one is
how every defect this project shipped got out. This file is where they stop
being unexamined.

Three kinds of entry:

- **Guarded** — a test exists whose failure means the assumption broke, and the
  test has been shown able to fail (`bb mutate` or observed during writing).
- **Accepted** — deliberately unguarded, with the reasoning and the blast
  radius written down.
- **Was wrong** — the audit found the assumption false. Fixed, with a test.

---

## Found wrong by this audit

| Assumption | Reality | Fix |
|---|---|---|
| The banner warning covers every mode that lets a caller name a path | It tested `= :path` exactly, so `both` — which grants everything `path` grants — carried no warning | Warns on `#{:path :both}`; tested from R64's reasoning, per mode |
| The header question panel renders | No test had ever constructed the `:unsure` + unanswered state; the panel was 19 uncovered lines | Rendered and both answers asserted; answering makes it disappear |
| The Trash accounting renders | `:trashed` / `:left-behind` result lines never rendered in any test, in any language | Rendered, counts asserted, plural forms exercised in fr/zh/ja |
| The scanner's quote-exit branches are covered by the fixtures | The `:quote-seen` → delimiter/CR/EOF transitions were the least-covered lines in the one namespace where a miss is silent corruption | Each exit exercised explicitly |

## Guarded

| Assumption | Where | Guard |
|---|---|---|
| A multi-byte character straddling the 64 KiB sniff boundary is truncation, not corruption | `encoding/detect` | End-to-end test with é split across byte 65,536; misreading demotes UTF-8 to windows-1252 |
| Numbers are written and read in the interface language, and rewritten at the moment it changes | `format/restate`, `state/with-language` | R80 tests + mutations |
| Stored settings never hold locale-formatted text | `prefs`, `state/remembered-values` | R81 tests + `every-remembered-setting-is-classified`, which fails when a new setting is added unclassified |
| The bundled runtime contains locale data | packaging scripts | R83: parsed from `--add-modules`, checked inside the built image, binary launched before wrapping |
| Comma wins delimiter ties; nothing separator-like falls back to comma | `csv/detect-delimiter` | Explicit tie and fallback tests |
| A stale folder-inspection reply cannot overwrite the current folder's | `state/::out-dir-inspected` | Test with mismatched dir |
| An unrecognised charset label means "detected", never nil | `state/charset-for-label` | Round-trip through every language + fallback test |
| The window geometry survives a restart, and nonsense geometry is ignored | `app/session-settings`, `view/remembered-window` | R55/R58: read from a real (never-shown) Stage, sanity bounds tested |
| Every picker label maps back to the value it stands for, in every language | `app/handle-event` | Routing tests over all six languages |
| A split that cannot write reports a failure event, never a bare exception | `app/split-worker` | Test against an impossible output folder |
| The service reports a job that threw as `failed`, with the reason | `api.jobs/start!` | Test with a file deleted between survey and execution |

## Accepted

| Assumption | Reasoning | Blast radius if wrong |
|---|---|---|
| 64 KiB of head is enough to sniff encoding and delimiter | A file that switches encoding or delimiter after 64 KiB is corrupt in a way no sniff length fixes | Wrong delimiter → visibly wrong preview; the user can override both |
| "MB" means MiB (×1,048,576) | Matches what file managers show for the resulting files, which is the number users will compare against | Sizes ~5% larger than a decimal reading; consistent in both directions |
| Index padding is fixed at max(4, digits of *planned* count) before writing starts | Width must be chosen before file 1 is written; a byte-mode plan is an estimate | A byte-mode split whose reality exceeds 9,999 files against a smaller estimate sorts `_10000` before `_9999` in file managers. Requires the user to have confirmed a >10,000-file split first. Names remain unique and correct |
| `zh` serves Traditional-script readers with Simplified strings | One Chinese translation; `normalise-tag` takes the primary subtag | Readable but non-native script for zh-TW/zh-HK users; a Traditional bundle can be dropped into the languages folder |
| Finished API jobs expire only when a new job starts or the service stops | A poll-only client holds at most its own finished jobs for the 30-minute window | Bounded memory (one map entry per job), reclaimed at the next start |
| The token is printed to stdout | The user asked for a service; the terminal is theirs. Documented in SECURITY.md | Anything reading the process's stdout could read the token — a process that can do that can read `.nvd-api-key` too |
| `free-space` may be unknown (nil) on exotic filesystems; an unknown never blocks a split | Refusing to split because the filesystem would not answer would be a worse failure than trusting the user | The disk-full refusal degrades to the OS's own write error, which is caught and shown |
| `split/terminated`'s fallback branch is unreachable through `execute!` | A header row with no terminator means the header is the file's last line, which means no data rows, which `plan` refuses | Defensive only; unit-tested directly so a refactor that makes it reachable finds it working |
| Drag events, file dialogs and `System/exit` are untested | `DragEvent`/chooser dialogs cannot be constructed headlessly; `exit!` ends the test JVM. All are behind vars or thin wrappers so everything around them is tested | The wiring layer; a break is visible on first manual launch, which `bb package` performs |

## Assumptions the *tests* make

These are about the suite itself — the ones rule 3 of the testing standard is
aimed at.

- **The header-detection thresholds (0.62 / 0.34) are tested against the
  specification, and the specification was written from the implementation.**
  This circularity is structural: the thresholds are tuned judgment, not
  derivable from a standard. What the tests actually pin is *behaviour on named
  examples* (a letter is not a table, an all-numeric first row is data, named
  columns are a header), so a threshold change that breaks a judgment call
  fails a named example, not a number.
- **Number-format expectations are CLDR literals, not calls to the code under
  test** — with a guard that the six languages' forms are not all identical,
  because "all identical" is what a runtime with no locale data produces.
- **Fixture files are assumed healthy because `scan` says so.** Accepted: the
  byte-for-byte rejoin test does not depend on health, and hostile-input tests
  cover the unhealthy space.
- **Polling tests cap at 5 s** (200 × 25 ms). A machine slower than that fails
  spuriously rather than hanging CI forever. Accepted as the right trade.
- **Random-input tests use a fixed seed**, so they are regression tests for a
  sampled space, not a fuzzer. Stated in the test and in SECURITY.md.
- **`bb mutate` is the check that the suite can fail at all.** 25+ mutations,
  each a real shipped or near-shipped fault. A test added without a mutation is
  a test whose ability to fail is untested.

## What coverage cannot say

Coverage of the remaining gap is concentrated in `app.clj`'s display-bound
wiring (dialogs, drag, renderer mounting) and `desktop.clj`'s calls into
`java.awt.Desktop`. These run only with a display and real user gestures. The
guard for them is not a unit test: it is `bb package`, which launches the
packaged binary, and the shots harness, which renders every screen. 100% line
coverage of this layer would mean mocking the exact APIs whose real behaviour
is the risk — a test that can only agree with itself, which is the kind this
project has resolved not to write.
