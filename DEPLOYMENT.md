# Deployment via GitHub Actions (recommended)

Releases are published by the `release` workflow (`.github/workflows/release.yml`).
No local credentials and no personal GitHub login are needed — everyone with
write access to the `ajilach/openhtmltopdf` repository can publish independently.

## How it works

The workflow builds all modules, signs them with GPG and uploads them to Maven
Central via the `central-publishing-maven-plugin` (`autoPublish=true`). The
publishing credentials and the signing key live as **repository secrets**, not on
anyone's machine.

Tests are skipped in the release workflow (`-DskipTests`): the visual regression
tests in `openhtmltopdf-examples` are font/OS-sensitive and fail on the Linux
runner even though the artifacts are correct. Test execution is the job of the
separate `build` workflow.

## Who can release

Only collaborators/organization members with **write access** can trigger it:

- `workflow_dispatch` (the "Run workflow" button) requires write access.
- Pushing a `v*` tag requires write access.

Anyone forking this public repository works on their own fork; their forks have
their own (empty) secrets and cannot push to or trigger workflows in this repo.
Fork pull requests run **without** access to the secrets below. There is no
required reviewer — any team member can release on their own.

## One-time setup: repository secrets

Configure these under **Settings > Secrets and variables > Actions** in the
`ajilach/openhtmltopdf` repo (done once, by a repo admin):

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central user token — username part |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central user token — password part |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored GPG private key (full block) |
| `MAVEN_GPG_PASSPHRASE` | passphrase of that GPG key |

Get the values:

- **User token**: log in at https://central.sonatype.com/ > Account > Generate
  User Token. (Generating a new token invalidates the previous one.)
- **GPG private key** (export the release signing key):
  ```bash
  gpg --armor --export-secret-keys <GPG_KEY_ID_OR_EMAIL>
  ```
  Copy the entire output, including the `-----BEGIN/END PGP PRIVATE KEY BLOCK-----`
  lines, into the secret.

> Note: this uploads the Sonatype token and the GPG signing key to GitHub as
> secrets. Alternatively, each releaser can use their own user token and their
> own GPG key (any key whose public part is on a keyserver is accepted) — only
> the four secret values would differ.

If the `gh` CLI is installed and authenticated, the secrets can also be set from
the command line:

```bash
gh secret set MAVEN_CENTRAL_USERNAME --repo ajilach/openhtmltopdf
gh secret set MAVEN_CENTRAL_PASSWORD --repo ajilach/openhtmltopdf
gh secret set MAVEN_GPG_PASSPHRASE  --repo ajilach/openhtmltopdf
gpg --armor --export-secret-keys <GPG_KEY_ID_OR_EMAIL> \
  | gh secret set MAVEN_GPG_PRIVATE_KEY --repo ajilach/openhtmltopdf
```

## Releasing a new version

1. Bump the version in the parent `pom.xml`: `<revision>increase version</revision>`.
2. Commit: `Prepared revision version 1...` and push to `master`.
3. Start the release, either:
   - **Actions tab** > *release* workflow > **Run workflow** (select `master`), or
   - push a tag: `git tag v1.0.x && git push origin v1.0.x`.
4. Watch the run in the Actions tab. Then check
   https://central.sonatype.com/publishing — it takes around 15 min. from
   publishing to published.
5. (Optional, to keep the existing convention) create a `1..._release` tag and
   merge `master` back into `develop`.

Remember: Maven Central versions are immutable (see *Version Immutability* below).
A failed CI build never publishes, but a successful publish of a wrong version
cannot be undone — only superseded by a higher version.

---

# Deployment from a local machine (fallback)

Requires `~/.m2/private_settings.xml` with the `central` server credentials and
the GPG profile, plus the signing key in the local GPG keyring.

1. Merge develop into master
2. Edit parent pom <revision>increase version</revision>
3. Commit: Prepared revision version 1...
4. Run: mvn clean deploy -P release -s ~/.m2/private_settings.xml
5. Commit: Finished deployment 1...
6. Merge back to develop and create new tag 1..._release

Check status on https://central.sonatype.com/publishing (it takes around 15min. from publishing to published)

# Maven Central Distribution Guide

