# Verification ledger

What is currently claimed about this application, at what level of evidence,
and what remains unverified. Kept because a claim's verification level turned
out to matter as much as the claim: three fixes in one development session
were reported as done and then falsified by the project manager's testing —
each a mechanism verified without verifying the path to it.

Levels:

- **OBSERVED** — executed against the artefact; output on record.
- **TESTED** — automated test exists *and has been shown able to fail*
  (`bb mutate`, or a witnessed failure during development).
- **DERIVED** — concluded from reading source or bytecode; not executed.
- **PENDING-USER** — only verifiable on a real display/keyboard; steps given.

Nothing may be reported as *done* unless OBSERVED or TESTED. The falsification
record at the bottom is a metric, not a confession box: it says where claims
from this project's own development proved unreliable, so a reader can weight
the rest accordingly.

## Active claims — build `e2047cb`

| Claim | Level | Evidence |
|---|---|---|
| Splitting is byte-faithful; concatenating output reproduces input | TESTED | `samples_test/every-sample-splits-and-rejoins-byte-for-byte`; scanner mutations caught |
| Nothing on disk is overwritten without consent | TESTED | collision tests + API `nothing-already-on-disk-is-replaced`; mutation caught |
| API refuses without token; input modes enforced; loopback only | OBSERVED + TESTED | live smoke on real socket (401/400/202/404 on record); 5 API mutations caught |
| Installer bundles locale data; packaged binary starts | OBSERVED | build inspects its own image and launches it; `It starts: 2.0.0 (e2047cb)` |
| macOS icon centred | OBSERVED | margins L49 R49 T49 B49 read from shipped icns bytes; generator refuses asymmetry |
| Get Info shows canonical copyright | OBSERVED | `plutil -extract NSHumanReadableCopyright` on the built app |
| One file dialog at a time; stale scans discarded | TESTED | claim-guard + scan-epoch tests; 3 mutations caught. Dialog modality itself is platform behaviour: PENDING-USER below |
| Dialog cards never outgrow the window | TESTED | layout-bounds fx test at 420×320; mutation caught. The earlier version of this test measured the drop-shadow and was discarded |
| Single-line dialog titles cannot clip ink | TESTED for shape, **PENDING-USER for the pixels** | titles are Text nodes, which do not clip by construction; the two prior padding fixes were falsified on a Retina display, so this one is not "done" until seen there |
| Alt/Option + About reveals hidden languages | TESTED for wiring, **PENDING-USER for the keystroke** | real KeyEvent driven through the real scene; the focus path from a physical key press cannot be exercised headless |
| Session survives ⌘Q / Glass Quit / kill | TESTED for mechanism | geometry-tracking + shutdown-hook tests; the ⌘Q path itself on a live Mac is PENDING-USER |
| macOS app menu carries Quit (Glass's own) | DERIVED + user-observed | `MacApplication.installDefaultMenus` read from bytecode; the user reports Quit present |
| macOS app menu carries About, translated, opening the About overlay | OBSERVED + TESTED | installed via the Objective-C runtime (JNA) after the public-API route was proven absent; the fx test reads the item's title back **from AppKit** and fires it through `performActionForItemAtIndex:` — AppKit's own click dispatch — asserting the overlay opens. Probe run on record: install → "About CSV Cleaver" → retitle "Acerca de CSV Cleaver" → perform → handler fired |
| A selected mode/theme pill cannot be switched off | TESTED | real MouseEvent and KeyEvents through the guard: press and Space consumed on a selected pill, Tab never trapped, unselected pills unaffected; mutation caught. The visual on a live click is PENDING-USER below |
| Eight visible languages complete; two hidden ones partial-by-design | TESTED | parity/plural/placeholder suites; egg-bundle contract test |

## To verify on the next build (PENDING-USER)

1. **App menu ▸ About CSV Cleaver** exists, above Hide, and opens the About
   overlay; switch language in About and the menu item's title follows.
2. Help and About titles intact at the right edge — the mechanism is Text
   nodes this time, not more padding.
3. Hold Option, click the ℹ️ button: the Language picker offers tlhIngan Hol
   and Vuhlkansu.
4. Click the already-selected "Equal row counts" pill: it must stay selected —
   never the both-off state.
5. One file dialog at a time; ⌘Q quits cleanly with one open.
6. Move/resize the window, quit with ⌘Q: position remembered on next launch.

## Falsification record

Claims stated as done during development and later disproven by the project
manager's testing of the real artefact. All five cluster in desktop-UI and
platform-integration territory; none of the engine or API "done" claims has
been falsified to date. The pattern in every case: a mechanism was verified
without verifying the path to it.

| Claim as stated | Falsified by | Root cause |
|---|---|---|
| "Locale formatting fixed" (first fix) | numbers still English in the app | fixed one layer (language switch) while the defect had a second home (settings file) and a third (runtime missing locale data) |
| "Locale formatting fixed" (second fix) | same symptom, screenshots | the artefact's runtime lacked `jdk.localedata`; every test ran on a full JDK where it cannot be missing |
| "Icon wired in and verified" | Dock screenshot: uncentred | "verified" meant the binary launched; nobody had read the icon's pixels — snapshot had silently discarded the centring transform |
| "Title clipping fixed" (padding) | 'p' still clipped on Retina | padding widens a Label *and moves its text*, leaving the same knife-edge; fix verified at 1× for a 2× defect |
| "Alt reveals hidden languages" | keypress did nothing | handlers sat on a node that never has focus; tests dispatched synthetic maps, proving the handler worked if reached, never that anything reached it |
| "macOS app menu carries About, tested" | no such menu item exists | `Desktop.setAboutHandler` handles an event AWT never receives under JavaFX, whose Glass toolkit owns the menu; "tested" meant handlers installed without error |

Six falsified claims across roughly twenty substantive reports in the same
period. The corrective in force: every claim now carries one of the four
levels above, and "done" is reserved for OBSERVED or TESTED.

Resolution note: the app-menu About found its real implementation afterwards —
through the Objective-C runtime, verified by reading AppKit's own state — so
the falsified entry above records the false *claim*, not a permanent
impossibility. "Impossible via public API" had been the true part; treating it
as the end of the road was the error the project manager corrected.
