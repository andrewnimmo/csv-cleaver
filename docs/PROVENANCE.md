# Provenance

Made with 🤖 in Barcelona.

This codebase was written predominantly by an AI — Claude (Anthropic), working
through Claude Code — under the direction of a human Technical Project Manager.
This document says exactly what that means, because "AI-generated" covers
everything from an unsupervised paste to a managed engineering project, and the
difference matters to anyone deciding whether to trust, use or contribute to
this code.

## Who did what

**Andrew David Nimmo — Technical Project Manager.** Set the requirements and
the constraints (among them: the application must never overwrite a file;
translations must never live in code; the API must be off unless asked for).
Made every product decision, chose between design alternatives, reviewed and
accepted or rejected each round of work, and acceptance-tested the real
packaged builds — several of the most serious defects this project has had
(locale-blind installers, settings that changed meaning across languages, a
mis-centred icon) were found by that human testing of the artefact, not by the
AI or its test suite. Directed the testing standards the project now enforces.

**Claude (Anthropic)** — implementation, tests, documentation, and the build
and release tooling, across two long-running sessions in 2026: an initial
version, and a full rewrite (model: `claude-fable-5`, via Claude Code). Every
commit records the involvement with a `Co-Authored-By: Claude` trailer.

The division is honestly described as: the human decided *what* and *whether*;
the AI produced *how*, subject to review. No line of this codebase reached the
repository without having been requested, and the direction of every subsystem
traces to a human decision recorded in [DECISIONS.md](DECISIONS.md).

## Copyright

The copyright notice for this project is:

> Copyright © 2026 Andrew David Nimmo

and the project is licensed under the [Apache License 2.0](../LICENSE).

The legal ground under AI-generated code is still settling, and this project
does not pretend otherwise. The position taken here, and the reasoning:

- **Purely machine-generated material, with no human creative contribution,
  is not copyrightable** in the United States (U.S. Copyright Office guidance,
  2023, reaffirmed in its January 2025 report on copyrightability), and the
  same conclusion follows in the European Union and Spain, where authorship
  requires a natural person's own intellectual creation.
- **Human contribution can make the resulting work protectable** — selection,
  coordination, arrangement, iterative direction and revision are the kinds of
  contribution those same authorities recognise. This project's development was
  exactly that: sustained human direction, decision-making and acceptance over
  months, recorded decision by decision.
- The copyright claim here is therefore made **on the work as a whole as a
  directed, curated compilation**, in the name of the person who directed it.
  To whatever extent a court would find some individual portion uncopyrightable,
  the Apache-2.0 grant simply has nothing to attach to for that portion — the
  practical permissions for users are unchanged either way.
- Anthropic's commercial terms assign to the customer Anthropic's rights, if
  any, in model outputs, so no third party asserts a competing claim.

None of this is legal advice; for decisions that depend on the precise
boundary, consult a lawyer familiar with your jurisdiction.

## Practices followed

The recommendations this project follows for AI-provenance code, all of them
verifiable in the repository:

1. **Disclosure at every level** — this file, the README, the About dialog,
   and the five-word version on the tin.
2. **Attribution in history** — every AI-authored commit carries the
   `Co-Authored-By: Claude <noreply@anthropic.com>` trailer.
3. **The model is named** — `claude-fable-5`, via Claude Code — because
   "an AI" is not reproducible information and a model id is.
4. **Human review is the merge gate**, and the human's role is stated
   precisely (Technical Project Manager), not inflated into authorship of the
   code nor erased into a rubber stamp.
5. **The verification burden is treated as higher, not lower.** AI-generated
   code ships plausible-looking defects; this project's answer is
   [ASSUMPTIONS.md](ASSUMPTIONS.md), a mutation-tested suite (`bb mutate` —
   a test that has never failed proves nothing), tests whose expectations come
   from external referents rather than from the code under test, and checks
   that run against the built artefact rather than only the source tree.
   [SECURITY.md](SECURITY.md) states plainly that single-party review is the
   project's weakest form of assurance.
6. **Dependency and vulnerability auditing is mandatory**, with the audit
   proven to actually scan (`bb audit` fails on an implausibly empty scan,
   because it once passed while scanning nothing).
