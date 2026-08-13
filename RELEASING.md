# Release LocalCloud CLI

This is the operator runbook for publishing a LocalCloud CLI release. The release workflow is manual-only. It does not build or publish the LocalCloud Docker image; it pulls and tests the existing `jaysen2apache/localcloud:latest` image.

## Access required

You need admin access to:

- `LocalGCloud/localcloud-cli`
- `LocalGCloud/homebrew-tap`

No cross-repository token is required. Each manual workflow writes only to its own repository using that repository's `GITHUB_TOKEN`.

## 1. Prepare the Docker runtime

Publish and qualify `jaysen2apache/localcloud:latest` using the private LocalCloud repository before releasing the CLI. This CLI workflow will not trigger that build.

The private `.github/workflows/docker-publish.yml` workflow is manual-only and is currently disabled. After the reviewed LocalCloud source and release tag are pushed, enable and dispatch it yourself:

```sh
VERSION=0.1.0
gh workflow enable docker-publish.yml --repo jhsenjaliya/localcloud
gh workflow run docker-publish.yml \
  --repo jhsenjaliya/localcloud \
  --ref "v${VERSION}"
```

Do not enable the workflow until its manual-only trigger is present on the private repository's default branch.

Confirm that the public image has both supported Linux architectures:

```sh
docker buildx imagetools inspect jaysen2apache/localcloud:latest
```

The output must include `linux/amd64` and `linux/arm64` manifests.

## 2. Prepare the CLI version

In `LocalGCloud/localcloud-cli`:

1. Set `__version__` in `src/localcloud_cli/__init__.py` to the release version.
2. Update `uv.lock` if package metadata or dependencies changed.
3. Regenerate and verify third-party notices.
4. Run the release tests and a frozen-binary smoke test.

For version `0.1.0`:

```sh
uv lock --check
uv run --frozen --extra test python -m pytest
uv run --frozen --extra release python scripts/generate-third-party-notices.py
git diff --exit-code -- THIRD_PARTY_NOTICES
uv run --frozen --extra release python -m PyInstaller --clean --noconfirm localcloud.spec
test "$(./dist/localcloud --version)" = "localcloud 0.1.0"
./dist/localcloud --help >/dev/null
./dist/localcloud guide >/dev/null
```

Commit and push the reviewed CLI changes. Confirm the working tree is clean before tagging.

## 3. Create the release tag

The tag, package version, and requested release version must match exactly:

```sh
VERSION=0.1.0
git tag -a "v${VERSION}" -m "Release LocalCloud CLI ${VERSION}"
git push origin main
git push origin "v${VERSION}"
```

Pushing the tag does not start the workflow.

## 4. Run the release automation

Dispatch the workflow explicitly from the tag:

```sh
gh workflow run cli-release.yml \
  --repo LocalGCloud/localcloud-cli \
  --ref "v${VERSION}"
```

Watch the run in GitHub Actions or with `gh run watch --repo LocalGCloud/localcloud-cli --exit-status`.

The workflow:

- verifies the selected ref is an existing `v0.1.0` tag matching `localcloud_cli.__version__`;
- resolves and records the exact digest behind `jaysen2apache/localcloud:latest`;
- runs the CLI test suite;
- builds native macOS and Linux archives for ARM64 and AMD64;
- smoke-tests the frozen binaries and the Docker-backed lifecycle;
- publishes SHA-256 checksums and keyless Sigstore bundles;
- creates the GitHub release, including the generated `localcloud.rb` formula asset.

After that run succeeds, dispatch the tap's own publisher:

```sh
gh workflow run publish-formula.yml \
  --repo LocalGCloud/homebrew-tap \
  -f "version=${VERSION}"
```

The tap workflow downloads the formula from the public CLI release, installs and tests it on macOS, then commits it to `LocalGCloud/homebrew-tap`. Do not recreate or overwrite a published CLI release.

## 5. Verify public channels

```sh
gh release view "v${VERSION}" --repo LocalGCloud/localcloud-cli
brew update
brew info LocalGCloud/tap/localcloud
brew install LocalGCloud/tap/localcloud
brew test LocalGCloud/tap/localcloud
localcloud --version
localcloud doctor
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
