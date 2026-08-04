# Security

What this application is exposed to, what is done about it, and — importantly —
what none of that can promise.

## The honest headline

**No process certifies software free of security defects, and this document
does not claim to.** Certification schemes such as Common Criteria certify a
*process* and a *configuration*, at considerable expense, and still do not
guarantee the absence of flaws. What is achievable is: a stated threat model, a
small attack surface, automated checks that run on every change, and a way for
someone to report what they find. Anything stronger would be a marketing claim.

The realistic goal is **reducing the likelihood and the blast radius of a
defect**, and being able to show the work.

---

## What this application actually does

It matters that the surface is small:

- Reads a file the user picked, and writes files into a folder the user picked.
- Reads an EDN settings file and, optionally, EDN translation files it wrote or
  the user supplied.
- Starts one external program — `open`, `explorer` or `xdg-open` — to show a
  folder.
- Ships a bundled Java runtime inside each installer.
- **Only if started with `--api`:** listens on `127.0.0.1`, behind a token.
  Covered in its own section below.

**It makes no outbound network connections, has no update mechanism, runs no
scripts, and evaluates no code from any file it reads. It listens on no port
unless `--api` is given on the command line.** A system takeover has to come
from somewhere; these are the somewheres.

---

## Threat model

| Where the input comes from | What an attacker controls | What could go wrong | What prevents it |
|---|---|---|---|
| The CSV file | Every byte, and the file name | Crash, hang, unbounded memory | Records stream one at a time; nothing accumulates. Hostile-input tests on every push. |
| The output name pattern | A string the user types | Writing outside the chosen folder | `naming/template-problem` refuses `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`. Tested against traversal strings. |
| The input file's name | Whoever named the file | Path traversal into output names | Output names derive from `File.getName`, which carries no directory part. Tested. |
| Translation files | Anyone who can write to the languages folder | Code execution; misleading text | `clojure.edn/read-string` evaluates nothing and honours no reader tags. Files may only *replace* existing phrases, never introduce keys. Control characters and bidirectional overrides refused. See R44–R51. |
| The settings file | Anyone who can write to it | Malformed data crashing startup | Parsed defensively; anything unreadable means defaults apply. |
| `ProcessBuilder` for revealing a folder | The folder path | Command injection | No shell is involved. The program name is a constant and the path is a separate argument, so it is never parsed as a command. |
| Bundled JRE and libraries | Upstream | Known CVEs accumulating | `bb audit` against the NVD; `bb outdated`; both in CI. |
| The HTTP service, when `--api` is given | Any local process that can reach `127.0.0.1` | Reading arbitrary files; filling the disk; exhausting memory | Off unless asked for. Loopback binding only, not configurable. Token on every request. `--api-input` bounds what may be given to it. See below. |

### Deliberately out of scope

An attacker who can already write to the user's home directory or replace the
application binary has won before any of this applies. Nothing here defends
against a compromised machine, and pretending otherwise would be worse than
saying so.

---

## The optional HTTP service

Everything in this section applies **only** when the application is started with
`--api`. Without it, no socket is opened and none of this is reachable. See
[API.md](API.md) for what it does; this is what it costs.

### Four things that are not configurable

1. **Off by default.** A desktop application that quietly listens on a port is
   not what anyone installed.
2. **Loopback only.** It binds `127.0.0.1` and there is no option to change
   that. Nothing outside this machine can reach it, whatever the firewall says.
3. **A token on every request.** Generated with `SecureRandom` and printed once
   at startup. Compared in constant time. The only two things readable without
   it are the OpenAPI document and the Swagger page that renders it — neither
   discloses anything about the machine.
4. **No shell, ever.** The service starts no external program. Nothing a caller
   sends is interpolated into a command.

### The part to be clear-eyed about

With `--api-input path` — the default — a caller holding the token can ask the
application to **read any file the user running it can read**, by naming its
path. That is the feature: it is how you split a 40 GB file without copying it.
It also means:

> **The token is the entire security boundary.** Treat it like a password. Any
> process on the machine that can read it can read the user's files through this
> service.

The startup banner says so, in those words, whenever the mode is `path`.

`--api-input` exists to narrow that:

| Mode | A caller may | Worth choosing when |
|---|---|---|
| `none` | Neither name a path nor upload | You want the service for `/health` and `/capabilities` only |
| `path` | Name a local path | The files are large and you trust every process on the machine |
| `upload` | Send bytes; results come back as a zip | You would rather the service could not read paths at all |
| `both` | Either | Convenience over caution |

A caller can ask which is in force at `GET /api/capabilities` rather than
discovering it by being refused.

### Bounds

