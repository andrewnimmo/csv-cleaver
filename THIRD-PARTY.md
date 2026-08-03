# Third-party components

CSV Cleaver is distributed under the Apache License 2.0 (see `LICENSE`). It
depends on, and its installers redistribute, the components below.

## Bundled in the installers

The `.dmg`, `.msi`, `.deb` and AppImage each contain a Java runtime produced by
`jpackage`. That runtime is **not** covered by this project's Apache-2.0 licence.

| Component | Licence | Source |
|---|---|---|
| OpenJDK 25 (Eclipse Temurin) | GPL-2.0 with Classpath Exception | https://github.com/adoptium/temurin-build |
| OpenJFX 25 | GPL-2.0 with Classpath Exception | https://github.com/openjdk/jfx |

The Classpath Exception is what makes this combination lawful: it grants
permission to link independent modules against these components and to
distribute the result under terms of your choosing, without the resulting work
becoming subject to the GPL. The components themselves stay under GPL-2.0+CE,
and the links above satisfy the corresponding source-availability requirement.

## Clojure dependencies

| Component | Licence |
|---|---|
| org.clojure/clojure | Eclipse Public License 1.0 |
| cljfx/cljfx | MIT |
| io.github.mkpaz/atlantafx-base | MIT |
| org.openjfx/javafx-{base,graphics,controls} | GPL-2.0 with Classpath Exception |

## Build and test only

Not redistributed; used during development and in CI.

| Component | Licence |
|---|---|
| io.github.clojure/tools.build | Eclipse Public License 1.0 |
| io.github.cognitect-labs/test-runner | Eclipse Public License 1.0 |
| cloverage/cloverage | Eclipse Public License 1.0 |
| clj-kondo/clj-kondo | Eclipse Public License 1.0 |
| dev.weavejester/cljfmt | Eclipse Public License 1.0 |
