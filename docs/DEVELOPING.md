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
                    │  app        wiring, threads,     │  ← the only namespace
                    │             dialogs, -main       │    that touches JavaFX
                    └───────────────┬──────────────────┘      objects and IO
                       effects as data │ events as data
                    ┌───────────────┴──────────────────┐
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

### Namespace by namespace

| Namespace | Responsibility |
|---|---|
| `csv` | Reads CSV as **records**, not lines. Quote-aware scanner over a hand-rolled character buffer. The heart of the correctness claim. |
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
| `app` | Performs effects, owns the atom, drives threads, starts the window. |

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

**JavaFX keeps the JVM alive.** Requiring `cljfx.api` starts the toolkit, and its
thread is not a daemon. A script that loads it must call `System/exit`.
