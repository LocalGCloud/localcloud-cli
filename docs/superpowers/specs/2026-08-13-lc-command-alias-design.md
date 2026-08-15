# `lc` Command Alias Design

## Goal

Make routine LocalCloud commands faster to type by installing `lc` as a short alias for `localcloud`. Preserve `localcloud` as the canonical executable and product identity so existing scripts, documentation links, release artifacts, MCP clients, and troubleshooting workflows continue to work without migration.

The user-facing contract is:

> `lc` is an alias for `localcloud`; both commands behave identically.

## Command behavior

`localcloud` remains the only CLI implementation. Installed `lc` launchers resolve to the same entry point or executable and must not introduce argument rewriting, environment changes, output changes, or alias-specific branches.

Both names therefore accept the same arguments and have identical exit codes, stdin handling, stdout and stderr ownership, configuration lookup, and side effects. The displayed product identity remains canonical regardless of the invoked filename:

```text
$ lc --version
localcloud 0.1.0
```

Top-level help must include the alias statement. Usage text continues to use `localcloud`; subcommand help does not need duplicate alias notices.

New human-oriented examples should prefer `lc`, including the README quick start, installer next steps, and Homebrew caveats. Machine-facing and stable identifiers remain `localcloud`, including:

- the Python distribution and implementation package;
- the PyInstaller executable and release archive names;
- generated MCP `command` values;
- configuration, state, container, network, and volume names;
- release verification and troubleshooting references where the canonical name removes ambiguity.

## Distribution design

### Standalone installer

`LocalStack-Google/localcloud-site/public/install.sh` continues to download, verify, and install the signed `localcloud` executable. Only after the canonical binary is installed successfully does it attempt to create the relative symlink:

```text
$install_dir/lc -> localcloud
```

A relative link keeps an installation movable within its directory and avoids embedding a user-specific path.

The managed-install marker records whether the installer created the alias. A present alias is treated as managed only when the marker records that ownership and the destination is still a symlink whose target is exactly `localcloud`. A recorded alias that is later missing may be recreated safely. Installation and upgrade follow these rules:

1. If the marker records ownership and the managed alias exists with the expected target, retain it.
2. If the marker records ownership but the alias is missing, recreate it when no unrelated `lc` command or destination conflicts.
3. If `$install_dir/lc` exists with any other type or target, preserve it and warn.
4. If `lc` already resolves to an unrelated command elsewhere on `PATH`, do not create a new alias that could shadow it; preserve it and warn.
5. A conflict never prevents successful installation or upgrade of `localcloud`.

Warnings must identify the conflicting command or path and state that `localcloud` remains available. They must not suggest force-overwriting another program.
A skipped alias is not recorded as installer-managed.

Uninstall removes `lc` only when the marker records installer ownership and the link still targets `localcloud`. A missing, changed, or unowned path is preserved. The canonical binary and existing PATH-block cleanup retain their current ownership checks.

### Homebrew

The generated formula installs the canonical `localcloud` executable and creates `lc` as a symlink in the same keg. The formula must not use forced linking or overwrite a command owned by another keg. Homebrew's normal link-conflict behavior protects an existing `lc` installation and reports the collision.

Formula caveats state that `lc` is an alias and both names behave identically. The formula test invokes both commands and requires identical version output.

### Python and development installs

`pyproject.toml` exposes both console-script names, each mapped to `localcloud_cli.cli:main`. Python packaging may materialize these as separate launcher files rather than a filesystem symlink, but both launchers use one implementation and honor the same behavioral contract.

This also makes `uv run lc` available during development. The project does not promise to override a conflicting console script installed by another Python distribution; environment isolation and package-manager behavior remain authoritative.

### Release archives

Native release archives keep their current security boundary and contents:

- `localcloud`
- `LICENSE`
- `THIRD_PARTY_NOTICES`

The archives do not include `lc`. The official standalone installer and Homebrew formula create the alias after verifying and installing the canonical artifact. Manual archive consumers may create `lc -> localcloud` themselves.

## Documentation and messaging

The exact alias statement appears in:

- top-level CLI help;
- the README installation or quick-start section;
- standalone installer success and next-step output;
- Homebrew caveats.

Human command examples in those surfaces use `lc` after first establishing the relationship. Documentation must not imply that `localcloud` is deprecated or that `lc` is guaranteed when installation skipped it because of a collision.

Generated MCP configuration and the coding-agent guide continue using `localcloud`. This avoids making automation depend on a convenience alias that may be unavailable on a host with a name collision.

## Error handling and ownership

The feature must prefer preserving unrelated user state over guaranteeing that `lc` is present. `localcloud` is always the recovery command.

The standalone installer must distinguish these outcomes in its output:

- alias installed or already managed;
- alias repaired during upgrade;
- alias skipped because an unrelated command or destination exists;
- alias preserved during uninstall because ownership or target validation failed.

No code path may copy the executable to `lc`, replace an existing non-managed path, mutate a shell's alias configuration, or add shell-specific startup commands for the alias.

## Verification

### CLI repository

Add focused coverage that verifies:

- both Python console-script names map to the same `main()` function;
- top-level help contains the exact alias statement;
- `lc --version` and `localcloud --version` produce identical canonical output through installed entry points;
- the generated Homebrew formula installs an `lc` symlink without force-linking;
- the formula test exercises both names and expects `localcloud <version>` from each;
- release archives retain exactly the three canonical files;
- existing CLI behavior tests continue to pass unchanged through the canonical entry point.

The frozen-binary smoke test remains centered on `dist/localcloud`, because PyInstaller still emits one canonical executable.

### Website installer repository

Cover these installer transitions in isolated temporary directories and PATH values:

1. Fresh installation creates a relative `lc -> localcloud` link.
2. Re-running the same version retains the managed link.
3. Upgrade repairs a missing managed link.
4. An unrelated regular file at `$install_dir/lc` is unchanged and triggers a warning.
5. An unrelated symlink at `$install_dir/lc` is unchanged and triggers a warning.
6. An unrelated `lc` elsewhere on `PATH` is not shadowed and triggers a warning.
7. Uninstall removes an owned link with the expected target.
8. Uninstall preserves changed, missing, and unowned alias destinations.
9. A skipped or preserved alias never prevents `localcloud --version` from succeeding.

### Release verification

Homebrew release verification must confirm that both installed commands resolve and return identical version output. Standalone installer verification must check the link target as well as command behavior. No test should require `lc` when a deliberate collision fixture is present.

## Rollout

This is a coordinated change across:

1. `LocalGCloud/localcloud-cli`, which defines CLI messaging, Python entry points, documentation, and the generated Homebrew formula.
2. `LocalStack-Google/localcloud-site`, which owns `public/install.sh` and its installer tests.

Publish the CLI release and formula changes through the existing signed release process. Deploy the website installer change only after its collision, upgrade, and uninstall tests pass. The installer can add the alias to supported existing CLI releases because the alias targets the unchanged canonical executable.

## Non-goals

- Renaming or deprecating `localcloud`.
- Building or shipping a second frozen executable.
- Adding alias-specific CLI behavior or output.
- Changing release asset, package, configuration, or runtime resource names.
- Force-claiming `lc` when another tool already owns it.
- Making generated MCP configuration depend on `lc`.
- Adding aliases beyond `lc`.
