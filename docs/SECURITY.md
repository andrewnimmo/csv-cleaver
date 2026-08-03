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

**It opens no network connections, listens on no port, has no update mechanism,
runs no scripts, and evaluates no code from any file it reads.** A system
takeover has to come from somewhere; these are the somewheres.

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

### Deliberately out of scope

An attacker who can already write to the user's home directory or replace the
application binary has won before any of this applies. Nothing here defends
against a compromised machine, and pretending otherwise would be worse than
saying so.

---

## What runs automatically

Every push, via `.github/workflows/ci.yml`:

| Check | Command | Catches |
|---|---|---|
| Static analysis | `bb lint` | Unresolved symbols, misuse, dead code |
| Hostile input | `bb test` | Crashes and hangs on malformed, adversarial and random bytes |
| Path traversal | `bb test` | Output names escaping the chosen folder |
| No code execution | `bb test` | Reader-tag payloads in translation files |
| Known vulnerabilities | `bb audit` | CVEs in the resolved dependency tree |
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
