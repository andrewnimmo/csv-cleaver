# Design decisions

Why the application is shaped the way it is, and what was rejected. Read
[SPECIFICATION.md](SPECIFICATION.md) for what it must do; this is why.

---

## 1. Records, not lines

**Decision.** The splitter works in whole CSV records, scanning quote state as
it goes, and copies each record's text verbatim.

**Rejected.** Parsing each record into fields and writing them back out. This
validates the file as a side effect, which is attractive — but it re-serialises,
so quoting style, escaping and stray whitespace change. A user who asked for a
file to be divided did not ask for it to be rewritten, and a diff they cannot
explain destroys trust in the tool.

**Rejected.** Counting lines, as the previous version did. Silently corrupts any
file with a line break inside a quoted value.

**Consequence.** Output is byte-identical to the input, which is testable, and
is tested against every file in the sample corpus.

---

## 2. Never overwrite, and check by pattern

**Decision.** Before writing, the folder is scanned for every name the pattern
could produce at any index — not the exact list this run expects.

**Why not the exact list.** A split by size does not know its own file count
until it has finished, so there is no exact list to compare. The pattern also
catches the nastier case: files 20 to 30 left over from an earlier, larger split
of the same file, which would otherwise sit in the output folder looking exactly
like part of the new results.

**Decision.** The dialog leads with "Use a new folder" and styles "Replace them"
as destructive, and states plainly that nothing has been written yet — which is
the question a worried user actually has.

---

## 3. Effects as data

**Decision.** `state/handle` returns `{:state … :effects […]}` and performs
nothing. `app` performs the effects.

**Why.** It makes the entire behaviour of the application testable by calling a
function and reading a map. No display, no toolkit, no waiting, no flakiness.
Around 98% of the state namespace is covered by tests that would run on a
machine with no graphics stack at all.

**Cost.** One more indirection to follow when reading the code, and an effect
vocabulary to keep in step between two namespaces.

---

## 4. Views as pure data, plus materialisation tests

**Decision.** Every screen is a function from state to a cljfx description map.
Tests assert on the maps. A second, smaller layer of tests builds real widgets.

**Why both.** Map tests are fast and exhaustive but structurally blind: a
misspelled property name, or a `when` leaving `nil` in a children vector, looks
perfectly fine as data and throws at render time. That exact bug was caught by
the materialisation tests during development, on a screen whose map-based tests
were passing.

---

## 5. Three-way header verdict

**Decision.** Whether row one is a header is *header*, *data*, or **unsure**.
When unsure, the application shows the row's actual values and asks.

**Rejected.** A two-way guess. The original single-signal heuristic — words above
numbers — is blind to a file of nothing but words, and guessed silently. Getting
it wrong puts a row of column names into the middle of someone's data, or drops
a genuine row.

**Why show the row.** A user who has never heard the word "header" cannot answer
an abstract question about one, but recognises `id · name · city` as labels
instantly. Ask about what is on the screen, not about a concept.

**Cost.** Seven signals to maintain and a scoring function to calibrate. The
weights are documented and each signal is inspectable, so a wrong answer can be
diagnosed rather than guessed at.

---

## 6. Refuse rather than warn on disk space

**Decision.** If the output would not fit, the Split button is disabled and the
reason given. Not a warning that can be clicked through.

**Why.** Every other failure in this application is contained: a bad number, a
name clash, a damaged row. Filling a volume is not — it breaks other
applications, and possibly the operating system, long after this one has
finished. The asymmetry justifies the paternalism.

**Rejected.** Checking only at write time. By then the damage is partly done.

---

## 7. Never override a number the user typed

**Decision.** Splitting by size caps rows at Excel's limit; splitting by row
count does not.

**Why the asymmetry.** In size mode the user never chose a row count — one was
derived — so constraining a derived value is correcting our own arithmetic. In
row mode they typed a number, and they may not be using Excel at all. They are
warned and obeyed.

---

## 8. Translations in EDN, never in code

**Decision.** No user-facing string appears in a `.clj` file. Problems are
reported as keywords or `{:key … :args […]}` maps, rendered at the edge.

**Why.** A translator can correct a phrase without opening a Clojure file, and
the completeness of every language is testable — missing keys, invented keys,
absent plural forms and dropped `{0}` placeholders are all caught by tests.

**Rejected.** `java.text.MessageFormat` for interpolation. It gives the
apostrophe a special meaning, and French is full of apostrophes; every other
string would need doubling and a translator would eventually forget.

---

## 9. Numbers follow the window, not the machine

**Decision.** `i18n/number` for anything shown, `i18n/plain-number` for anything
in a file name, and `clojure.core/format` for neither.

**Why.** `format` follows the JVM default locale. On the development machine —
set to Spanish — an English window rendered 1,204,338 as `1.204.338`. It would
have shipped. Making the locale an explicit argument turns a silent
environmental dependency into a visible choice.

---

## 10. One overlay mechanism for every dialog

**Decision.** Name clash, About and Help all use the same in-window overlay,
rather than native modal windows or a menu bar.

**Why.** One mechanism to build, style, test and translate. It behaves
identically on all three platforms, and it is already covered by tests. A menu
bar would have been more conventional on macOS and a second thing to maintain
for a single-window utility with one action.

---

## 11. jpackage, not GraalVM

**Decision.** Ship a bundled Java runtime per platform.

**Why.** GraalVM native image requires all code paths to be known at compile
time. Clojure compiles dynamically and JavaFX leans heavily on reflection and
JNI; both fight it. jpackage produces a real installer, the application starts
in about a second, and users see no difference.

**Cost.** Installers around 60 MB, and one build machine per platform, because
jpackage does not cross-compile. Hence four CI runners.

---

## 12. Excluding javafx-web and javafx-media

**Decision.** cljfx declares both as hard dependencies; both are excluded.

**Why.** javafx-web alone carries a WebKit native of roughly 180 MB, which would
land in every installer, for a window with no web view in it. Verified that
cljfx loads and every screen renders without them.
