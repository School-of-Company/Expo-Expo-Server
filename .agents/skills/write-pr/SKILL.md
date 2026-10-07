---
name: write-pr
description: Draft and create a GitHub PR from the repository template, with a verified base branch, related issue, and labels.
allowed-tools: Bash(git *:*), Bash(gh *:*), Bash(bash *create-pr.sh:*), Bash(cat *:*), Read, Write
---

## Step 1 — Confirm the Base and Dependencies

Check the current branch, existing PRs, and the remote branches before choosing a base:

```bash
git branch --show-current
gh repo view --json defaultBranchRef -q .defaultBranchRef.name
gh pr list --state open --json number,title,baseRefName,headRefName
git ls-remote --heads origin develop development dev | sed 's#.*refs/heads/##'
```

Use the actual predecessor branch as `BASE` for a dependent PR. Otherwise use the integration branch,
or the default branch when there is no integration branch. Do not infer dependencies from PR numbers.
Record predecessor PRs and intended merge order in the PR body; distinguish merge order from service
deployment prerequisites. If the dependency or base cannot be established, resolve it before creating a PR.

## Step 2 — Gather Context

```bash
git fetch origin "$BASE" --quiet
git log "origin/$BASE..HEAD" --oneline
git diff "origin/$BASE...HEAD" --stat
git diff "origin/$BASE...HEAD"
cat .github/PULL_REQUEST_TEMPLATE.md 2>/dev/null
```

Find the related issue from the request, branch, commits, or GitHub issues. Verify its number and whether
this PR fully closes it. If none can be identified, ask for the issue before creating the PR; never leave
`Closes #` as a placeholder or invent a number.

## Step 3 — Learn This Project's Scope Vocabulary

Read `.agents/shared/commit-conventions.md` and recent PR titles. Follow this repository's title style;
commit scopes do not automatically become PR title prefixes.

## Step 4 — Determine Labels

Labels differ per repo, so read the repo's own set and match against it:

```bash
gh label list --limit 100
```

Pick **1–2** by meaning, using `.agents/skills/write-pr/references/labels.md` as the mapping guide. If nothing
matches, attach none — a wrong label is worse than no label, and an undefined one fails PR creation.

## Step 5 — Generate PR Content

**Title** — Generate 3 concise Korean options in the style of recent PRs:

- Describe the change directly; this repository's current PR titles do not use a `[scope]` prefix.
- No added emojis; max 50 characters total.

**Body** — Copy `.github/PULL_REQUEST_TEMPLATE.md` and fill its existing sections and checklist. Keep
the template's headings and separators; remove placeholder prose. In `관련 이슈`, add a verified issue
reference: `Closes #N` only when this PR completes it, otherwise `Refs #N`. Include predecessor PRs,
intended merge order, and separate deployment prerequisites under `리뷰 시 참고사항` when relevant.

- Korean 합쇼체: `~하였습니다`, `~되었습니다`, `~추가하였습니다`
- Do not add emojis beyond the template's headings.
- Max 2500 characters
- Wrap all proper nouns and technical identifiers in backticks: class names, method names, annotations, file names, field names, config keys, module names, and agent names.

## Step 6 — Write Body & Show Preview

Write the filled template to `PR_BODY.md` without overwriting someone else's file, then display:

```
## PR 제목 후보
1. [title1]
2. [title2]
3. [title3]

## 선택된 라벨
- label1, label2

## PR 본문 미리보기
[body content]
```

Ask the user which title to use (present options 1/2/3). Wait for the answer before proceeding.

## Step 7 — Create PR

Run the creation script with the confirmed title, labels, and verified base. It rejects a body without
a concrete issue reference and silently drops labels this repo does not define:

```bash
bash .agents/skills/write-pr/scripts/create-pr.sh "<confirmed-title>" "PR_BODY.md" "<label1>,<label2>" "<base>"
```

After creation, display the PR URL.
Cleanup: remove `PR_BODY.md` only if this run created it.
