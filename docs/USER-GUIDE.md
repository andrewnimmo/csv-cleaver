# CSV Cleaver — user guide

CSV Cleaver takes one large CSV file and makes several smaller ones from it,
so that Excel can open them.

You do not need to know anything about CSV files to use it. The application
works out what it can and asks you only about the things it genuinely cannot
decide.

---

## Why files need splitting

Excel can open a sheet of at most **1,048,576 rows**. Older versions stopped at
65,536. A file with more rows than that will not open at all — not partly, not
with a warning. Splitting it into pieces small enough to open is the usual way
round the problem.

---

## Installing

Download the installer for your computer from the
[releases page](https://github.com/andrewnimmo/csv-cleaver/releases).

The installers are not signed with a paid certificate, so your computer will
warn you the first time. This is expected.

**macOS.** Open the `.dmg` and drag the application to Applications. The first
time you open it, macOS will say it cannot be opened. Right-click the
application in Applications, choose **Open**, then confirm. You only do this
once. Choose the Apple Silicon download for an M-series Mac, or the Intel one
for anything older.

**Windows.** Run the `.msi`. Windows shows "Windows protected your PC". Choose
**More info**, then **Run anyway**.

**Linux.** Either install the `.deb` with `sudo dpkg -i csv-cleaver_*.deb`, or
make the AppImage executable with `chmod +x` and run it directly.

Nothing else needs installing. Each download contains everything it needs.

---

## Splitting a file

**1. Choose the file.** Drag it onto the window, or press *Browse*.

The application then reads the file through once. For a very large file this
takes a few seconds, and you will see it counting.

**2. Look at what it found.** A row of small labels appears:

| Label | Meaning |
|---|---|
| `1,204,338 data rows` | How many rows of actual data the file holds |
| `218 MB` | How big it is |
| `Text: UTF-8` | How the letters are stored. Almost never needs attention |
| `Looks healthy` | Nothing unusual was found |
| `12 rows look damaged` | Something unusual was found — see below |

**3. Answer the question, if you are asked one.** Sometimes the application
cannot tell whether the first row of your file contains column names or your
first row of data. When that happens it shows you the row and asks. Look at
what it shows you: if it reads like a list of labels — `id`, `name`, `city` —
they are column names. If it looks like a real record — `1`, `Ann`, `Leeds` —
it is data.

Most of the time it works this out for itself and does not ask.

**4. Choose how to split.** Either equal numbers of rows, or equal file sizes.

If you choose rows, the shortcut buttons explain themselves:

- **65,000** — safe for very old versions of Excel
- **100,000** — a comfortable size to work with
- **1,048,576** — the most Excel can open in one sheet

**5. Read the sentence.** Under the box, in blue, the application tells you what
will happen before it happens:

> This makes 19 files — 18 with 65,000 rows and one with 34,338.

If that is not what you wanted, change the number and the sentence changes with
it. Nothing has been written yet.

**6. Choose where they go.** By default the results go into a **new folder named
after your file** — splitting `customers-2024.csv` creates a `customers-2024
split` folder beside it. That way the results cannot get mixed up with anything
else, and it is obvious which run they came from.

Underneath, you are shown what the files will be called, and whether the folder
already exists. If it does and already holds CSV files, you are told how many —
so a folder chosen by mistake is visible now, rather than later.

You can change the destination. Wherever you choose is remembered as the home
for the next file's folder.

**7. Press *Split file*.**

---

## Things it may tell you

### "6 CSV files in that folder use these names"

The folder already contains files with the names this split would use. **Nothing
has been written yet.**

The application deliberately does not tell you what those files are, because it
cannot know. They might be the results of a previous split — or they might be
your own files, in a folder you picked by mistake. All it has observed is that
the names clash. The dialog shows you the folder's full path so you can check.

Your three choices:

- **Use a new folder** — the safe one, and the one already selected. The results
  go somewhere new and nothing existing is touched.
- **Replace them** — the listed files are written over. Any *other* files whose
  names match go to the Trash or Recycle Bin, so a mistake can be undone. On a
  system with no Trash they are left alone and you are told so.
- **Cancel** — nothing happens at all.

Pressing Return chooses the safe option. Nothing is ever removed by accident.

### "12 rows look damaged"

Some rows do not follow the usual rules — perhaps one has more columns than the
others, or a stray quotation mark. This is common in files exported from older
systems.

**Nothing is repaired and nothing is thrown away.** Damaged rows are copied
across exactly as they arrived. You lose nothing, and you can look at them
yourself afterwards. The application tells you so you are not surprised.

### The results open with strange characters

If accented letters look like nonsense when you open a result — `RenÃ©` instead
of `René` — the text encoding was guessed wrongly. Open **Advanced** and change
**Text encoding**, then split again. This is rare.

---

## Stopping a split

Press **Cancel**. The file currently being written is removed, and the files
already finished are kept. The application tells you how many survived, so you
are never left wondering whether a half-written file is lurking among them.

---

## Changing the language

Press the **i** button in the top corner. The application starts in your
computer's language if it is one of the six it speaks — English, Spanish,
French, German, Chinese, Japanese — and English otherwise.

The five non-English translations were produced by machine and have not yet been
checked by a native speaker. The About box says so.

### Adding a language yourself

You can add one without waiting for a new version. Copy the English file from
the application, translate it, and put it in this folder:

| Your computer | Folder |
|---|---|
| macOS | `~/Library/Application Support/CSV Cleaver/languages/` |
| Windows | `%APPDATA%\CSV Cleaver\languages\` |
| Linux | `~/.config/csv-cleaver/languages/` |

Name it after the language — `it.edn` for Italian — and restart. It then appears
in the language list like any other.

Files placed there are checked before they are used, because a file that arrived
by some other route could otherwise put words on your screen that the
application would never say. A file is refused if it invents wording that does
not correspond to something already in the application, if it is implausibly
large, or if it contains characters that can make text display differently from
what it really says.

If anything is refused, the application says so and does not start in that
language. You can quit, or carry on in English.

---

## Quitting

**File → Quit**, or the usual keyboard shortcut for your computer — ⌘Q on macOS,
Ctrl+Q on Windows and Linux. Closing the window works too.

On a Mac the menu appears at the top of the screen, as it does for every other
application; on Windows and Linux it is at the top of the window.

Quit is in a menu rather than on the window itself so that it cannot be pressed
by accident while you are working.

---

## Light and dark

Also under the **i** button. By default the application follows whatever your
computer is set to, and keeps following it if you change it while the
application is open.

---

## Checking for updates

Under the **i** button there is a **Check for updates** button. Pressing it
asks GitHub — where releases are published — whether a newer version exists,
and if one does, shows a link to its download page. Nothing downloads or
installs itself; you fetch the new installer and run it yourself, exactly as
you did the first time.

Nothing is ever checked without you asking. If you would like the application
to look once at startup, tick **Check automatically when the application
starts** — then, if a newer version exists, a small link appears at the bottom
of the window, and if you are offline or already current, nothing appears at
all. The request carries no data of yours: only the application's own name and
version, which is how polite software introduces itself to a server.

If the application is deployed somewhere it must never make a network request,
start it with `--no-update-check`: the button and the checkbox disappear, and
no check can happen by any route.

---

## Getting help inside the application

The **?** button answers the questions people actually ask: what a header row
is, why some rows are called damaged, what text encoding means, and what happens
to files already in the folder.

---

## Splitting files automatically

If you find yourself doing the same split every week, the application can be
driven by a program instead of by hand. This is a job for whoever looks after
your computers rather than something to set up yourself — point them at
[API.md](API.md). Nothing about it is switched on unless somebody deliberately
switches it on, and it never reaches beyond your own machine.

---

## What the application will never do

- Change your original file in any way.
- Overwrite anything without asking you first.
- Repair, reformat or silently alter a single row of your data.
- Send your data anywhere. Splitting never touches the network. The one
  request the application can make — the update check above — happens only
  when you press the button or tick the box, carries nothing but the
  application's own name and version, and can be forbidden outright with
  `--no-update-check`.
