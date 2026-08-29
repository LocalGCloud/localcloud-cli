#!/bin/sh
set -eu

SOURCE_REPO="LocalGCloud/localcloud-cli"
SOURCE_OWNER=${SOURCE_REPO%%/*}
SOURCE_NAME=${SOURCE_REPO#*/}
TAP_REPO="LocalGCloud/homebrew-tap"
SOURCE_REMOTE="origin"
SOURCE_BRANCH="main"
RELEASE_WORKFLOW="cli-release.yml"
TAP_WORKFLOW="publish-formula.yml"

usage() {
    cat <<'EOF'
Usage:
  ./scripts/release.sh
  ./scripts/release.sh --build-only
  ./scripts/release.sh [-f|--force] --release VERSION

Modes:
  --build-only       Build and smoke-test for the current platform.
  --release VERSION  Publish a prepared X.Y.Z version through GitHub Actions.

Options:
  -f, --force        Replace a release and retarget conflicting tags to HEAD.

With no arguments, the default: build for the current platform.
EOF
}

usage_error() {
    printf 'error: %s\n\n' "$1" >&2
    usage >&2
    exit 2
}

fail() {
    printf 'error: %s\n' "$1" >&2
    exit 1
}

stage() {
    printf '\n==> %s\n' "$1"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

is_version() {
    case $1 in
        '' | *[!0-9.]* | .* | *. | *..* | *.*.*.*)
            return 1
            ;;
        *.*.*)
            version_major=${1%%.*}
            version_remainder=${1#*.}
            version_minor=${version_remainder%%.*}
            version_patch=${version_remainder#*.}
            for version_part in \
                "$version_major" "$version_minor" "$version_patch"; do
                case $version_part in
                    0 | [1-9]*) ;;
                    *) return 1 ;;
                esac
            done
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

MODE="build"
VERSION=""
FORCE_RELEASE="false"
BUILD_ONLY="false"

while [ "$#" -gt 0 ]; do
    case $1 in
        --build-only)
            [ "$BUILD_ONLY" = "false" ] && [ -z "$VERSION" ] ||
                usage_error "--build-only cannot be combined with other modes"
            BUILD_ONLY="true"
            shift
            ;;
        --release)
            [ "$BUILD_ONLY" = "false" ] && [ -z "$VERSION" ] ||
                usage_error "--release cannot be combined with other modes"
            [ "$#" -ge 2 ] || usage_error "--release requires VERSION"
            is_version "$2" ||
                usage_error "release version must have the form X.Y.Z"
            MODE="release"
            VERSION=$2
            shift 2
            ;;
        -f | --force)
            [ "$FORCE_RELEASE" = "false" ] ||
                usage_error "--force may be specified only once"
            FORCE_RELEASE="true"
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage_error "unknown argument: $1"
            ;;
    esac
done

if [ "$FORCE_RELEASE" = "true" ] && [ "$MODE" != "release" ]; then
    usage_error "--force requires --release VERSION"
fi

