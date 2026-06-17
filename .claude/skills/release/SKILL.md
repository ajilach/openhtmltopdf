---
name: release
description: Publish a new version of this library (openhtmltopdf) to Maven Central. Use when the user wants to cut, ship, publish, or release a new version. Drives the whole release end-to-end — version bump, commit, push to master, triggering the GitHub Actions "release" workflow, and monitoring it to confirmation. Optionally pass the target version as an argument, e.g. "/release 1.0.20".
---

# Release to Maven Central

This project publishes to Maven Central through the **`release` GitHub Actions
workflow** (`.github/workflows/release.yml`). That workflow builds all modules,
GPG-signs them and uploads them via the `central-publishing-maven-plugin`
(`autoPublish=true`), using repository secrets — no local credentials needed.

This skill performs a complete release autonomously. Work through the steps in
order. **Stop and report** the moment anything looks wrong — a published Maven
Central version is immutable and can never be changed or removed.

## Preconditions — verify first, abort with a clear message if any fail

1. `gh` CLI is authenticated: `gh auth status`
2. The account has write access:
   `gh repo view ajilach/openhtmltopdf --json viewerPermission`
   → must be `WRITE`, `MAINTAIN`, or `ADMIN`.
3. The working tree is clean (an untracked `.DS_Store` is fine):
   `git status --porcelain`. If there are uncommitted changes unrelated to the
   release, abort and tell the user.

## Step 1 — Determine the target version

- The version lives in the parent `pom.xml` as `<revision>X.Y.Z</revision>`.
- If the user passed a version (e.g. `/release 1.0.20`), use it.
- Otherwise read the current `<revision>` and propose the next **patch** version
  (e.g. `1.0.19` → `1.0.20`), then **confirm with the user before publishing**.
- The new version MUST be strictly greater than the current one and must never
  reuse an already-published version (Maven Central is immutable).

## Step 2 — Sync branches

Releases go out from `master`; `develop` and `master` are kept in lockstep.

```bash
git fetch origin
# master must NOT be ahead of local develop, otherwise a fast-forward is unsafe:
git rev-list --count develop..origin/master   # must print 0
```

If it prints non-zero, abort and ask the user how to reconcile the branches.
Also make sure you are on `develop` (or fast-forward it to `origin/develop`).

## Step 3 — Bump the version

Edit only the parent `pom.xml` (child modules inherit via `${revision}`):
set `<revision>` to the target version.

## Step 4 — Commit and push to develop + master

```bash
git add pom.xml
git commit -m "Prepared revision version <NEW_VERSION>"
git push origin develop
git push origin develop:master      # fast-forwards master to the same commit
```

(Follow the repo's commit-trailer convention if one applies.)

## Step 5 — Trigger the release workflow

```bash
gh workflow run release.yml --ref master --repo ajilach/openhtmltopdf
```

`gh workflow run` does not print the run id. Get it right after triggering:

```bash
sleep 5
gh run list --workflow release.yml --repo ajilach/openhtmltopdf \
  --branch master --limit 1 --json databaseId,url --jq '.[0]'
```

## Step 6 — Monitor until completion

Poll until the run finishes (run this in the background so you can report when
done):

```bash
RID=<databaseId>
until [ "$(gh run view "$RID" --repo ajilach/openhtmltopdf --json status --jq .status)" = "completed" ]; do sleep 20; done
gh run view "$RID" --repo ajilach/openhtmltopdf --json conclusion --jq .conclusion
```

- **On `success`** — confirm the upload reached Central and was validated:
  ```bash
  gh run view "$RID" --repo ajilach/openhtmltopdf --log \
    | grep -iE "Uploaded bundle|deploymentId|has been validated"
  ```
  Report the version, the run URL and the `deploymentId`. The deployment then
  auto-publishes (~15 min) at https://central.sonatype.com/publishing. Artifacts
  appear under `io.github.rgquiet.openhtmltopdf:*:<NEW_VERSION>`.

- **On failure** — inspect and report, do not retry blindly:
  ```bash
  gh run view "$RID" --repo ajilach/openhtmltopdf --log-failed | tail -60
  ```
  If the build failed **before** the upload step, nothing was published and the
  version number is still free — fix the cause and re-trigger with the **same**
  version. If it failed **after** a successful upload, the version may be taken;
  check the portal before reusing it.

## Step 7 — Report and offer the tag convention

Summarize what was published. The repo's older convention also creates a
`<NEW_VERSION>_release` tag — offer to create and push it:

```bash
git tag <NEW_VERSION>_release && git push origin <NEW_VERSION>_release
```

## Notes

- Tests are intentionally skipped in the release workflow (`-DskipTests`): the
  visual regression tests in `openhtmltopdf-examples` are font/OS-sensitive and
  fail on the Linux runner although the artifacts are correct. The separate
  `build` workflow is the test gate.
- Never print secret values. The four required secrets
  (`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_GPG_PRIVATE_KEY`,
  `MAVEN_GPG_PASSPHRASE`) are already configured on the repository.
- Full reference: `DEPLOYMENT.md` in the repo root.
