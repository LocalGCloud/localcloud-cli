# Release LocalCloud CLI

This is the operator runbook for publishing a LocalCloud CLI release. The release workflow is manual-only. It does not build, publish, or depend on the LocalCloud Docker image being present on Docker Hub. The CLI always resolves the runtime image by the mutable `jaysen2apache/localcloud:latest` tag at run time (see `DEFAULT_IMAGE` in `src/localcloud_cli/config.py`), never a pinned digest, so users always get the newest qualified image without needing to update the CLI itself.

## Access required

You need admin access to:

- `LocalGCloud/localcloud-cli`
- `LocalGCloud/homebrew-tap`

No cross-repository token is required. Each manual workflow writes only to its own repository using that repository's `GITHUB_TOKEN`.

## 1. Publish the Docker runtime (independent of CLI releases)

Publishing and qualifying `jaysen2apache/localcloud:latest` is managed entirely
through the private LocalCloud repository, on its own schedule. It is not a
prerequisite for a CLI release: the CLI release workflow no longer inspects or
requires Docker Hub state, because the shipped CLI always pulls the runtime
image by the mutable `:latest` tag rather than a pinned digest.

```sh
VERSION=0.1.0
gh workflow enable docker-publish.yml --repo jhsenjaliya/localcloud
gh workflow run docker-publish.yml \
  --repo jhsenjaliya/localcloud \
  --ref "v${VERSION}"
```

Do not enable the workflow until its manual-only trigger is present on the
private repository's default branch. Let the run finish to completion (do not
cancel it) so `docker-publish.yml`'s own platform qualification gate promotes
`latest` atomically as a multi-arch (`linux/amd64` + `linux/arm64`) image.

You can independently confirm the public image's architecture coverage and
ownership label at any time:

```sh
docker buildx imagetools inspect jaysen2apache/localcloud:latest
test "$(docker image inspect \
  --format '{{ index .Config.Labels "com.localcloud.runtime-ownership" }}' \
  jaysen2apache/localcloud:latest)" = "data-volume-v1"
```

An older CLI can ignore new labels; a new CLI may attach to a compatible older
container but must not create or replace a managed runtime from an image
without the capability label. Because the CLI resolves `latest` at run time,
keep the published image itself backward compatible with CLI versions still in
the field.

## 2. Prepare the CLI source

In `LocalGCloud/localcloud-cli`:

1. Set `__version__` in `src/localcloud_cli/__init__.py` to the release version.
2. Update `uv.lock` if package metadata or dependencies changed.
3. Regenerate `THIRD_PARTY_NOTICES` when locked dependencies changed.
4. Review, commit, and push the prepared source to `main`.

Release automation never edits or commits tracked files. It requires a clean
`main` whose `HEAD` already equals `origin/main`.

To build and smoke-test a pre-extracted one-folder bundle for the current host
without publishing anything:

```sh
./scripts/release.sh --build-only
```

Running `./scripts/release.sh` without arguments is equivalent. This build is
native: Apple Silicon macOS produces a Darwin ARM64 executable, not Linux or
AMD64 artifacts.

## 3. Run the automated release

After the prepared CLI source is committed and pushed:

```sh
VERSION=0.1.0
./scripts/release.sh --release "$VERSION"
```

The script:

- validates the clean branch, remote revision, version, lockfile, and notices;
- runs the complete test suite and a native frozen-binary smoke test;

  Because this local preflight includes tests marked `docker`, Docker must be
  reachable, a `jaysen2apache/localcloud:latest` image must be present locally,
  and a compatible `localcloud-data` runtime must already be running and
  operational. Its built-in `local-project` fixture must retain the seeded GCS
  buckets and BigQuery datasets. The release tests attach to that runtime and
  perform only read-only operational and seeded-data checks.
- creates and pushes the annotated `v${VERSION}` tag for a new release, or
  verifies and reuses the matching local and `origin` tag for an existing
  release;
- dispatches and watches `cli-release.yml` only when the GitHub release does
  not already exist;
- verifies the exact archive, checksum, formula, and Sigstore asset set; and
- dispatches and watches the idempotent Homebrew tap publisher.

The GitHub workflow remains responsible for native macOS and Linux builds on
ARM64 and AMD64 runners. The local script does not cross-compile. A matching
tag may be reused after a failed workflow. If a complete GitHub release already
exists, the script requires its local and `origin` tags to resolve to the
current release commit, skips the duplicate CLI workflow, and resumes Homebrew
publication. Conflicting tags and published release assets are never
overwritten.

## 4. Manual recovery commands

The automation above is the primary release path. After an interrupted run,
the supported recovery is to rerun the same command while `main`,
`origin/main`, the source version, and both tag refs still identify the release
commit:

```sh
VERSION=0.1.0
./scripts/release.sh --release "$VERSION"
```