SCRIPT_DIR=$(CDPATH='' cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(CDPATH='' cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_ROOT"

TEMP_DIR=""
APPROVED_TREE_STATUS=""
TREE_STATUS_APPROVED="false"
cleanup() {
    if [ -n "$TEMP_DIR" ] && [ -d "$TEMP_DIR" ]; then
        rm -rf "$TEMP_DIR"
    fi
}
trap cleanup 0 1 2 15

validate_locked_inputs() {
    stage "Validate locked release inputs"
    uv lock --check

    TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/localcloud-release.XXXXXX")
    notice_output="$TEMP_DIR/THIRD_PARTY_NOTICES"
    uv run --frozen --extra release python scripts/generate-third-party-notices.py \
        --output "$notice_output"
    if ! cmp -s THIRD_PARTY_NOTICES "$notice_output"; then
        fail "THIRD_PARTY_NOTICES is stale; regenerate and commit it"
    fi
}

require_build_commands() {
    require_command uv
    require_command cmp
    require_command install
    require_command mktemp
}

build_native_executable() {
    stage "Build native one-folder bundle"
    uv run --frozen --extra release python -m PyInstaller \
        --clean --noconfirm localcloud.spec
    install -m 0755 scripts/localcloud-launcher.sh dist/localcloud
}

smoke_native_executable() {
    expected_version=$1

    stage "Smoke native executable"
    actual_version=$(./dist/localcloud --version)
    printf '%s\n' "$actual_version"
    if [ -n "$expected_version" ] &&
        [ "$actual_version" != "localcloud $expected_version" ]; then
        fail "frozen CLI version '$actual_version' does not match $expected_version"
    fi
    ./dist/localcloud --help >/dev/null
    ./dist/localcloud guide >/dev/null
}

build_native() {
    require_build_commands
    validate_locked_inputs
    build_native_executable
    smoke_native_executable ""

    printf '\nBuilt native bundle: %s/dist/localcloud and %s/dist/localcloud-runtime\n' \
        "$PROJECT_ROOT" "$PROJECT_ROOT"
}

confirm_release_tree_state() {
    release_commit=$1
    tree_status=$(git status --porcelain --untracked-files=normal)
    [ -n "$tree_status" ] || return 0

    if [ "$TREE_STATUS_APPROVED" = "true" ] &&
        [ "$tree_status" = "$APPROVED_TREE_STATUS" ]; then
        return 0
    fi

    printf 'warning: working tree is not clean.\n' >&2
    printf 'Published artifacts will be built from commit %s; these local changes are excluded:\n' \
        "$release_commit" >&2
    printf '%s\n' "$tree_status" >&2
    printf 'Release commit %s anyway? [y/N] ' "$release_commit" >&2

    confirmation=
    if ! IFS= read -r confirmation; then
        confirmation=
    fi
    printf '\n' >&2

    case $confirmation in
        y | Y | yes | Yes | YES)
            APPROVED_TREE_STATUS=$tree_status
            TREE_STATUS_APPROVED="true"
            ;;
        *)
            fail "release cancelled"
            ;;
    esac
}

remote_tag_object() {
    remote_tag=$1
    git ls-remote --tags "$SOURCE_REMOTE" "refs/tags/$remote_tag" | cut -f 1
}

remote_tag_commit() {
    remote_tag=$1
    remote_tag_line=$(
        git ls-remote --tags "$SOURCE_REMOTE" "refs/tags/$remote_tag^{}"
    )
    if [ -z "$remote_tag_line" ]; then
        remote_tag_line=$(
            git ls-remote --tags "$SOURCE_REMOTE" "refs/tags/$remote_tag"
        )
    fi
    if [ -n "$remote_tag_line" ]; then
        printf '%s\n' "$remote_tag_line" | cut -f 1
    fi
}

prepare_tag() {
    head_commit=$1
    existing_release=$2
    local_tag_commit=$(
        git rev-parse -q --verify "refs/tags/$TAG^{}" 2>/dev/null || :
    )
    published_tag_object=$(remote_tag_object "$TAG")
    published_tag_commit=$(remote_tag_commit "$TAG")
    TAG_PUSH_REQUIRED="false"
    TAG_FORCE_PUSH="false"
    TAG_REMOTE_EXPECTED=$published_tag_object

    if [ -n "$local_tag_commit" ] && [ "$local_tag_commit" != "$head_commit" ]; then
        [ "$FORCE_RELEASE" = "true" ] ||
            fail "local tag $TAG does not point to the release commit"
    fi
    if [ -n "$published_tag_commit" ] &&
        [ "$published_tag_commit" != "$head_commit" ]; then
        [ "$FORCE_RELEASE" = "true" ] ||
            fail "remote tag $TAG does not point to the release commit"
    fi

    if {
        [ -n "$local_tag_commit" ] && [ "$local_tag_commit" != "$head_commit" ]
    } || {
        [ -n "$published_tag_commit" ] &&
            [ "$published_tag_commit" != "$head_commit" ]
    }; then
        stage "Retarget annotated tag $TAG"
        git tag -fa "$TAG" -m "Release LocalCloud CLI $VERSION" "$head_commit"
        local_tag_commit=$head_commit
        if [ "$published_tag_commit" != "$head_commit" ]; then
            TAG_PUSH_REQUIRED="true"
            if [ -n "$published_tag_commit" ]; then
                TAG_FORCE_PUSH="true"
            fi
        fi
        printf 'Retargeted tag %s to release commit %s\n' "$TAG" "$head_commit"
    fi

    if [ -n "$existing_release" ]; then
        if [ -z "$local_tag_commit" ] || [ -z "$published_tag_commit" ]; then
            fail "GitHub release $TAG exists but its tag is missing locally or on origin"
        fi
        if [ "$TAG_PUSH_REQUIRED" = "false" ]; then
            printf '\nReusing tag %s at %s\n' "$TAG" "$head_commit"
        fi
        return
    fi

    if [ -z "$local_tag_commit" ] && [ -n "$published_tag_commit" ]; then
        fail "remote tag $TAG could not be resolved locally"
    fi

    if [ -z "$local_tag_commit" ]; then
        stage "Create annotated tag $TAG"
        git tag -a "$TAG" -m "Release LocalCloud CLI $VERSION"
    else
        printf '\nReusing tag %s at %s\n' "$TAG" "$head_commit"
    fi
    if [ -z "$published_tag_commit" ]; then
        TAG_PUSH_REQUIRED="true"
    fi
}