| Concern | What bounds it |
|---|---|
| Memory from an upload | 256 MiB per request, enforced by the server before the body is accepted. An uploaded body is held in memory before it reaches disk, which is why the limit is modest. Large files belong in `path` mode, which copies nothing. |
| Disk from a split | The same pre-flight the window uses: a split that would not fit is refused, not started and abandoned half-way. |
| Temporary files | An upload and its results live in one folder under the system temporary directory, removed when the job is forgotten (30 minutes after it finishes) or when the service stops. |
| Unbounded job accumulation | Finished jobs are dropped after 30 minutes. |
| Overwriting | Exactly as in the window: nothing already on disk is replaced. A name that is already taken stops the job. |
| Writing outside the chosen folder | The same `naming/template-problem` check the window uses. An uploaded file's name is passed through `File.getName`, so a part announcing itself as `../../.ssh/config` lands in the temporary folder like any other. |

### What is not defended against

- **A malicious process on the same machine that can read the token.** It has
  the same access the user does. This is stated rather than mitigated.
- **Denial of service by a local caller.** Starting a hundred splits will make
  the machine unhappy. The service is for a script on the user's own desktop,
  not a shared host.
- **Anything at all if the machine is already compromised**, as above.

---

## What runs automatically

Every push, via `.github/workflows/ci.yml`:

| Check | Command | Catches |
|---|---|---|
| Static analysis | `bb lint` | Unresolved symbols, misuse, dead code |
| Hostile input | `bb test` | Crashes and hangs on malformed, adversarial and random bytes |
| Path traversal | `bb test` | Output names escaping the chosen folder |
| No code execution | `bb test` | Reader-tag payloads in translation files |
| Service authorisation | `bb test` | A route answering without the token, or with a near-miss token |
| Service input modes | `bb test` | A path accepted in `upload` mode, or an upload in `path` mode |
| Known vulnerabilities | `bb audit` | CVEs in the resolved dependency tree — needs `NVD_API_KEY` in the environment, fails loudly without one, and refuses to report a pass unless it actually scanned the dependencies |
| Dependency drift | `bb outdated` | Libraries falling behind |

`test/csv_cleaver/hostile_input_test.clj` is the concrete part: malformed CSV,
unbalanced quotes, five thousand columns, a two-hundred-thousand-character
field, control characters, bidirectional overrides, and forty pseudo-random byte
files from a fixed seed so a failure reproduces exactly.

---

## What is not automated, and should be

Being straight about the gaps is more useful than a checklist that implies they
are covered:

1. **Real fuzzing.** Forty random files is a smoke test, not a fuzzer. A proper
   run means AFL-style coverage-guided fuzzing over the record scanner for hours,
   not milliseconds.
2. **Signing and notarisation.** Unsigned installers are the largest practical
   risk to a *user*: they train people to click through warnings, and they offer
   no integrity guarantee. This is a purchasing decision, not a coding one.
3. **Reproducible builds.** Two builds of the same commit should be
   byte-identical, so a published binary can be shown to match its source.
4. **A published SBOM** with each release, so a downstream user can answer "does
   this contain the library in today's advisory?" without asking.
5. **Runtime currency.** The bundled JRE is frozen at build time. A JDK security
   release means rebuilding and re-releasing; nothing prompts that automatically.
6. **Independent review.** Everything above was written by the same party that
   wrote the code, which is the weakest form of assurance there is.
7. **Fuzzing the HTTP surface.** The routes are tested with well-formed requests
   and with the refusals that matter. Nobody has thrown malformed multipart
   bodies, absurd header counts or slow-loris connections at it. If the service
   is to be used seriously, that gap should be closed first.

---

### What the audit has found

Kept here rather than only in a commit message, because "the audit is clean" is
worth nothing without a record of what it has caught.

| When | Finding | Done |
|---|---|---|
| 2026-08-04 | `jackson-databind` 2.21.1, pulled in transitively by reitit, carried seven CVEs including two at CVSS 8.1 (polymorphic type validation) | Pinned to 2.21.5 in `deps.edn`. Remove the pin once reitit resolves it itself. |

Two things about that entry are worth stating plainly. The dependency arrived
with the HTTP service, so the service is what introduced the exposure. And the
audit had never once run successfully before that day — it was failing on a
missing argument, then on a missing key, and then silently scanning nothing at
all while reporting a clean result. `bb audit` now counts what it scanned and
fails if the answer is implausible.

---

## Reporting something

Open a GitHub issue for anything not sensitive. For something that should not be
public, contact the maintainer directly and allow time for a fix before
disclosure.

---

## If you need real assurance

For an application handling data that genuinely warrants it:

- Commission an **independent penetration test and code review**. That is the
  single highest-value step, and nothing in this file substitutes for it.
- Require **signed, notarised, reproducible** builds with a published SBOM.
- Run the application under least privilege, with no access beyond the folders
  it needs.
- Treat the bundled runtime as a dependency with its own patch cycle.