This repeats the local preflight. When a matching GitHub release already
exists, it verifies the complete asset set, reuses the immutable release,
skips the duplicate CLI build/publish workflow, and dispatches the idempotent
Homebrew publisher. If `main` has advanced since the CLI release, use the
direct tap command below rather than weakening the source and tag checks.

The underlying commands are retained for low-level diagnosis or recovery.
Rerunning `cli-release.yml` for an already published tag is intentionally
rejected; only dispatch it when the GitHub release does not exist:

```sh
VERSION=0.1.0

uv lock --check
test "$(uv run --frozen lc --version)" = "localcloud ${VERSION}"
uv run --frozen --extra test python -m pytest
uv run --frozen --extra release python scripts/generate-third-party-notices.py
git diff --exit-code -- THIRD_PARTY_NOTICES
uv run --frozen --extra release python -m PyInstaller --clean --noconfirm localcloud.spec
install -m 0755 scripts/localcloud-launcher.sh dist/localcloud
uv run --frozen python scripts/check-startup-feedback.py dist/localcloud --timeout 2.0
test "$(./dist/localcloud --version)" = "localcloud ${VERSION}"
./dist/localcloud --help >/dev/null
./dist/localcloud guide >/dev/null

git tag -a "v${VERSION}" -m "Release LocalCloud CLI ${VERSION}"
git push origin main
git push origin "v${VERSION}"

# Only run this workflow when v${VERSION} has no GitHub release.
cli_run_url=$(gh workflow run cli-release.yml \
  --repo LocalGCloud/localcloud-cli \
  --ref "v${VERSION}")
gh run watch "${cli_run_url##*/}" \
  --repo LocalGCloud/localcloud-cli \
  --exit-status

tap_run_url=$(gh workflow run publish-formula.yml \
  --repo LocalGCloud/homebrew-tap \
  -f "version=${VERSION}")
gh run watch "${tap_run_url##*/}" \
  --repo LocalGCloud/homebrew-tap \
  --exit-status
```

`cli-release.yml` verifies the selected tag, runs source validation with
`pytest -m "not docker"`, builds all four native archives, and creates the
GitHub release. It does not pull or otherwise depend on Docker Hub state — the
release notes cite the runtime image by its mutable `:latest` tag, and no job
in the workflow requires Docker. Release creation is fail-closed: rerunning
the workflow for an already published tag is intentionally rejected rather
than replacing its immutable assets. The tap workflow then downloads,
installs, tests, and commits the published formula; when the formula is already
identical, it makes no commit.

## 5. Verify public channels

```sh
VERSION=0.1.0
gh release view "v${VERSION}" --repo LocalGCloud/localcloud-cli
brew update
brew info LocalGCloud/tap/localcloud
brew install LocalGCloud/tap/localcloud
brew test LocalGCloud/tap/localcloud
localcloud_version=$(localcloud --version) &&
lc_version=$(lc --version) &&
test "$localcloud_version" = "localcloud ${VERSION}" &&
test "$lc_version" = "$localcloud_version"
lc doctor
```

The fully qualified `brew install LocalGCloud/tap/localcloud` command
automatically taps the third-party repository when necessary. For an existing
tap checkout, run `brew update` first so Homebrew fetches the latest formula
commit before `brew info` or installation.

Confirm that the release contains these files:

- `localcloud-darwin-arm64.tar.gz`
- `localcloud-darwin-amd64.tar.gz`
- `localcloud-linux-arm64.tar.gz`
- `localcloud-linux-amd64.tar.gz`
- `SHA256SUMS`
- `localcloud.rb`
- one `.sigstore.json` bundle for each archive and for `SHA256SUMS`

Deploy the website installer only after the GitHub release and Homebrew formula resolve publicly. Then verify `https://local.cloud/install.sh` on a clean supported host.

## 6. Troubleshooting

**`error: source version X does not match Y`**

Step 2 was skipped or incomplete: `__version__` in `src/localcloud_cli/__init__.py`
still holds the last released version, not the one you're releasing. Bump it
(step 2), commit, and push to `main` before retrying.

If you've already done that and the error persists, check for a stale local
tag from a previous interrupted release attempt:

```sh
git rev-parse -q --verify "refs/tags/v${VERSION}^{}"
```

`prepare_tag` reuses a local `v${VERSION}` tag only if it already points at
`HEAD`. A tag left over from an attempt that failed *before* the source was
ever bumped and pushed points at an older commit, and blocks tag creation on
the next run. Since it was never pushed to `origin` (confirm with
`git ls-remote --tags origin "refs/tags/v${VERSION}"`) and no GitHub release
exists for it, it's safe to delete and let the script recreate it at the
correct commit:

```sh
git tag -d "v${VERSION}"
```