dispatch_and_watch() {
    workflow_label=$1
    workflow_file=$2
    workflow_repo=$3
    shift 3

    stage "$workflow_label"
    WORKFLOW_RUN_URL=$(
        gh workflow run "$workflow_file" --repo "$workflow_repo" "$@"
    )
    [ -n "$WORKFLOW_RUN_URL" ] ||
        fail "$workflow_file dispatch did not return a workflow run URL"
    WORKFLOW_RUN_ID=${WORKFLOW_RUN_URL##*/}
    case $WORKFLOW_RUN_ID in
        '' | *[!0-9]*)
            fail "invalid workflow run URL returned: $WORKFLOW_RUN_URL"
            ;;
    esac

    printf 'Workflow run: %s\n' "$WORKFLOW_RUN_URL"
    gh run watch "$WORKFLOW_RUN_ID" --repo "$workflow_repo" --exit-status
}

expected_release_assets() {
    cat <<'EOF'
SHA256SUMS
SHA256SUMS.sigstore.json
localcloud-darwin-amd64.tar.gz
localcloud-darwin-amd64.tar.gz.sigstore.json
localcloud-darwin-arm64.tar.gz
localcloud-darwin-arm64.tar.gz.sigstore.json
localcloud-linux-amd64.tar.gz
localcloud-linux-amd64.tar.gz.sigstore.json
localcloud-linux-arm64.tar.gz
localcloud-linux-arm64.tar.gz.sigstore.json
localcloud.rb
EOF
}

verify_release_assets() {
    stage "Verify published release assets"
    actual_asset_file="$TEMP_DIR/actual-assets.txt"
    expected_asset_file="$TEMP_DIR/expected-assets.txt"
    gh release view "$TAG" --repo "$SOURCE_REPO" \
        --json assets --jq '.assets[].name' >"$actual_asset_file"
    expected_release_assets >"$expected_asset_file"

    actual_assets=$(LC_ALL=C sort "$actual_asset_file")
    expected_assets=$(LC_ALL=C sort "$expected_asset_file")
    if [ "$actual_assets" != "$expected_assets" ]; then
        printf 'Expected assets:\n%s\n\nActual assets:\n%s\n' \
            "$expected_assets" "$actual_assets" >&2
        fail "GitHub release asset set is incomplete or unexpected"
    fi

    RELEASE_URL=$(
        gh release view "$TAG" --repo "$SOURCE_REPO" --json url --jq '.url'
    )
    [ -n "$RELEASE_URL" ] || fail "GitHub release URL is missing"
}

published_release_tag() {
    gh api graphql \
        -f "owner=$SOURCE_OWNER" \
        -f "name=$SOURCE_NAME" \
        -f "tag=$TAG" \
        -f "query=query(\$owner:String!,\$name:String!,\$tag:String!){repository(owner:\$owner,name:\$name){release(tagName:\$tag){tagName}}}" \
        --jq '.data.repository.release.tagName // ""'
}

