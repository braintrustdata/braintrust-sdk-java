# Publishing the Java SDK

The Java SDK is released from GitHub Actions via a single **Release** workflow
([`.github/workflows/release.yml`](.github/workflows/release.yml)). There is no local release script
and no separate tag-triggered publish workflow — one manual run drives the whole pipeline. Do not
publish from your local machine.

Java releases are **stable-only**: the workflow validates that `version` matches `vX.Y.Z` and rejects
prerelease or `-SNAPSHOT` versions. The entire job runs in the protected **`release`** GitHub
Environment, which holds the Sonatype / GPG secrets and **requires reviewer approval** before any tag
is pushed or any artifact is published.

## Release

1. Open a PR that bumps the version and merge it to `main`.
2. Copy the full 40-character SHA of the commit you want to release (use GitHub's **Copy full SHA**
   button).
3. Run the **Release** workflow (Actions → Release → Run workflow) with:
   - `version` — the release version, e.g. `v1.2.3`.
   - `sha` — the full 40-char commit SHA to tag. Supplying an explicit SHA (not a branch) ensures
     commits that land on `main` during the approval gate are **not** silently included.
4. Approve the `release` environment when GitHub prompts.

Once approved, the workflow:

1. Validates the version and SHA, and verifies the SHA is an ancestor of `origin/main`.
2. Runs `./gradlew check` on the chosen SHA.
3. Creates and pushes the annotated tag `vX.Y.Z` at that SHA, then re-runs `./gradlew check` at the tag.
4. Builds the release artifacts.
5. Creates the GitHub Release and uploads the SDK jars (main / sources / javadoc), the
   `braintrust-java-agent` jar, and the `braintrust-otel-extension` jar.
6. Publishes to Maven Central via Sonatype, GPG-signed with the project key.
7. Polls Maven Central until the new version is visible. **This can take many hours.**

## Re-publishing a failed release

Re-run the **Release** workflow with the **same `version`**. If the tag already exists, the
tag-creation step is skipped and the rest of the pipeline runs against the existing tag; GitHub
Release asset uploads clobber any partial uploads.

## Verify

- GitHub Release: https://github.com/braintrustdata/braintrust-sdk-java/releases
- Maven Central: https://central.sonatype.com/artifact/dev.braintrust/braintrust-sdk-java/versions

Then run the test app with the newly published SDK (check that traces and evals look okay):

- https://github.com/braintrustdata/sdk-test-apps — `make verify-java`
