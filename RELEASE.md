# Branching & Release Strategy

## Long-lived branches

| Branch | Purpose |
|--------|---------|
| `main` | Stable, released code only. Tagged with `vX.Y.Z` on each release. |
| `develop` | Integration branch. All feature work merges here first. |

## Short-lived branches

| Branch pattern | Purpose | Merges into |
|----------------|---------|-------------|
| `feature/<name>` | New features | `develop` |
| `fix/<name>` | Bug fixes | `develop` (or `main` for hotfixes) |
| `release/X.Y.Z` | Release prep (version bump, changelog, final testing) | `main` + back-merge to `develop` |
| `hotfix/X.Y.Z` | Critical prod fixes | `main` + back-merge to `develop` |

## Release versioning

```
0.MAJOR.MINOR  →  breaking / significant feature / patch
```

- **Minor bump** (`0.3.2 → 0.3.3`): bug fixes, small additions
- **Mid bump** (`0.3.x → 0.4.0`): new significant feature set
- **Major bump** (`0.x → 1.0.0`): stable public API, production-ready

## Flow in practice

```
feature/auth-rewrite  ──┐
feature/plugin-api    ──┤──► develop ──► release/0.4.0 ──► main (tag v0.4.0)
fix/datasource-null   ──┘
```

## Issue tracking during the release flow

### When a fix PR is opened (e.g. `fix/#40 → develop`)

1. Comment on each linked issue: *"Fixed in `develop` via PR #NNN — will close when `develop → main` is merged."*
2. Apply the **`status: in develop`** label to each issue.
3. Do **not** add `Closes #N` keywords to the `fix/* → develop` PR — GitHub only auto-closes issues when the PR merges into the default branch (`main`).

### When a release PR is opened (`develop → main`)

1. Include `Closes #N` for every issue being released in the PR body.
2. Remove the **`status: in develop`** label from those issues.
3. GitHub will auto-close all linked issues on merge.

### Label reference

| Label | Meaning |
|-------|---------|
| `status: in develop` | Fix merged into `develop`, not yet released to `main` |

## Guidelines

- Use tags (e.g. `v0.3.2`) for release snapshots, not archive branches
- Keep feature branches short-lived and close to `develop`
- Never commit directly to `main`
- All changes to `main` must go through a `release/X.Y.Z` or `hotfix/X.Y.Z` branch