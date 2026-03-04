# Deployment steps

1. Merge develop into master
2. Edit parent pom <revision>increase version</revision>
3. Commit: Prepared revision version 1...
4. Run: mvn clean deploy -P release -s ~/.m2/private_settings.xml
5. Commit: Finished deployment 1...
6. Merge back to develop and create new tag 1..._release

# Maven Central Distribution Guide

This document summarizes the process of publishing this fork to Maven Central.

## Project Configuration

**GroupId**: `io.github.rgquiet.openhtmltopdf`
**Current Version**: `1.0.11`
**Namespace**: `io.github.rgquiet` (verified via GitHub)

## Prerequisites Completed

### 1. GPG Key Setup

Generated and configured GPG signing key for artifact verification:

```bash
# Install GPG (macOS)
brew install gnupg

# Generate key (already done)
gpg --full-generate-key

# Key details
Key ID: 2EEBCF4339C581B8
Published to: keys.openpgp.org
```

### 2. Maven Central Registration

- Registered at: https://central.sonatype.com/
- Signed in with GitHub account (rgquiet)
- Namespace `io.github.rgquiet` automatically verified
- Generated user token for deployment credentials

### 3. Maven Settings Configuration

Created `~/.m2/private_settings.xml` with:
- Server credentials (id: `central`)
- GPG configuration (keyname, passphrase)
- Active profile for GPG signing

## POM Configuration

### Parent POM (`pom.xml`)

Key configurations:
1. **GroupId changed** from `com.openhtmltopdf` to `io.github.rgquiet.openhtmltopdf`
2. **ArtifactId changed** from `openhtmltopdf-parent` to `openhtmltopdf`
3. **Added central-publishing-maven-plugin** (version 0.9.0)
   - `publishingServerId`: central
   - `autoPublish`: true
4. **Removed distributionManagement** section (not needed with new plugin)
5. **Configured GPG plugin** in release profile

### Child Module POMs

All 12 child modules updated:
1. Parent reference updated to new groupId/artifactId
2. Version updated to 1.0.11
3. **Critical**: Removed conflicting `distributionManagement` sections

## Common Issues Encountered and Solutions

### Issue 1: 401 Unauthorized Error

**Cause**: Child modules had their own `distributionManagement` sections pointing to old OSSRH URLs with `id=ossrh`, which conflicted with the parent's central-publishing-maven-plugin configuration.

**Solution**: Removed all `distributionManagement` sections from child POMs. The central-publishing-maven-plugin in the parent POM handles all deployments.

### Issue 2: Wrong Server ID

**Cause**: Settings.xml had `id=${server}` instead of `id=central`.

**Solution**: Changed server ID to `central` to match the `publishingServerId` in the plugin configuration.

### Issue 3: Parent Reference Mismatch

**Cause**: Child POMs referenced old parent groupId `com.openhtmltopdf` and artifactId `openhtmltopdf-parent`.

**Solution**: Updated all child POMs using sed commands:
```bash
sed -i '' 's|<groupId>com.openhtmltopdf</groupId>|<groupId>io.github.rgquiet.openhtmltopdf</groupId>|g' pom.xml
sed -i '' 's|<artifactId>openhtmltopdf-parent</artifactId>|<artifactId>openhtmltopdf</artifactId>|g' pom.xml
```

## Deployment Process

### Command
```bash
mvn clean deploy -P release -s ~/.m2/private_settings.xml
```

### What Happens
1. Builds all 13 modules
2. Runs tests
3. Generates sources and javadoc JARs
4. Signs all artifacts with GPG (via release profile)
5. Uploads to Maven Central via central-publishing-maven-plugin
6. Auto-publishes to Maven Central (typically takes 15-30 minutes)

### First Successful Deployment
- Date: 2026-01-13
- Version: 1.0.11
- Deployment ID: 50602863-eb9c-45e5-ad36-405b4fe9c075
- Status: Published successfully
- All 14 components validated

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
