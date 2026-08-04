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

**R20a.** Nothing the application says about those files may claim to know what
they are. It observed one thing — that their names clash — and it says only
that. They might be output from an earlier split; they might equally be the
user's own files in a folder chosen by mistake.

**R20b.** "Replace them" replaces all of them. Files that were listed but that
this run does not itself write are cleared too, and the user is told how many.
The alternative — writing three files and silently leaving five — produces a
folder that is part new and part old with nothing to distinguish them.

**R20c.** Clearing means **moving to the system Trash or Recycle Bin**, never
deleting. These files were identified by name alone. Where the platform offers
no Trash, nothing is removed at all and the result says so: falling back to
deletion would quietly turn a recoverable act into an irreversible one.

**R20d.** Files are cleared only after the split has succeeded. A failure or a
cancellation never costs anyone their existing files.

**R20e.** The clash dialog names the destination folder in full, states what
replacing will do to files not listed, and gives the accent and the keyboard
default to the safe choice. Pressing Return must never remove anything.

**R20f.** A split that would produce a single file is refused, with an
explanation. Copying the input under a new name is not a split, and proceeding
would also raise a name-clash dialog about files the run will never write.

**R20g.** Results go by default into a **new folder named after the input
file** — `customers-2024 split` — rather than into the input's own folder. A
folder the application created for one run cannot be confused with anything
else, so the clash dialog becomes exceptional rather than routine, and the
dangerous path is rarely reached at all. If the user chooses a destination, that
location is remembered and used as the home for the next file's folder.

**R20h.** Before Split is pressed, the destination line says whether the folder
already exists and how many CSV files it holds. A folder chosen by mistake is
then visible while noticing it still costs nothing.

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

**R80.** Changing the interface language rewrites the numbers already typed into
the row-count and size boxes as the new language writes them. Those boxes hold
text and R31 reads that text back in the current language, so text left as it
was would change meaning: `65,000` typed in English and read as German is
sixty-five, and the split would produce a thousand times as many files as the
user asked for. Text that is not a number the previous language could read is
left exactly as it is.

**R81.** Settings kept between sessions never store a number as text formatted
for a language. The row count and size are stored as numbers, and written into
the boxes at startup as whatever language the window opens in writes them. A
settings file saying `100,000` does not say which language wrote it, and reading
it as the wrong one turns a hundred thousand into a hundred. Text stored by
earlier versions is ignored rather than guessed at: forgetting a row count once
is a smaller harm than silently dividing it by a thousand.

**R83.** The bundled runtime in every installer contains locale data for every
language the application offers. Number formatting comes from the Java runtime,
not from the translation files, so a runtime without it renders every language's
numbers as English while the words around them are translated.

**R82.** A size is read in each language's own words for its units as well as in
the ASCII forms — a French user may type `25 Mo`, which is what the application
itself shows them, as well as `25 MB`. Changing the language keeps the unit the
user chose and translates it: `1.5 GB` becomes `1,5 Go`, not `1,536 Mo`.

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

**R33a.** Once a file is chosen, the first rows are shown as they were parsed.
This is what makes the separator and the header decision checkable rather than
merely asserted: seeing `id · name · city` above `1 · Ann · Leeds` demonstrates
both that the columns were found correctly and which row is which.

**R33b.** The detected separator is always named, including when it is a comma.
Reporting it only when unusual leaves the commonest case as the one the user
cannot verify, and is inconsistent with how the encoding is reported.

**R33c.** A file may be dropped onto the window at any time, including when one
is already open. There is no separate drop target to hit and no need to go
through the file dialog to change files.

**R33e.** A number large enough to matter is asked about, not coloured. Above
10,000 output files the application stops and asks outright, because a colour
change reads as decoration — particularly on first use, with nothing to compare
it against — and because that many files in one folder is far more often a
mistyped row count than an intention, with consequences that land on the file
system rather than on this application.

**R33f.** Counted phrases agree with their number. "19 files of 1 rows each" is
what carelessness looks like, and one row per file is precisely what a mistyped
figure produces.

**R33g.** File paths are shown in full and can be selected and copied. A label
cannot be selected in JavaFX and elides what will not fit, which for a path
hides the middle — the part that says which folder it is.

**R33h.** After a split, the destination is stated in full. The application may
have created that folder itself, so "it made one somewhere" is not enough.

**R33d.** There is no separate finished screen. The outcome of a split appears
above the options, which remain as they were, so a setting can be adjusted and
the split repeated in one press. A finished screen whose only exit is "start
again with another file" is a dead end.

**R34.** Before splitting, the consequence is stated in a sentence: how many
files, of what size. A person who cannot judge 65,000 against 100,000 can judge
19 files against 30.

**R35.** Long work happens off the interface thread, reports progress, and can
be cancelled. Progress counts files *finished*, so the file being written is
reported as the one after them — a single-file run says "File 1", not "File 2".

