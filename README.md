# TileLinkExplorer — CI/CD User Guide

*Draft v1 — covers the CI/CD system as currently deployed and working. More detail to be added.*

## 1. Overview

TileLinkExplorer is a Chisel/RTL project repository. It doesn't run its own CI/CD logic — instead, it plugs into a **shared, central pipeline** maintained once and reused by every project repo in the organization.

Two repos are involved:

| Repo | Role |
|---|---|
| **CICD** (`shau-08/CICD`) | The shared "engine." Contains the reusable CI/CD logic and the toolchain setup steps. Lives once, org-wide. |
| **TileLinkExplorer** (this repo) | A project repo. Contains only small "caller" files that say *"use CICD's logic"* — no CI/CD logic is duplicated here. |

Updating CI/CD behavior for every project repo at once means editing the CICD repo — project repos never need to be touched individually.

## 2. What Happens Automatically (Features)

- **CI on every push and pull request** — checks out the shared `playground` toolchain, installs Java/Mill/firtool/verilator, runs the project's tests, then lints.
- **Merge-conflict pre-check** — if you open a PR into `main`, CI first tries a real merge against `main` before running anything else. If it doesn't merge cleanly, you get a clear error immediately instead of a confusing test failure later.
- **Local lint checks before you even push** — this repo ships git hooks (see §4) that run the same lint check on your machine, so problems are caught before code leaves your laptop, not just in CI.
- **On-demand RTL release (CD)** — manually triggered, not automatic. Generates RTL and publishes it as a downloadable GitHub Release.
- **`cd.config`** — a plain text file in this repo that controls which RTL generation target CD uses (and which specific design it builds within that target — see §5). Editable and pushable like any other file — no need to touch GitHub's settings UI to change it.
- **Weekly verilator cache warm-up** — a scheduled job keeps the (slow-to-build) verilator install cached, so a cache eviction doesn't silently make the next real CI run take much longer.
- **Workflow files check themselves** — any change to files under `.github/workflows/` is automatically linted (via `actionlint`) before it's trusted.

## 3. One-Time Setup (per developer, per clone)

Since you'll be working over SSH from a terminal:

```bash
git clone git@github.com:YOUR_ORG/TileLinkExplorer.git
cd TileLinkExplorer
```

**Enable the local lint hooks** — this repo's git hooks live in `.githooks/`, not the default `.git/hooks/`, so Git needs to be told where to find them. This is a one-time step per clone:

```bash
git config core.hooksPath .githooks
chmod +x .githooks/*
```

Without this step, the hooks exist in the repo but Git will never run them.

## 4. Everyday Developer Workflow

1. **Make your changes** as normal.
2. **Commit.** The `post-commit` hook runs automatically and lints your code. If it reformatted anything, you'll see a message telling you to review, commit the reformatted files, and continue.
3. **Push.** The `pre-push` hook runs the same lint check again. If it finds anything that still needs reformatting, **the push is blocked** until you commit those changes — this guarantees nothing unlinted ever reaches GitHub.
4. **CI runs automatically** on GitHub once your push/PR lands — same lint check, plus the actual test suite, plus (for PRs into `main`) the merge-conflict check.
5. **Merge** once CI is green.

You never need to manually run Mill, install firtool, or set up the toolchain yourself for this — both the local hooks and CI figure out the toolchain automatically.

## 5. Generating an RTL Release (CD)

CD is **manual only** — it never runs on its own. To trigger it:

- **From the GitHub UI:** Actions tab → "CD" workflow → "Run workflow" → optionally give it a release tag name (leave blank to auto-generate one).
- **From the terminal**, if you have `gh` CLI set up:
  ```bash
  gh workflow run cd.yml --repo YOUR_ORG/TileLinkExplorer
  ```

**Which RTL target it builds is controlled by `cd.config`** in this repo, not by anything you type when triggering the run:
```
RTL_TARGET=rtl
```
Change this to `RTL_TARGET=lazyrtl` (the only two valid values) if you need the other generation path, then commit and push it like any other file — no GitHub settings to touch.