This document summarizes the process of publishing this project to Maven Central.
Keep account names, token values, private key material, passphrases, local machine
paths, and deployment IDs out of this public file.

## Project Configuration

The Maven coordinates and current version are defined in the parent `pom.xml`.
Use that file as the source of truth before publishing.

## Prerequisites

### 1. GPG Key Setup

A release signing key must exist and its public key must be published to a public
keyserver accepted by Maven Central.

```bash
# Install GPG, if needed
brew install gnupg

# Generate a key, if needed
gpg --full-generate-key

# Export the private key only when setting the GitHub Actions secret
gpg --armor --export-secret-keys <GPG_KEY_ID_OR_EMAIL>
```

### 2. Maven Central Registration

- Register at https://central.sonatype.com/
- Verify the namespace used by this project
- Generate a user token for deployment credentials

### 3. Maven Settings Configuration for Local Fallback

If using the local fallback flow, create a private Maven settings file outside the
repository with:
- Server credentials using the same id configured by the publishing plugin
- GPG configuration for the release signing key
- An active profile for GPG signing

Never commit this settings file or any generated secret material.

## POM Configuration

### Parent POM (`pom.xml`)

Key configurations:
1. Project Maven coordinates are defined in the parent POM
2. `central-publishing-maven-plugin` handles publication
   - `publishingServerId`: `central`
   - `autoPublish`: `true`
3. `distributionManagement` is not needed with the Central Publishing plugin
4. GPG signing is configured in the release profile

### Child Module POMs

Child modules should inherit the parent coordinates and publishing configuration.
They should not define conflicting `distributionManagement` sections.

## Common Issues Encountered and Solutions

### Issue 1: 401 Unauthorized Error

**Cause**: Module POMs define their own `distributionManagement` sections or use a
server id that does not match the publishing plugin configuration.

**Solution**: Remove module-level `distributionManagement` sections and ensure the
Maven settings server id matches the plugin's `publishingServerId`.

### Issue 2: Wrong Server ID

**Cause**: The Maven settings file uses a server id different from `central`.

**Solution**: Use server id `central`, matching the `publishingServerId` in the
plugin configuration.

### Issue 3: Parent Reference Mismatch

**Cause**: Child POMs reference stale parent coordinates.

**Solution**: Update child POM parent references to match the current parent POM.

## Deployment Process

### Command
```bash
mvn clean deploy -P release -s /path/to/private_settings.xml
```

### What Happens
1. Builds all modules
2. Runs tests
3. Generates sources and javadoc JARs
4. Signs all artifacts with GPG via the release profile
5. Uploads to Maven Central via `central-publishing-maven-plugin`
6. Auto-publishes to Maven Central, typically after a short delay

## Important Notes

### Version Immutability
**Critical**: Maven Central enforces strict immutability. Once a version is published, it:
- Cannot be changed
- Cannot be replaced
- Cannot be redeployed

To make code changes, you MUST increment the version number:
- Bug fixes: 1.0.11 → 1.0.12
- New features: 1.0.11 → 1.1.0
- Breaking changes: 1.0.11 → 2.0.0

### Deploying New Versions
1. Update version in parent `pom.xml`
2. Make code changes
3. Run deployment command
4. Wait for publishing to complete

### Monitoring Deployments
Check deployment status at: https://central.sonatype.com/publishing/deployments

### Artifacts Location
Published artifacts available at:
```
io.github.rgquiet.openhtmltopdf:openhtmltopdf-core:1.0.11
io.github.rgquiet.openhtmltopdf:openhtmltopdf-pdfbox:1.0.11
... (all modules)
```

## Key Differences from Original Project

1. **GroupId**: `io.github.rgquiet.openhtmltopdf` (vs `com.openhtmltopdf`)
2. **Publishing Method**: New Central Portal with central-publishing-maven-plugin (vs old OSSRH)
3. **No distributionManagement**: Plugin handles deployment automatically
4. **Server ID**: `central` (vs `ossrh`)

## Resources

- Maven Central Portal: https://central.sonatype.com/
- Publishing Documentation: https://central.sonatype.org/publish/publish-portal-maven/
- Original Project: https://github.com/danfickle/openhtmltopdf
