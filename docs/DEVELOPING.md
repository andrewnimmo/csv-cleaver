# Developing CSV Cleaver

For anyone changing the code. Start with
[SPECIFICATION.md](SPECIFICATION.md) for what the application is required to do
and [DECISIONS.md](DECISIONS.md) for why it is shaped this way.

---

## Getting set up

| Tool | Version | Why |
|---|---|---|
| JDK | 25 (LTS) | Runtime, and `jpackage` for installers |
| Clojure CLI | latest | Everything |
| Babashka | latest | `bb` tasks; optional but assumed below |

```bash
git clone https://github.com/andrewnimmo/csv-cleaver
cd csv-cleaver
bb test
bb run
```

---

## How the code is arranged

The organising principle is that **nothing interesting is allowed to depend on
JavaFX**. The engine knows nothing about a window, the state machine knows
nothing about widgets, and the view is a pure function producing data. Exactly
one namespace touches live objects, threads and the clock.

```
                    ┌──────────────────────────────────┐
                    │  main       -main, options,      │  ← loadable with no
                    │             starts one or both   │    display
                    └───────┬──────────────────┬───────┘
                            │                  │
        ┌───────────────────┴──────┐   ┌───────┴──────────────────────┐
        │  app   wiring, threads,  │   │  api.server  routes, schemas │
        │        dialogs           │   │  api.jobs    async jobs      │
        │   ← the only namespace   │   │  api.zip     archives        │
        │     touching JavaFX      │   └───────┬──────────────────────┘
        └───────────────┬──────────┘           │
           effects as data │ events as data    │
                    ┌───────┴──────────────────┴───────┐
                    │  state      pure transitions     │
                    │  view       pure descriptions    │
                    └───────────────┬──────────────────┘
                                    │
        ┌───────────┬───────────┬───┴───────┬───────────┬───────────┐
        │  scan     │  split    │  csv      │  encoding │  naming   │
        │  survey   │  engine   │  records  │  charset  │  filenames│
        └───────────┴───────────┴───────────┴───────────┴───────────┘
             supporting: files · format · i18n · branding · desktop · prefs · cli
```

The window and the service are two front ends over the same engine. Neither
knows about the other, and `split/execute!` cannot tell which one called it —
which is what makes "nothing already on disk is replaced" one guarantee rather
than two implementations of it.

### Namespace by namespace

