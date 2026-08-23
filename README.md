# GuardianLink — v1 Kotlin Scaffold

Sovereign personal data platform: zero-authority concierge interface, fail-closed
tiered PolicyEngine, Merkle tamper-evident audit log, Shamir's Secret Sharing MFA,
MAD-based trajectory estimation, and a friction/cooling-off pre-commitment rail
system.

## Build

Push this repo to GitHub as-is — .github/workflows/build.yml will build and test
it automatically via GitHub Actions (free runners, full JDK/Gradle support).

Locally: ./gradlew build test (requires JDK 17+).

## Verification status

The algorithmic core of every bug-prone module (Merkle checkpointing, MAD shift
detection, CAS concurrency, Shamir reconstruction, PolicyEngine fail-closed
invariant) was verified via adversarial Python-ported test batches before this
Kotlin code was written, confirming the logic is sound. The Kotlin itself has been
manually audited (brace/paren balance, import/package consistency) but not yet
compiler-verified — first CI run will confirm.

Not included (expansion-path, not v1 core): MinorProfile adapter,
HierarchicalOversight adapter, hardware Keystore/AES-GCM bindings (Android-target
specific), aggregate research/consent-bundle layer.