**R35a.** The first rows are shown with their columns aligned. Ragged spacing
makes the reader count separators to work out which value sits under which
heading. Over-long cells are shortened rather than pushing the rest off the
side, and a row with fewer cells does not end in a separator pointing at
nothing.

**R36.** Failures are reported in the user's language and in terms of what to do
next. The operating system's own wording is used only where the failure is not
one we recognise.

**R37.** The application follows the system's light or dark appearance by
default, and continues to follow it while running.

### Remembering settings

**R54.** Settings are stored in each platform's conventional location:
`~/Library/Application Support/CSV Cleaver/` on macOS, `%APPDATA%\CSV Cleaver\`
on Windows, `$XDG_CONFIG_HOME/csv-cleaver/` on Linux.

**R55.** Kept between sessions: appearance, language, output folder, splitting
mode, row count, size, file name pattern, the Excel row cap, whether Advanced
was open, and the window's size and position.

**R56.** Nothing about a particular file is kept — no path, no survey, no
result. The window always opens ready for a new file.

**R57.** Settings are gathered when the application closes rather than written
as each one changes. Typing into the row-count box changes state on every
keystroke, and writing the settings file that often would be absurd.

**R58.** A remembered window size or position that is no longer sensible — from
a monitor that has been detached, or a corrupted settings file — is ignored
rather than obeyed.

**R59.** An unreadable settings file is not an error. It means the defaults
apply, which is what happened the first time the application was ever run.

---

## 8. The optional local service

An HTTP interface to everything in sections 1 to 5, so that the application can
be driven by a script. It is optional in the strongest sense: without a command
line option, none of this exists at runtime.

### Whether it runs at all

**R60.** No socket is opened unless `--api` is given. The default is off.

**R61.** The service binds `127.0.0.1` and only `127.0.0.1`. There is no option
to bind anywhere else.

**R62.** Every request under `/api/` requires a token supplied as
`Authorization: Bearer <token>`. The two exceptions are the OpenAPI document and
the page that renders it, which describe the service and disclose nothing from
it.

**R63.** A token not given with `--api-token` is generated with a
cryptographically secure source, is at least 32 characters, and is printed once
at startup. Tokens are compared without revealing where they first differ.

**R64.** The startup banner states, in words, that with `--api-input path`
anyone holding the token can have the application read any file the user can
read.

**R65.** `--headless` runs the service with no window. Given without `--api` it
is refused, because it would start nothing at all. Loading the entry point must
not require the interface toolkit, so that `--headless` works on a machine with
no display.

### What may be given to it

**R66.** `--api-input` takes one of `none`, `path`, `upload` or `both`, and
bounds what the file-taking endpoints accept. A path in a mode that does not
allow paths, or an upload in a mode that does not allow uploads, is refused with
a message naming the option responsible.

**R67.** The mode in force is readable at `GET /api/capabilities`, so a caller
can ask rather than discover it by being refused.

**R68.** An upload is bounded in size, and the limit is reported through
`GET /api/capabilities`.

**R69.** In `upload` mode a caller may not name an output folder, that being a
path on this machine. The results are fetched instead as one archive.

### What it does

**R70.** `POST /api/surveys` returns what section 1 and section 5 determine
about a file, and writes nothing.

**R71.** `POST /api/plans` returns what section 3 and the disk-space rules of
section 4 determine, and touches no disk.

**R72.** `POST /api/splits` starts the split of section 3 and answers
immediately with a job identifier. A plan carrying a problem is refused before a
job is created, rather than started and abandoned.

**R73.** Every guarantee of section 4 applies identically to a split started
through the service. In particular, nothing already on disk is replaced.

**R74.** A job reports progress while running and, once finished, what was
written. A finished job stays readable for a period long enough for a slow
caller, then is forgotten.

**R75.** `DELETE /api/splits/{id}` cancels a running split, leaving a whole
number of complete output files as R30 requires.

**R76.** `GET /api/splits/{id}/archive` returns the job's output files as a
single archive.

**R77.** Temporary files created for an uploaded request are removed when the
job is forgotten or when the service stops.

**R78.** Problems and warnings carry both a stable machine-readable code and a
sentence. The service answers in English regardless of the interface language:
its caller is a program, and a string that changes with the user's locale is not
something to match on.

**R79.** The service describes itself in OpenAPI, and serves a page from which
every endpoint can be exercised.

---

## 9. What is deliberately not done

- **No repair of malformed files.** Damage is reported and passed through
  untouched. A tool that silently corrects data is worse than one that does not,
  because the user cannot tell what changed.
- **No re-serialisation.** See R12. Parsing and re-emitting would normalise
  quoting and whitespace, changing files the user never asked to change.
- **No outbound network access of any kind**, and no inbound access at all
  unless `--api` is given.
- **No telemetry.**
