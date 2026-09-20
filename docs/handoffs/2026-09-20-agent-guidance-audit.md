# Agent guidance audit — 2026-09-20

## Scope and decisions

The requested audit covers repository-owned agent instructions, skill metadata, reusable prompts, and directly related documentation. The final user request authorizes commit, push, and merge and requires both root instruction files to fit within five lines. Product code, execution configuration, hooks, dependencies, historical evidence, and personal/plugin files are outside the edit scope.

The original checkout was on local `main` at `bc60f7e`, with 13 modified tracked files, no staged changes, and many untracked artifacts. After fetching, local and remote main had diverged by 8 and 26 commits respectively. Work proceeds in a separate worktree from remote `main` at `1eded75`; the existing checkout is not rebased, stashed, or merged. In particular, local `contracts/README.md` is absent from remote main, so its validation command is not introduced into shared instructions.

| Candidate | Decision and evidence |
| --- | --- |
| Root/nested AGENTS.md, AGENTS.override.md, CLAUDE.md | No files in the tracked local or remote trees; no root files in the original checkout. Add five-line AGENTS.md and one-line CLAUDE.md. This is creation, not a measured reduction. |
| Repository .agents/skills, .claude/skills, rules, commands, agents | Absent from both tracked trees; original .claude contains only another task's worktrees. Do not manufacture workflows or edit other checkouts. |
| Original .codex/skills/reverse-skill | Untracked bundle containing specialist skills, scripts, routing configuration, and local field journals. Its provenance/ownership and existing user modifications cannot be reliably separated. Defer edits, migration, deletion, and committing this bundle. |
| Bundle routing/CONTRIBUTING.md | Sampled text requires unconditional router/case bootstrap and strong execution scaffolding. Recommend a separate ownership-confirmed audit to narrow triggers and conditional reads; no execution or metadata fields changed here. |
| README and architecture design | Keep intact; link only for repository boundaries, architecture/UI work, and gameplay acceptance. Preserve resource provenance, authority, visual fidelity, and multi-client acceptance in their existing source. |
| Tracked reusable prompts | No dedicated prompt/command/skill entrypoints found by tracked path inventory. Plans, work reports, and handoffs are historical task context, not blanket current instructions; preserve them. |
| Global skills/plugins and personal configuration | Read-only/out of scope. No global activation, installation, or disabling. |

## Loading and compatibility

Installed versions: Codex CLI 0.144.1 and Claude Code 2.1.271 (`--version` only). No repository configuration or fallback-name setting was found in the inspected locations; the user Codex config had no explicit `project_doc_fallback_filenames` or `project_doc_max_bytes` entries. Personal instructions and organization policy may add context and were not exhaustively audited.

For repository root, `apps/server`, and `apps/client`, the tracked tree has no nested instruction overrides. Codex's documented root-to-working-directory selection therefore reaches the new root AGENTS.md. Its Markdown links are conditional reading directions, not Claude-style imports. The current session's catalog exposes the original `.codex/skills/reverse-skill` location, whereas current Codex documentation names `.agents/skills`; relocating the untracked bundle without establishing that integration would risk breaking discovery.

Claude's new CLAUDE.md explicitly imports the adjacent AGENTS.md, with no cycle or further import. Current official documentation dates native AGENTS.md fallback support to 2.1.277, newer than the installed 2.1.271; this migration does not depend on it. The five shared lines contain no Codex-only commands. Imported content counts toward Claude's context: its repository contribution is one import line plus the shared five lines, not merely one line. No unconditional rules or additional imported documents are added.

## Validation and remaining checks

Static checks cover line limits, UTF-8 file sizes, relative link/import existence, absence of import cycles, `git diff --check`, and an exact three-file documentation-only commit scope. Original tracked modifications and staged state remain untouched because all writes occur in the new worktree. Application tests are not warranted by these documentation-only edits.

No skill descriptions or invocation fields changed, so positive/negative skill invocation tests are not applicable. Conditional reading review: architecture or UI fidelity work follows the design; a spelling correction does not require reading architecture, every historical handoff, or any reverse-engineering skill. A review-only user request still authorizes review only.

Actual Codex/Claude instruction loading has not been exercised. Starting an agent can run personal hooks or integrations whose absence of side effects was not established; version checks are not runtime loading tests. In a fresh approved session at root and at `apps/server`/`apps/client`, verify Codex's active instruction chain and Claude's `/context` or `/memory` import display, and ask each to identify the shared provenance and publication boundaries. Do not infer a reload in the audit session. No token, latency, or quality improvement is claimed.

## Sources consulted

- [OpenAI: Rethinking skills and prompts for GPT-6 Astra](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra): narrow triggers and conditional detail; not evidence of Claude model behavior.
- [Codex AGENTS.md](https://developers.openai.com/codex/agent-configuration/agents-md), [skills](https://developers.openai.com/codex/build-skills), and [configuration](https://developers.openai.com/codex/config-file/config-basic): fetched official pages (redirecting to ChatGPT Learn).
- [Claude memory](https://code.claude.com/docs/en/memory): import behavior, directory loading, and version-qualified AGENTS.md support.
- [Claude features](https://code.claude.com/docs/en/features-overview), [skills](https://code.claude.com/docs/en/skills), [best practices](https://code.claude.com/docs/en/best-practices), [settings](https://code.claude.com/docs/en/settings), and [subagents](https://code.claude.com/docs/en/sub-agents): fetched for the requested audit; no new tool-specific execution mechanism introduced.