| Namespace | Responsibility |
|---|---|
| `csv` | Reads CSV as **records**, not lines. Quote-aware scanner over a hand-rolled character buffer. The heart of the correctness claim, and hand-written rather than built on `clojure.data.csv` — [DECISIONS §17](DECISIONS.md#17-a-hand-written-csv-reader-not-clojuredatacsv) says why, and what it costs. |
| `encoding` | Byte-order marks and charset detection. |
| `files` | Opening readers and writers that preserve encoding and byte-order marks. |
| `naming` | Output file names, index padding, and the pattern used to find collisions. |
| `scan` | Surveys a chosen file once: rows, delimiter, damage, header evidence. |
| `split` | `plan` (what would happen, touching nothing) and `execute!` (doing it). |
| `format` | Numbers and outcomes into sentences. Locale-aware, no English literals. |
| `i18n` | Translation bundles, plural rules, locale-aware number formatting. |
| `naming`, `branding`, `prefs`, `desktop`, `cli` | Supporting concerns, each small. |
| `state` | Every state change as one pure function returning `{:state … :effects […]}`. |
| `view` | Every screen as a pure function returning cljfx description maps. |
| `app` | Performs effects, owns the atom, drives threads, opens the window. |
| `main` | `-main`. Parses options, starts the service and/or the window. **Must never require cljfx** — see below. |
| `api.server` | Routes, request schemas, the token check, the OpenAPI description. |
| `api.jobs` | Splits in progress: start, poll, cancel, expire. |
| `api.zip` | A finished job's output as one streamed archive. |

### Why state and effects are data

`state/handle` takes a state and an event and returns the next state plus a
vector of effects such as `[:scan file]` or `[:split opts]`. It never performs
them. That is what allows the whole behaviour of the application to be tested by
calling a function and reading the map that comes back — no display, no toolkit,
no waiting.

`app/perform!` is the dull layer that carries them out. Each long-running effect
has its body lifted into a *worker* (`scan-worker`, `split-worker`,
`collision-worker`) that takes a `dispatch` function, so a test can pass one that
records into a vector and check exactly what the application would have been
told.

---

### The vulnerability audit needs a key

`bb audit` will not run without an NVD API key — nvd-clojure requires one, and
refusing is the right failure: an audit that quietly checked nothing would be
worse than no audit. Request one free at
[nvd.nist.gov](https://nvd.nist.gov/developers/request-an-api-key); it arrives
by email in a minute or two.

Then either put it in the environment:

```bash
echo 'export NVD_API_KEY=your-key-here' >> ~/.zshenv
```

or in a file beside this one, which `.gitignore` already covers:

```bash
echo your-key-here > .nvd-api-key
```

`~/.zshenv` rather than `~/.zshrc` because non-interactive shells — which is
what tooling runs in — read the former and not the latter.

For CI, add it as a repository secret named `NVD_API_KEY`;
`.github/workflows/ci.yml` already passes it through.

---

## Testing

```bash
bb test          # everything
bb test:fast     # skip the tests needing a JavaFX toolkit
bb coverage      # report in target/coverage
```

Three layers, each catching what the others cannot:

1. **Pure tests** over the engine, the state machine and the view functions.
   The view returns maps, so assertions read data rather than driving widgets.
2. **The sample corpus** in `test/resources/samples`: eighteen real CSV files,
   each exhibiting one problem or none, described in `manifest.edn`. Every file
   is checked against the manifest, every file must appear in the manifest, and
   every file must split and rejoin byte for byte. A failure here can be
   understood by opening the file.
3. **Materialisation tests** (`fx_smoke_test`) that build real JavaFX widgets
   from every screen in every language. These catch what map-based tests cannot:
   a misspelled property, or a `when` leaving a `nil` in a children vector.
   Tagged `^:fx`; on a headless machine run them under `xvfb-run`.

### Writing a test that is worth having

Cite a requirement from [SPECIFICATION.md](SPECIFICATION.md). A test that only
restates what the code currently does will pass through a regression. When a
test and the code disagree, the default assumption is that the **code** is
wrong; changing the assertion needs a reason you could defend out loud.

### Coverage

Around 94% of forms. What is not covered is the native file and directory
choosers, the drag-and-drop handlers and `-main` — all of which need a human or
a robot. These are deliberate exclusions, not oversights.

---

## Seeing the interface without running it

```bash
bb shots
```

Renders every screen in both themes to `target/shots` as PNG, by snapshotting
the real scene graph with the real stylesheet. Useful for reviewing a design
change in a pull request, and it works on a headless CI machine. The CI workflow
uploads these as an artifact on every push.

---

## Adding or correcting a language

Translations live in `resources/i18n/<tag>.edn` and **never in code**.

To correct a phrase: edit the file, rebuild. That is the whole procedure.

To add a language:

1. Copy `resources/i18n/en.edn` to `resources/i18n/<tag>.edn`.
2. Translate the values. Leave the keys and the `{0}`, `{1}` placeholders alone.
3. Add the tag to `i18n/supported`.
4. If the language pluralises differently from English, add a case to
   `i18n/plural-category`.
5. `bb test` — the i18n tests check that no key is missing, none is invented,
   every plural form is present, and every placeholder survived.

Mark a file `:reviewed? true` in its `:meta` only once a native speaker has been
over it. `bb languages` lists the current state.

### Adding a language without rebuilding

A translation can also be dropped in at run time, which is how someone who is
not a developer can add one:

| Platform | Folder |
|---|---|
| macOS | `~/Library/Application Support/CSV Cleaver/languages/` |
| Windows | `%APPDATA%\CSV Cleaver\languages\` |
| Linux | `~/.config/csv-cleaver/languages/` |

Put `it.edn` there, restart, and `--locale it` works. Or point somewhere else
with `--languages DIR`, which is the convenient way to test one.

**Everything in that folder is untrusted**, and `i18n/validate-bundle` decides
whether it may be shown. The threat is not code execution — `clojure.edn`
evaluates nothing and honours no reader tags — it is **misleading text**: a file
that reworks a phrase into something the application would never say. Hence the
central rule, R46: a supplied file may only *replace* wording that already
exists, never introduce a key. A file that invents one is refused whole.

Also refused: oversized files, oversized phrases, control characters,
bidirectional overrides (which can make a label read in the opposite order to
the characters stored), mismatched `{0}` placeholders, plural entries missing
`:other`, and file names that are not language codes.

When anything is refused the main window does not open. An English error window
lists the problems and offers Quit or Continue in English — both, because making
a bad file fatal would let one leave the application permanently unopenable.

---

## Rebranding

Everything nameable lives in `resources/branding.edn` — name, tagline, version,
bundle identifier, vendor, accent colour, icon paths. The same file feeds the
window, the About box, and the installer.

```bash
bb brand --name "Acme Splitter" --accent "#c2410c"
bb uber
```

For finer control than an accent colour, add `resources/brand.css`. It loads
after the application's own stylesheet, so any rule in it wins. Override
AtlantaFX colour names such as `-color-accent-emphasis`.

---

## Releasing

```bash
git tag v2.1.0
git push origin v2.1.0
```

`.github/workflows/release.yml` builds on four runners — macOS Apple Silicon,
macOS Intel, Windows x64, Linux x64 — because `jpackage` only builds for the
machine it runs on, and publishes a release with all the installers.

Nothing is signed. The workflow has the signing steps stubbed so that adding
secrets activates them rather than requiring an edit.

To build locally for this machine only:

```bash
bb package
```

---

## Things worth knowing before you change something

**`some->` and nil.** `ready?` carries a comment about this because it bit once:
threading through a key whose *absence* is the success condition short-circuits
on exactly the value you were testing for.

**Locale.** `clojure.core/format` follows the JVM default locale. Never use it
for anything the user sees. Use `i18n/number`, `i18n/decimal`, or
`i18n/plain-number` for file names.

**`trn` does not interpolate the count.** It uses the number to choose between
singular and plural; the caller passes what should appear. Otherwise an
unformatted `1203` reaches the screen where `1,203` was intended.

**`nil` in a children vector.** cljfx has no lifecycle for `nil`, so a `when`
that does not fire will throw at render time. Use the `compact` helper. Only the
materialisation tests catch this.

**`:style-class` replaces, it does not add.** Two ways this bites:

*On the scene root.* JavaFX puts `root` in the root node's style-class list, and
every AtlantaFX colour is defined on `.root`. cljfx calls `setAll` whenever the
computed list changes, so a list that omits `root` destroys it — and with it
every colour lookup in the window, permanently. `content` therefore lists `root`
explicitly, and a test asserts it in every state.

*On a control.* `:style-class ["chip"]` on a Label drops `label`, so the label
loses the theme's default text fill and resolves to black — invisible in dark
mode. Either keep the control's own class in the list (`["label" "chip"]`) or
set `-fx-text-fill` in the rule. Both are used here; the bold headings do both.

**The bundled runtime is not the JDK you test on.** `jpackage` runs `jlink`,
which includes only modules something declares a dependency on. Nothing declares
one on `jdk.localedata`, so it was dropped — and the shipped application
formatted every language's numbers as English while the words around them were
translated. Every test in this suite runs on a full JDK, where that module is
always present, so nothing here could ever have caught it. The packaging scripts
now name their module set in full, the build fails if the module is absent from
the image it just made, and it launches the packaged binary before wrapping it
in an installer. When you change anything about packaging, check the built
artefact, not the source tree.

**Tests here cover functions; the bugs that got out lived between them.** Every
defect found by using the built application rather than by the suite has been
the same shape: two functions, each correct and each tested, with an unstated
assumption about the transition between them. R80 is the clearest case —
numbers are written in the interface language and read back in the interface
language, both tested, and nothing said what happens at the moment that language
changes. The result was `65,000` becoming sixty-five in a German window, which
no test asked about because no requirement mentioned it.

When you add anything that holds a *derived* value rather than recomputing it —
text formatted for a language, a path built from a setting, a label chosen by a
mode — write the invariant, not another example: *whatever changes underneath
it, this still means what it meant.* `state_test.clj` has two of these worth
copying. `every-remembered-setting-is-classified` fails when a setting is added
to `prefs/remembered` without anyone deciding whether a language change affects
it, which is the decision that went unmade the first time.

**The record scanner is the one place a bug corrupts data rather than throwing.**
`csv.clj` is a hand-written RFC 4180 state machine — deliberately, for the
reasons in [DECISIONS §17](DECISIONS.md#17-a-hand-written-csv-reader-not-clojuredatacsv),
but a state machine over quote state, doubled quotes, three line terminators and
end-of-file in every position is not a thing to adjust casually. Read
`csv_test.clj` and `hostile_input_test.clj` before touching it, and add to them
before changing it: a break there passes silently through every other layer and
comes out as damaged output files.

**JavaFX keeps the JVM alive.** Requiring `cljfx.api` starts the toolkit, and its
thread is not a daemon. A script that loads it must call `System/exit`.

**And that is why `main` may not require `app`.** Loading cljfx starts the
toolkit *as a side effect of loading it*, so any namespace mentioning cljfx in
its `:require` cannot be loaded on a machine with no display. `--headless` would
fail during class loading, which is the hardest kind of failure to explain to
someone. `main` therefore reaches the window through `requiring-resolve`, at the
moment a window is going to open, and `main_test.clj` reads the source to check
that this is still true. The same reasoning put the cljfx renderer behind a
`delay` in `app`.
