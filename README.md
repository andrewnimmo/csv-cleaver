# CSV Cleaver

Splits large CSV files into smaller ones that Excel can open, without damaging
them on the way through.

A desktop application for macOS, Windows and Linux, written in Clojure with a
[cljfx](https://github.com/cljfx/cljfx) interface. Available in English, Spanish,
French, German, Italian, Portuguese, Chinese and Japanese.

---

## Which document do you want?

| If you are… | Read |
|---|---|
| Someone who needs to split a file | **[docs/USER-GUIDE.md](docs/USER-GUIDE.md)** |
| A developer changing the code | **[docs/DEVELOPING.md](docs/DEVELOPING.md)** |
| Deciding whether a behaviour is a bug | **[docs/SPECIFICATION.md](docs/SPECIFICATION.md)** |
| Wondering why something was built this way | **[docs/DECISIONS.md](docs/DECISIONS.md)** |
| Driving it from a script | **[docs/API.md](docs/API.md)** |
| Assessing the security of any of it | **[docs/SECURITY.md](docs/SECURITY.md)** |
| Asking who — and what — wrote this | **[docs/PROVENANCE.md](docs/PROVENANCE.md)** |
| Checking what is verified, and how | **[docs/VERIFICATION.md](docs/VERIFICATION.md)** |
| Turning on signing and notarisation | **[docs/SIGNING.md](docs/SIGNING.md)** |

---

## What it does differently

Most tools that split a CSV file count lines. That is wrong, and quietly so. A
CSV value wrapped in quotation marks may contain line breaks, so one row can
occupy several lines:

```
id,notes
1,"first line
second line"
```

Counting lines cuts that row in half and damages both pieces, without
complaint. CSV Cleaver works in whole records, so this cannot happen.

It also:

- **Never overwrites anything without asking.** The output folder is checked
  before a single byte is written, and if any file would be replaced you are
  shown the list and asked.
- **Keeps the encoding.** UTF-8, UTF-8 with a byte-order mark, or the
  windows-1252 that Excel writes — whatever went in comes out, mark included,
  so results open correctly rather than full of mojibake.
- **Copies damaged rows untouched.** A malformed row is reported, never
  repaired and never dropped.
- **Stays inside Excel's limits.** When splitting by size — where you choose
  megabytes and never a row count — files also stop at 1,048,576 rows, because
  a file Excel cannot open would defeat the point.
- **Can be scripted.** An optional local HTTP service, with a Swagger page, puts
  all of the above behind an API. Off unless you ask for it — see
  [docs/API.md](docs/API.md).

---

## Installing

Download an installer from the
[releases page](https://github.com/andrewnimmo/csv-cleaver/releases). Each one
contains its own Java runtime; nothing else is needed.

The installers are **not code signed**, so each system will warn you the first
time. [docs/USER-GUIDE.md](docs/USER-GUIDE.md) explains what to click.

---

## Running from source

Needs JDK 25 and the [Clojure CLI](https://clojure.org/guides/install_clojure).
[Babashka](https://babashka.org) is recommended but optional.

```bash
bb run
```

Or without Babashka:

```bash
clojure -M:run
```

Command line options:

```bash
bb run -- --locale ja --theme dark
```

| Option | Meaning |
|---|---|
| `-l, --locale TAG` | Interface language: `en`, `es`, `fr`, `de`, `it`, `pt`, `zh`, `ja`. Defaults to the system language, then English. |
| `-t, --theme NAME` | `auto` (follows the system, the default), `light` or `dark`. |
| `-h, --help` | Show all options. |
| `-V, --version` | Show the version. |
| `--api` | Start the local HTTP service. Off unless given — see [docs/API.md](docs/API.md). |
| `--api-port PORT` | Port for it. Default `8377`. |
| `--api-input MODE` | What it accepts: `none`, `path`, `upload` or `both`. Default `path`. |
| `--api-token TOKEN` | Use a token you already have. One is generated and printed otherwise. |
| `--headless` | No window. Only useful with `--api`. |

### Scripting it

```bash
bb run -- --api --headless
```

prints a token and a link to a Swagger page at
`http://127.0.0.1:8377/api-docs/index.html`, from which every endpoint can be
tried. It listens on this machine only and answers nothing without the token.
[docs/API.md](docs/API.md) has the endpoints;
[docs/SECURITY.md](docs/SECURITY.md#the-optional-http-service) has what it
costs — read the part about `--api-input path` before using it.

---

## Common tasks

```bash
bb tasks
```

| Task | What it does |
|---|---|
| `bb test` | The whole test suite |
| `bb coverage` | Tests plus a coverage report in `target/coverage` |
| `bb mutate` | Break the code on purpose and check the tests notice |
| `bb lint` | clj-kondo |
| `bb format` | Reformat with cljfmt |
| `bb check` | Everything CI runs |
| `bb shots` | Render every screen to `target/shots` as PNG, both themes |
| `bb icons` | Regenerate every platform icon from `dev/icons.clj` |
| `bb uber` | Build the standalone jar |
| `bb package` | Build a native installer for this machine |
| `bb brand` | Change the name, colour or icons |
| `bb languages` | List languages and which have been reviewed |

---

## Provenance

This codebase was written predominantly by Claude (Anthropic) under the
direction of Andrew David Nimmo as Technical Project Manager — who set the
requirements, made the decisions, and acceptance-tested every build.
[docs/PROVENANCE.md](docs/PROVENANCE.md) says precisely what that means,
including the copyright position for AI-generated code.

Made with 🤖 in Barcelona.

---

## Licence

Copyright © 2026 Andrew David Nimmo.

Apache License 2.0 — see [LICENSE](LICENSE).

The installers bundle a Java runtime under the GPL v2 with Classpath Exception.
See [NOTICE](NOTICE) and [THIRD-PARTY.md](THIRD-PARTY.md).
