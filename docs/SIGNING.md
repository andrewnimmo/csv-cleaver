# Signing and notarisation

Everything in the build that signs is **inert until credentials exist**:
no secrets, no signing, and the release works exactly as before. This file is
the map from "I have decided to sign" to a notarised release, written so the
only editing ever needed is adding secrets — the scripts and workflow are
already wired.

## macOS: what you create (once)

1. **Apple Developer Program** membership ($99/year) on your Apple ID.
2. A **Developer ID Application** certificate — Xcode ▸ Settings ▸ Accounts ▸
   Manage Certificates, or developer.apple.com. Export it from Keychain
   Access as a `.p12` with a password.
3. An **App Store Connect API key** (App Store Connect ▸ Users and Access ▸
   Integrations ▸ App Store Connect API) with the Developer role. Note the
   Key ID and Issuer ID; download the `.p8` once — Apple never shows it again.

## macOS: the five secrets

```bash
base64 -i DeveloperID.p12 | gh secret set MACOS_CERTIFICATE
gh secret set MACOS_CERTIFICATE_PASSWORD    # the .p12 export password
gh secret set ASC_KEY_ID                    # e.g. 2X9R4HXF34
gh secret set ASC_ISSUER_ID                 # the UUID shown beside the keys
gh secret set ASC_KEY_P8 < AuthKey_XXXX.p8
```

That is the whole switch. The next tag builds a signed, hardened-runtime,
notarised, stapled DMG for each architecture, and the release fails rather
than publishes if Gatekeeper would refuse the result — `spctl --assess` runs
in the workflow, so "notarised" is observed per release, never assumed.

## macOS: what the machinery does with them

- `package/build-mac.sh` sees `MACOS_SIGNING_IDENTITY` (derived from the
  imported certificate by the workflow) and passes `--mac-sign` plus
  [`package/macos/entitlements.plist`](../package/macos/entitlements.plist)
  to jpackage. The entitlements are three, each with its reason written in
  the file: JIT and writable-executable memory for the JVM, and
  `disable-library-validation` for JNA — without which the application-menu
  About item would silently vanish from a hardened build.
- The workflow imports the certificate into a throwaway keychain, notarises
  each DMG with `notarytool --wait`, staples the ticket, and asks Gatekeeper.

To test locally without CI, once the certificate is in your login keychain:

```bash
MACOS_SIGNING_IDENTITY="Developer ID Application: Andrew David Nimmo (TEAMID)" bb package
```

```bash
xcrun notarytool store-credentials csv-cleaver --key AuthKey_XXXX.p8 --key-id KEYID --issuer ISSUER
```

```bash
xcrun notarytool submit "dist/CSV Cleaver-2.0.0.dmg" --keychain-profile csv-cleaver --wait && xcrun stapler staple "dist/CSV Cleaver-2.0.0.dmg"
```

Expect the first signed build to be a round trip: Apple's rejection emails
carry logs, and the JNA entitlement is the most likely negotiation. That
expectation is recorded in VERIFICATION.md rather than discovered in
surprise.

## Windows: Authenticode

Notarisation is Apple-only; Windows' equivalent is Authenticode on the MSI,
and since 2023 the certificate must live in hardware or a cloud signer. The
practical CI route is **Azure Trusted Signing** (~$10/month): create the
account, then add a signing step with `azure/trusted-signing-action` and the
`AZURE_*` secrets at the placeholder marked in
[release.yml](../.github/workflows/release.yml). SmartScreen's "Windows
protected your PC" then fades as reputation accrues (immediately, with an EV
certificate from a traditional CA).

## Linux

No gatekeeper to satisfy. Every release already publishes `SHA256SUMS`
covering all installers, so users on any platform can verify a download:

```bash
shasum -a 256 -c SHA256SUMS
```