release_version() {
    require_command git
    require_command gh
    require_command sort
    require_build_commands

    TAG="v$VERSION"

    stage "Validate prepared release source"
    current_branch=$(git branch --show-current)
    [ "$current_branch" = "$SOURCE_BRANCH" ] ||
        fail "release must run from branch $SOURCE_BRANCH"

    gh auth status --hostname github.com >/dev/null
    if [ "$FORCE_RELEASE" = "true" ]; then
        git fetch "$SOURCE_REMOTE" "$SOURCE_BRANCH"
        local_tag_object=$(
            git rev-parse -q --verify "refs/tags/$TAG" 2>/dev/null || :
        )
        if [ -z "$local_tag_object" ]; then
            published_tag_object=$(remote_tag_object "$TAG")
            if [ -n "$published_tag_object" ]; then
                git fetch "$SOURCE_REMOTE" \
                    "refs/tags/$TAG:refs/tags/$TAG"
            fi
        fi
    else
        git fetch "$SOURCE_REMOTE" "$SOURCE_BRANCH" --tags
    fi
    head_commit=$(git rev-parse HEAD)
    remote_commit=$(git rev-parse "$SOURCE_REMOTE/$SOURCE_BRANCH")
    [ "$head_commit" = "$remote_commit" ] ||
        fail "HEAD must equal $SOURCE_REMOTE/$SOURCE_BRANCH before release"
    confirm_release_tree_state "$head_commit"

    committed_version_source=$(
        git show "$head_commit:src/localcloud_cli/__init__.py"
    ) || fail "source version file is missing from release commit $head_commit"
    source_version=$(
        printf '%s\n' "$committed_version_source" |
            sed -n 's/^__version__ = "\([^"]*\)"$/\1/p'
    )
    [ -n "$source_version" ] ||
        fail "source version is missing from release commit $head_commit"
    [ "$source_version" = "$VERSION" ] ||
        fail "source version $source_version does not match $VERSION"

    validate_locked_inputs

    stage "Run release tests"
    uv run --frozen --extra test python -m pytest -q -m "not docker"

    build_native_executable
    smoke_native_executable "$VERSION"
    confirm_release_tree_state "$head_commit"

    existing_release=$(published_release_tag)
    prepare_tag "$head_commit" "$existing_release"

    CLI_RUN_URL=
    if [ -z "$existing_release" ] || [ "$TAG_PUSH_REQUIRED" = "true" ]; then
        stage "Push prepared source and tag"
        git push "$SOURCE_REMOTE" "$SOURCE_BRANCH"
        if [ "$TAG_FORCE_PUSH" = "true" ]; then
            git push "$SOURCE_REMOTE" \
                --force-with-lease="refs/tags/$TAG:$TAG_REMOTE_EXPECTED" \
                "refs/tags/$TAG"
        else
            git push "$SOURCE_REMOTE" "refs/tags/$TAG"
        fi
    fi

    if [ -z "$existing_release" ] || [ "$FORCE_RELEASE" = "true" ]; then
        if [ -n "$existing_release" ]; then
            printf '\nReplacing existing GitHub release %s; preserving its tag\n' "$TAG"
        fi
        dispatch_and_watch \
            "Build and publish four native CLI archives" \
            "$RELEASE_WORKFLOW" "$SOURCE_REPO" \
            --ref "$TAG" -f "force=$FORCE_RELEASE"
        CLI_RUN_URL=$WORKFLOW_RUN_URL
    else
        printf '\nReusing existing GitHub release %s\n' "$TAG"
    fi

    verify_release_assets

    dispatch_and_watch \
        "Publish Homebrew formula" \
        "$TAP_WORKFLOW" "$TAP_REPO" -f "version=$VERSION"
    TAP_RUN_URL=$WORKFLOW_RUN_URL

    printf '\nRelease %s completed.\n' "$VERSION"
    printf 'CLI release: %s\n' "$RELEASE_URL"
    if [ -n "$CLI_RUN_URL" ]; then
        printf 'CLI workflow: %s\n' "$CLI_RUN_URL"
    else
        printf 'CLI workflow: skipped (verified existing release %s)\n' "$TAG"
    fi
    printf 'Tap workflow: %s\n' "$TAP_RUN_URL"
    printf '\nPublic-channel checks:\n'
    printf '  gh release view %s --repo %s\n' "$TAG" "$SOURCE_REPO"
    printf '  brew update\n'
    printf '  brew info LocalGCloud/tap/localcloud\n'
    printf '  brew test LocalGCloud/tap/localcloud\n'
}

if [ "$MODE" = "build" ]; then
    build_native
else
    release_version
fi
