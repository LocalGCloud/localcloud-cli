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

To build and smoke-test a one-file executable for the current host without
publishing anything:

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
- creates and pushes the annotated `v${VERSION}` tag;
- dispatches and watches `cli-release.yml` for that tag;
- verifies the exact archive, checksum, formula, and Sigstore asset set; and
- dispatches and watches the Homebrew tap publisher.

The GitHub workflow remains responsible for native macOS and Linux builds on
ARM64 and AMD64 runners. The local script does not cross-compile. A matching
existing tag may be reused after a failed workflow only while no GitHub release
exists; conflicting tags and published releases are never overwritten.

## 4. Manual recovery commands

The automation above is the primary release path. These underlying commands are
retained for diagnosing or recovering an interrupted release:

```sh
VERSION=0.1.0

uv lock --check
test "$(uv run --frozen lc --version)" = "localcloud ${VERSION}"
uv run --frozen --extra test python -m pytest
uv run --frozen --extra release python scripts/generate-third-party-notices.py
git diff --exit-code -- THIRD_PARTY_NOTICES
uv run --frozen --extra release python -m PyInstaller --clean --noconfirm localcloud.spec
test "$(./dist/localcloud --version)" = "localcloud ${VERSION}"
./dist/localcloud --help >/dev/null
./dist/localcloud guide >/dev/null

git tag -a "v${VERSION}" -m "Release LocalCloud CLI ${VERSION}"
git push origin main
git push origin "v${VERSION}"

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
in the workflow requires Docker. The tap workflow then downloads, installs,
tests, and commits the published formula. Do not recreate or overwrite an
existing CLI release.

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

Confirm that the release contains these files:

- `localcloud-darwin-arm64.tar.gz`
- `localcloud-darwin-amd64.tar.gz`
- `localcloud-linux-arm64.tar.gz`
- `localcloud-linux-amd64.tar.gz`
- `SHA256SUMS`
- `localcloud.rb`
- one `.sigstore.json` bundle for each archive and for `SHA256SUMS`

Deploy the website installer only after the GitHub release and Homebrew formula resolve publicly. Then verify `https://local.cloud/install.sh` on a clean supported host.
