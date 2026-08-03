# Specification

What CSV Cleaver is required to do, written independently of how it does it.

This document exists so that the tests have something to be wrong against.
A test that merely restates what the code currently does will pass forever,
including through a regression; a test traceable to a numbered requirement here
can be judged. Where a test encodes a requirement, it cites the number.

Requirements are numbered `R1`, `R2`, … and are stable. Retired ones are struck
through rather than renumbered.

---

## 1. Reading a CSV file

**R1.** A *record* is one logical CSV row. A record may span several physical
lines, because a value enclosed in double quotation marks may contain line
breaks. Everything the application does is expressed in records, never lines.

**R2.** Within a quoted value, delimiters, line breaks and doubled quotation
marks are literal content and carry no structural meaning.

**R3.** A doubled quotation mark inside a quoted value (`""`) represents one
literal quotation mark and is well formed, not damage.

**R4.** The field delimiter is detected from the file. Comma, semicolon, tab and
vertical bar are recognised, scored on whether each yields a consistent field
count. Where the evidence is inconclusive the delimiter is a comma. When it is
anything other than a comma the interface says so.

**R4a.** The detected delimiter may be overridden. Because the row count, field
count and damage tally all depend on it, changing it re-reads the file rather
than adjusting the existing figures.

**R5.** The following are recognised as damage, counted per record, and never
repaired or discarded:

| Kind | Meaning |
|---|---|
| ragged | The record has a different number of fields from the first record. |
| stray quote | A quotation mark appears where the format does not allow one. |
| unterminated quote | The file ends inside a quoted value. |

**R6.** A file that is empty, or that holds only a header, is valid input. It
must not raise an error; it has nothing to split.

---

## 2. Character encoding

**R7.** The encoding is determined without asking the user, in this order:
a byte-order mark if present; otherwise UTF-8 if the opening bytes decode
cleanly as UTF-8; otherwise windows-1252, which cannot fail.

**R8.** A byte-order mark is skipped when reading and reproduced at the start of
every output file. Excel depends on it to open a UTF-8 file correctly.

**R9.** Output is written in the same encoding as the input. A file that arrived
as windows-1252 leaves as windows-1252.

**R10.** The user may override the detected encoding. The override changes how
the file is read and written; it does not change whether a byte-order mark is
reproduced, which is a fact about the input.

---

## 3. Splitting

**R11.** A record is never divided between two output files.

**R12.** Records are copied verbatim: the same bytes, the same quoting, the same
whitespace, the same line terminator. Nothing is re-quoted, normalised or
tidied. Concatenating every output file, allowing for a repeated header and a
reproduced byte-order mark, must reproduce the input exactly.

**R13.** Splitting is by row count or by file size, at the user's choice.

**R14.** When splitting by row count, the number given is honoured exactly, even
if it exceeds what Excel can open. The user is warned but not overruled: they
may not be using Excel.

**R15.** When splitting by file size, the user never states a row count, so one
is derived. A derived row count is capped at Excel's limit of 1,048,576 rows,
and a file rolls over at whichever limit is reached first. The cap may be turned
off.

**R16.** A single record larger than the target size gets a file to itself
rather than being divided or dropped.

**R17.** Where a header row is repeated into every output file, it counts
towards that file's size budget.

**R18.** Output files are named from a pattern. The index is zero-padded wide
enough for the number of files actually being written, so the results sort
correctly in a file manager at any scale.

---

## 4. Not destroying anything

**R19.** Before any byte is written, the output folder is examined for files
whose names this split could take. This check considers every name the pattern
could produce at any index, not merely the names this particular run expects, so
leftovers from an earlier and larger split of the same file are found too.

**R20.** If any such file exists, nothing is written. The user is shown the list
and offered three choices: cancel, replace them, or write to a new folder. The
safe choice is the prominent one; replacement is styled as destructive.

**R21.** Cancelling a split in progress removes the file being written and keeps
those already finished, so what remains on disk is always a whole number of
complete output files. The user is told how many survived.

### Disk space

**R38.** Before writing, the space the output will occupy is estimated and
compared with the space actually free on the destination volume. The estimate is
the size of the input plus, for every file after the first, a repeated header
where one is being repeated and a byte-order mark where one is being reproduced.

**R39.** A margin of 5% over the estimate is required. Filling a volume to its
last byte is its own kind of failure, and the estimate is an estimate.

**R40.** If the result would not fit, the split is **refused**, not merely warned
about. A full disk is worse than an unsplit file, and its consequences spread
well beyond this application. The message states both what is needed and what is
free, as sizes rather than byte counts.

**R41.** If the result would fit but leave less than a twentieth of the volume
free, the split is allowed and the user is warned.

**R42.** Where the destination cannot be examined — no folder chosen yet, or the
platform will not say — the check is skipped rather than guessed at. A false
alarm is worse than no alarm.

---

## 5. Deciding whether row one is a header

**R22.** Whether the first row names the columns is decided from evidence, not
assumed. The hints are combined, not chained, because none is conclusive alone:

| Hint | Evidence |
|---|---|
| Type disagreement | Row one holds a word where the column beneath holds numbers or dates. |
| Column consistency | The rows beneath agree with each other and disagree with row one. |
| No bare numbers | A bare number as a column name is vanishingly rare. |
| Uniqueness | Column names are distinct; data repeats. |
| No empty cells | Headers rarely have blanks. |
| Word-like cells | Letters rather than values. |
| Case style | One convention across the row: lowercase, Title Case, snake_case or camelCase. |

**R23.** The verdict has three values, not two: *header*, *data*, and *unsure*.

**R24.** When the verdict is *unsure*, the application does not guess. It
displays the first row's actual values and asks, in words that do not require
the user to know the term "header". Confident verdicts never interrupt anyone.

**R25.** The user may always override the verdict, and doing so ends the
question for that file.

---

## 6. Language and locale

**R26.** No user-facing text appears in source code. All of it lives in
`resources/i18n/<tag>.edn`.

**R27.** The interface starts in the system's language when that language is
translated, otherwise English. It may be overridden from the command line or
from the About dialog.

**R28.** A phrase absent from a translation falls back to English rather than
appearing blank or as a key.

**R29.** Numbers shown to the user follow the *interface* language, not the
machine's locale: an English window shows `1,204,338` on a Spanish computer.

**R30.** Numbers that appear in file names use plain ASCII digits with no
grouping, whatever the language, so that they sort.

**R31.** Numbers typed by the user are read in the interface language: `65.000`
means sixty-five thousand to a German user.

**R32.** Plural wording follows each language's own rule. French treats zero as
singular; Chinese and Japanese have no plural form.

### Translations supplied by the user

**R44.** A language may be added without rebuilding, by placing an EDN file in a
`languages` folder beside the settings file, or in a folder named with
`--languages`. Such a language is then selectable from `--locale` and from the
About dialog like any other.

**R45.** Everything read from that folder is untrusted and validated before use.
Nothing in such a file is ever evaluated: `clojure.edn/read-string` honours no
reader tags, unlike `clojure.core/read-string`.

**R46.** A supplied translation may only replace wording the application already
has. A file containing a key that does not exist in English is refused in its
entirety. This is what prevents a file dropped into the folder from introducing
a prompt the application would never otherwise show.

**R47.** A file is refused if it: is larger than 256 KB; is not readable as EDN;
is not a map with a `:strings` section; has no `:meta :name`; contains a phrase
longer than 2,000 characters; contains control characters or bidirectional
overrides, which can make displayed text read differently from what is stored;
has a plural entry with no `:other` form; or uses a different set of `{0}`
placeholders from the English phrase it replaces.

**R48.** A file name that is not a two- or three-letter language code is
refused, which also means nothing can address a path outside the folder.

**R49.** Where a supplied file shares a tag with a shipped translation, the two
are layered: supplied phrases win, shipped ones fill the remainder.

**R50.** If any file is refused, the application does not open its main window.
It shows an error window naming each problem, with **Quit** and **Continue in
English**. Both are offered deliberately: making a refused file fatal would let
anything dropped into that folder leave the application permanently unable to
start, which is a worse failure than the one being guarded against. The problems
are also written to standard error.

**R51.** The error window is in English and is not translated, because the
translations are the thing that is wrong.

### Quitting

**R52.** The application can be quit from within its own interface, not only by
closing the window. Quit lives in a **File menu**, with the platform's usual
accelerator, and on macOS in the system menu bar.

A menu satisfies both halves of the requirement at once: it is where people look
for the way out, and it takes two deliberate actions, so it cannot be brushed.
An earlier attempt put Quit in the About dialog. That could not be pressed by
accident and could not be found either — nobody opens an About box looking for
the way out. Being unreachable is a failure as much as being too reachable is.

**R53.** The Help menu offers the same Help and About overlays as the ⓘ and ?
buttons. Two routes to the same thing is not duplication: the buttons are the
quick one, the menu is the discoverable one.

---

## 7. Behaviour of the interface

**R33.** No option is shown before a file has been chosen, because none of them
means anything until then.

**R34.** Before splitting, the consequence is stated in a sentence: how many
files, of what size. A person who cannot judge 65,000 against 100,000 can judge
19 files against 30.

**R35.** Long work happens off the interface thread, reports progress, and can
be cancelled.

**R36.** Failures are reported in the user's language and in terms of what to do
next. The operating system's own wording is used only where the failure is not
one we recognise.

**R37.** The application follows the system's light or dark appearance by
default, and continues to follow it while running.

---

## 8. What is deliberately not done

- **No repair of malformed files.** Damage is reported and passed through
  untouched. A tool that silently corrects data is worse than one that does not,
  because the user cannot tell what changed.
- **No re-serialisation.** See R12. Parsing and re-emitting would normalise
  quoting and whitespace, changing files the user never asked to change.
- **No network access of any kind.**
- **No telemetry.**
