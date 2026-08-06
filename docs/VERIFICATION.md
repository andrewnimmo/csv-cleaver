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
| macOS app menu carries About, translated, opening the About overlay | OBSERVED + TESTED + **user-verified** | installed via the Objective-C runtime (JNA) after the public-API route was proven absent; the fx test reads the item's title back **from AppKit** and fires it through `performActionForItemAtIndex:` — AppKit's own click dispatch — asserting the overlay opens. Probe run on record: install → "About CSV Cleaver" → retitle "Acerca de CSV Cleaver" → perform → handler fired |
| A selected mode/theme pill cannot be switched off | TESTED (armed) + **user-verified** | the first guard consumed the press in a same-node handler, which cannot block a control's own behavior — its handler-level test passed while the running app still deselected. Now a capturing scene filter; the test fires the full press/release/click at the real pill and asserts both directions: no guard → deselects, guard → cannot. Live click PENDING-USER |
| Option-reveal of hidden languages lasts exactly one About | TESTED + **user-verified** | opening plainly or closing conceals; the command-line reveal is a separate session-long flag that concealing never touches; mutation caught |
| Eight visible languages complete; two hidden ones partial-by-design | TESTED | parity/plural/placeholder suites; egg-bundle contract test |

| Signing scaffolding inert and wired | OBSERVED + TESTED | unsigned `bb package` unchanged with the scaffolding in place; entitlements, script wiring and workflow steps pinned by test, two mutations caught. The *sufficiency* of the entitlements for notarisation is DERIVED until the first signed build — the JNA `disable-library-validation` need is the expected round-trip, stated in SIGNING.md |

## Release v2.0.0 — run 31091724587, commit `082779a`

| Claim | Level | Evidence |
|---|---|---|
| Five installers published: AppImage, deb, two dmgs, msi | OBSERVED | asset list read back from the GitHub API after publish |
| SHA256SUMS covers all five; digests are real | OBSERVED | AppImage re-downloaded and re-hashed locally; digest matches the published one |
| The Intel dmg is x86_64, built on the ARM runner under Rosetta | OBSERVED | x64 JDK in the job log; `lipo -archs` assertion in the workflow fails on mismatch and passed; the packaged binary started under Rosetta ("It starts: 2.0.0") |
| The AppImage stage works | OBSERVED twice | first in an amd64 container against the real script logic (emulation divergences on record in the test script), then live on the runner |
| CVSS ≥ 7.0 fails the audit; the gate is read | OBSERVED | nvd-clojure's startup echo shows the config at the level it reads |
| The published MSI installs and the application works on real Windows 11 | **user-verified** | installed from the release page by the project manager, 2026-08-06 — the first shipped installer confirmed on real hardware rather than a runner |
| The published Apple Silicon dmg installs and the application works on a real Mac | **user-verified** | installed and tested by the project manager, 2026-08-06. General "works as expected"; the two specific checks below were not named and stay open |

## To verify on the next build (PENDING-USER)

1. ~~App menu ▸ About~~ — **verified by the project manager**.
2. ~~Selected pill stays selected~~ — **verified by the project manager**.
3. ~~Option-About reveals for one About only~~ — **verified by the project
   manager**.
4. Help and About titles intact at the right edge (Text nodes).
5. One file dialog at a time; ⌘Q quits cleanly with one open; window
   position remembered across a ⌘Q.

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
| "Selected pill cannot be switched off, TESTED" | both pills still clickable to grey | the guard consumed the press in a same-node handler, which cannot block a control's own behavior handlers; the test proved the flag was set, not that the toggle was prevented — the seventh instance of mechanism-without-path, caught this time within a day by the PENDING-USER step |

Seven falsified claims across roughly twenty substantive reports in the same
period. The corrective in force: every claim now carries one of the four
levels above, and "done" is reserved for OBSERVED or TESTED.

The release added a second column of falsifier: the first live run. These
claims were stated at DERIVED or below and disproven by the machine before
any user saw them — the ledger records them because "the scaffolding is
wired" had been reported with more confidence than a never-executed branch
deserved.

| Claim as stated | Falsified by | Root cause |
|---|---|---|
| "Release workflow wired, TESTED" | tag run: zero jobs, no log | a generator wrote `\1` as byte 0x01; GitHub rejected the whole file; the source-level tests checked for strings, not for validity |
| "Signing/release scaffolding inert and correct" | first Linux release leg | the AppImage branch had never executed anywhere: tool never installed, pin URL recalled from memory (404), and an AppDir layout appimagetool rejects |
| "Actions deprecation bump, versions checked" | every job, in seconds | 13.6 assumed from the old two-part tag pattern; the tag that exists is 13.6.1 |

The pattern is the same one as above — mechanism verified without the path —
plus one refinement worth keeping: a pin, a URL, or a version written from
memory is an assumption wearing a pin's clothes. Every such value now gets
checked against the live source before it is committed.

Resolution note: the app-menu About found its real implementation afterwards —
through the Objective-C runtime, verified by reading AppKit's own state — so
the falsified entry above records the false *claim*, not a permanent
impossibility. "Impossible via public API" had been the true part; treating it
as the end of the road was the error the project manager corrected.