**Important: `RTL_TARGET` alone isn't enough — you also need to set `TARGET` to a value that's actually valid for whichever one you chose.** `rtl` and `lazyrtl` call two different Scala objects internally (`explorerTLMain` and `lazyExplorerTLMain`), and each only recognizes its own specific list of `TARGET` names — picking a name from the wrong list fails with `Unknown Module Name!`. The `Makefile` defaults `TARGET` to `"Minimal"`, which only works for `rtl` — so if you switch to `lazyrtl` and don't also add a `TARGET=` line, it will fail using that leftover default.

Add both lines to `cd.config` together:
```
RTL_TARGET=lazyrtl
TARGET=Point2Point
```

Valid `TARGET` values for each:

| `RTL_TARGET=rtl` (`explorerTLMain`) | `RTL_TARGET=lazyrtl` (`lazyExplorerTLMain`) |
|---|---|
| `Minimal` | `Point2Point` |
| `SerPhy` | `RegNode` |
| | `SmoketestRegNode` |
| | `AsyncDevice` |
| | `AdapterNode` |
| | `TLSerDesLoopBack` |
| | `AXI4` |
| | `SBTLLoopback` |
| | `SBTLMem` |

Mixing a `TARGET` from the wrong column with the other `RTL_TARGET` is the single most likely way to break a CD run — worth double-checking both lines match the same column before pushing `cd.config`.

Once CD finishes, it publishes a GitHub Release with a `.tar.gz` of the generated RTL (`generated_sv_dir`) attached.

## 6. Key Files Reference

| File | Purpose |
|---|---|
| `.github/workflows/ci.yml` | Thin trigger — tells GitHub to run CICD's shared CI logic on every push/PR. |
| `.github/workflows/cd.yml` | Thin trigger — same, but for CD, and only on manual dispatch. |
| `.github/workflows/validate.yml` | Lints this repo's own workflow files whenever they change. |
| `.github/workflows/warm-verilator-cache.yml` | Weekly scheduled job, keeps the verilator build cache warm. |
| `Makefile` | Defines `rtl`, `lazyrtl`, `test`, `check`, `rtl-dispatch` — the actual build commands both CI and your local hooks call. |
| `cd.config` | Controls which RTL target (`rtl` or `lazyrtl`) CD builds, and which `TARGET` design to build within that. Edit + push, no UI needed. |
| `build.sc` | Mill build definition — declares this project's dependencies and how it links against the shared `playground` toolchain. |
| `playground.hash` | Pins which exact commit of the shared `playground` toolchain this repo is validated against. |
| `.gitmodules` | Declares this repo's own private/external dependencies (currently: `emitrtl`). |
| `.githooks/post-commit`, `.githooks/pre-push` | Local lint checks — see §4. |
| `.mill-version` | Pins the Mill build tool version used for this repo. |

## 7. Notes

- Dependencies (both `playground`'s own and this repo's `dependencies/`) are currently resolved at their **pinned commit**, not automatically updated to latest — this keeps builds reproducible. If a dependency needs updating, that's a deliberate step (updating the pin), not something that happens silently on its own.
- The `.gitmodules` file currently has two entries for `emitrtl` (`dependencies/emitrtl` and `dependencies/dependencies/emitrtl`) — worth double-checking with whoever maintains this repo whether the second one is intentional.

---

## 8. Setting Up the Central CICD Repo (One-Time, Org-Level)

Everything in §1–§7 assumes the central **CICD** repo and its SSH access already exist. This section covers building that from scratch — relevant once, when standing up CI/CD for the org (or a new sub-org/team), not something individual developers need to do per project.

### 8.1 Creating the central repo

1. Create a new repo in the organization's GitHub account (e.g. `YOUR_ORG/CICD`, if you change name from CICD to something else that should be changed as well in the path of caller yaml files in user's repo as well as in central repo's reusable yaml files. Currently its "shau-08/CICD").
2. Add these files to it:
   ```
   .github/workflows/reusable-ci.yml
   .github/workflows/reusable-cd.yml
   .github/workflows/validate.yml
   .github/actions/setup-toolchain/action.yml
   ```
   These four files are the entire shared "engine" — every project repo's own workflows just point at them.
3. Push to `main`.

### 8.2 Pointing a project repo at the central repo

Each project repo (like TileLinkExplorer) needs only three thin files under its own `.github/workflows/`:

```yaml
# ci.yml
on: [push, pull_request]
jobs:
  ci:
    uses: YOUR_ORG/CICD/.github/workflows/reusable-ci.yml@main
    secrets: inherit
```
```yaml
# cd.yml
on: workflow_dispatch
jobs:
  cd:
    uses: YOUR_ORG/CICD/.github/workflows/reusable-cd.yml@main
    secrets: inherit
```
The `@main` reference is what makes updates to the central repo apply to every project repo automatically — a project repo never needs its own copy of the actual CI/CD logic.

### 8.3 SSH access for private dependencies

Several dependencies (`chipyard`, `diplomacy`, `emitrtl`, `rocket-chip`, `rocket-chip-fpga-shells`, `rocket-chip-inclusive-cache`, `redefine-header`, and likely others as new project repos get added) are referenced via SSH (`git@github.com:...`) URLs, some of which are private. CI has no SSH key by default, so without this setup, any submodule fetch over SSH fails with `Permission denied (publickey)`.

**Step 1 — generate a keypair** (on your own machine, not the CI runner):
```bash
ssh-keygen -t ed25519 -C "ci-key-label" -f ./ci_deploy_key -N ""
```
This creates `ci_deploy_key` (private) and `ci_deploy_key.pub` (public). The `-C` label is just a human-readable comment — it has no effect on how the key works.

**Step 2 — decide whose account the public key belongs to. This choice matters a lot:**

| Approach | How it works | Trade-off |
|---|---|---|
| **Deploy key** (repo Settings → Deploy keys) | Grants access to exactly *one* repo | Must be repeated for every private dependency repo individually |
| **Account-level key** (a person's or bot's own Settings → SSH and GPG keys) | Grants access to *every* repo that account can already read | One-time setup, works for new dependencies automatically, but ties CI's access to a specific account |

The account-level approach, ideally via a **dedicated bot/machine account** (not a specific person's own account), is the recommended path — it scales without repeating setup per repo, and doesn't break if a person leaves or rotates their personal key.

Add the public key here:
```bash
cat ci_deploy_key.pub
```
→ paste into: (bot account's) `github.com/settings/keys` → New SSH key.

**Step 3 — add the private key as a CI secret:**
```bash
cat ci_deploy_key
```
Copy the full output (including the `BEGIN`/`END` lines) → central repo (or org-wide, if set at the org level so every project repo inherits it) → Settings → Secrets and variables → Actions → New secret → name it exactly `CI_SSH_PRIVATE_KEY`.

If you have `gh` CLI authenticated with admin rights, this can be done from the terminal instead:
```bash
gh secret set CI_SSH_PRIVATE_KEY --org YOUR_ORG --visibility all < ci_deploy_key
```

**Step 4 — clean up the private key from local disk** once it's safely stored in GitHub:
```bash
rm ci_deploy_key
```

### 8.4 How this connects to what project repos actually do

Project repos never touch `CI_SSH_PRIVATE_KEY` directly — their `secrets: inherit` line (§8.2) automatically passes whatever secrets exist down into the central repo's reusable workflows, which pass it into `setup-toolchain`'s `ssh-private-key` input, which is what actually authenticates the submodule fetches described in §7. Once §8.3 is done once, every current and future project repo gets working SSH access with zero extra setup on their end.

---

*This is a first draft. Send over the additional details you mentioned — internal conventions, onboarding specifics, anything about the `sb_sim` simulation setup, or corrections to anything above — and I'll fold them in.*
