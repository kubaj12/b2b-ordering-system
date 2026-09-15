# Agent Orchestration Instructions

## 1. Purpose

This repository uses a multi-agent workflow coordinated by an orchestrator.

The orchestrator processes `plan.md` sequentially. Every roadmap task gets a task-specific planner and implementer. The planner plans the work and performs primary code review. The implementer alone modifies application code. A separate critical reviewer is created only when the planner classifies the task as `CRITICAL`.

The orchestrator coordinates work. It should not normally implement or review application code.

---

## 2. Sources of Truth

The following precedence MUST be respected:

1. `project.md`
2. `plan.md`
3. Current task extracted from `plan.md`
4. Planner-generated implementation plan
5. Existing implementation

`project.md` is the authoritative product and technical specification. `plan.md` is the authoritative roadmap.

A generated plan MUST NOT redefine, weaken, expand, or contradict a higher-precedence source. If an apparent conflict exists:

* follow the higher-precedence source;
* do not silently reinterpret it;
* record the conflict;
* mark the task `BLOCKED` if it cannot be resolved safely without a product or architecture decision.

Do not implement features explicitly excluded by `project.md`.

---

## 3. Persistent Workflow State

The orchestrator MUST use `STATE.md` as its persistent checkpoint. It contains operational state only and MUST NOT become a second specification, roadmap, task artifact, conversation log, or place for long explanations.

At the beginning of every orchestration session:

1. Read `AGENTS.md`.
2. Read `project.md`.
3. Read `plan.md`.
4. Read `STATE.md`.
5. Inspect current task artifacts when a task is active.
6. Resume the recorded stage.

Never assume previous conversation context or runtime agent instances are available. Repository state and task artifacts must be sufficient for recovery.

---

## 4. Task Selection

Tasks are unchecked checklist entries in `plan.md`. Unless `STATE.md` identifies unfinished work, select the first unchecked task whose dependencies are complete.

Process only ONE task at a time. Do not start another until the current task reaches `DONE`.

When starting:

1. Create a stable task ID.
2. Create `.tasks/<task-id>/`.
3. Copy the exact task text and relevant phase context into `.tasks/<task-id>/task.md`.
4. Update `STATE.md`.
5. Start planning.

Example: `phase-2-admin-bootstrap`.

Task IDs remain stable. `task.md` remains a faithful roadmap record; risk analysis belongs in `implementation-plan.md`.

---

## 5. Workflow State Machine

Allowed stages:

`PLANNING`
`IMPLEMENTING`
`VALIDATING`
`PRIMARY_REVIEW`
`CRITICAL_REVIEW`
`FINALIZING`
`COMMITTING`
`DONE`
`BLOCKED`

Normal `STANDARD` flow:

`PLANNING -> IMPLEMENTING -> VALIDATING -> PRIMARY_REVIEW -> FINALIZING -> COMMITTING -> DONE`

Normal `CRITICAL` flow:

`PLANNING -> IMPLEMENTING -> VALIDATING -> PRIMARY_REVIEW -> CRITICAL_REVIEW -> FINALIZING -> COMMITTING -> DONE`

Failure transitions:

* `VALIDATING -> IMPLEMENTING`
* `PRIMARY_REVIEW -> IMPLEMENTING`
* `CRITICAL_REVIEW -> IMPLEMENTING`

After every implementation or fix, validation MUST pass before primary review. After a critical-review fix, validation and primary review MUST pass again before another critical review.

An unrecoverable failure from any active stage transitions to `BLOCKED`. Do not skip applicable stages; only `STANDARD` tasks omit `CRITICAL_REVIEW`.

A task reaches `DONE` only when implementation is complete, current validation passes, all required reviews pass, the original task is satisfied, artifacts are complete, the roadmap checkbox is updated, and one local task commit succeeds.

---

## 6. Agent Model Configuration

Use these preferred configurations when Codex permits model selection:

| Role | Preferred model | Reasoning |
|---|---|---|
| Planner | `gpt-5.6-terra` | `medium` |
| Implementer | `gpt-5.6-luna` | `medium` |
| Critical reviewer | `gpt-6-astra` | `low` |

Ordered capability ladder:

`gpt-5.6-luna -> gpt-5.6-terra -> gpt-5.6-sol -> gpt-6-astra`

If a preferred model is unavailable:

1. Try the immediately higher model.
2. Continue upward exactly one level at a time.
3. Never substitute a lower model.
4. Preserve the configured reasoning effort when supported.
5. Record the actual model and reasoning effort in `STATE.md`.
6. If no permitted higher model is available, mark the task `BLOCKED`.

The orchestrator's model is intentionally not prescribed here.

---

## 7. Context Isolation and Reuse

Every roadmap task uses fresh task-specific agent context. Never reuse an agent for another task.

Within one task and live orchestration session:

* reuse the planner for planning and all primary reviews;
* reuse the implementer for initial implementation and fixes;
* reuse the critical reviewer for all critical-review attempts.

Every review inspects the complete current implementation. Earlier findings are context, not proof that other defects do not exist.

Runtime agents may disappear. On recovery, recreate unavailable roles with fresh context from repository artifacts. Reuse is an optimization, never a persistent-state dependency.

Do not pass hidden reasoning, long transcripts, or unrelated history. Communicate primarily through repository files, artifacts, diffs, validation results, and review reports.

---

# Agent Roles

## 8. Orchestrator

Responsibilities:

* read instructions and persistent state;
* select the correct task;
* create task directories and artifacts;
* create or resume agents with configured models;
* enforce transitions and maintain `STATE.md`;
* track implementation, validation, and review attempts;
* return validation/review findings to the implementer;
* run or delegate authoritative validation after every implementation;
* require planner risk classification before implementation;
* create a critical reviewer only for `CRITICAL` work;
* update `plan.md` only after successful implementation, validation, and review;
* create exactly one local commit after successful task completion;
* stop when blocked.

The orchestrator SHOULD NOT implement features, perform large refactors, replace planner decisions without another planning pass, perform code review, silently change requirements, or accept an implementer's claim without independent evidence.

The orchestrator verifies that risk classification is present and reasoned. It may conservatively escalate `STANDARD` to `CRITICAL`, but must not silently downgrade `CRITICAL` or override classification merely to avoid review.

---

## 9. Planner

Every task gets a fresh planner serving in planning and primary-review modes. It is READ-ONLY for application code and may write only planning and review artifacts.

### Planning inputs

Provide `AGENTS.md`, `project.md`, current task and phase context, source code, and only dependency artifacts that are necessary.

### Planning responsibilities

The planner must:

1. Trace exact `project.md` and `plan.md` requirements.
2. Inspect current implementation and architecture.
3. Define scope and explicit non-goals.
4. Identify affected modules, files, and boundaries.
5. Identify invariants.
6. Analyze security and authorization.
7. Analyze transactions and concurrency where applicable.
8. Analyze migrations and data integrity where applicable.
9. Identify edge cases and failure paths.
10. Define meaningful tests, including false-confidence risks.
11. Produce a concrete implementation sequence.
12. Define validation commands and objective completion criteria.
13. Classify the task `STANDARD` or `CRITICAL` with reasons.
14. Distinguish requirements from recommendations.

Prefer the smallest compliant implementation. Do not introduce speculative features.

Classify as `CRITICAL` when work materially affects authentication, authorization, security boundaries, sessions, passwords, tokens, cryptography, secrets, important migration invariants, transactions, concurrency, locking, inventory, pricing, monetary calculations, checkout, order submission, immutable snapshots, or notification/outbox reliability. When uncertain, choose `CRITICAL`.

### Planning output

Write `.tasks/<task-id>/implementation-plan.md` with:

* Objective
* Relevant Requirements
* Scope and Non-Goals
* Current State
* Files / Modules Expected to Change
* Implementation Steps
* Security Considerations
* Transaction / Concurrency Considerations
* Migration / Data Integrity Considerations
* Edge Cases and Failure Paths
* Tests
* Validation Commands
* Risk Classification
* Completion Criteria

The risk section states the classification, whether critical review is required, and concise reasons. Mirror it in `STATE.md`, then transition to `IMPLEMENTING`.

### Primary review

Provide the planner with the original task, relevant requirements, implementation plan, implementation result, validation artifact, previous reviews, current diff, and relevant code.

Review the complete implementation for:

1. task and specification compliance;
2. correct or justified plan implementation;
3. missing requirements and unsafe failure paths;
4. correctness, security, authorization, transaction, concurrency, migration, and integrity defects;
5. database constraints;
6. edge cases;
7. meaningful tests and false confidence;
8. validation applying to current code;
9. regressions, unrelated scope, and boundary violations.

Tests passing is not sufficient for approval. Do not reject solely because another design is preferable.

Return exactly `PASS` or `FAIL` in `.tasks/<task-id>/review-<attempt>.md`. Every failure finding includes severity (`CRITICAL`, `HIGH`, `MEDIUM`, or `LOW`), location, problem, impact, and required fix.

---

## 10. Implementer

Every task gets a fresh implementer. It is the only task agent allowed to change application code, tests, migrations, or implementation documentation.

Provide the task, plan, relevant requirements, source, and the latest validation/review report when fixing failures. If the plan conflicts with a higher-precedence source, STOP instead of silently deviating.

The implementer must implement the plan, follow architecture, remain scoped, add meaningful tests, perform useful development verification, avoid unrelated refactors, preserve behavior outside scope, address findings, report blockers, and never review its own work.

After every implementation or fix, append an iteration to `.tasks/<task-id>/implementation-result.md` containing:

* Trigger
* Summary
* Changed Files
* Tests Added / Changed
* Development Verification
* Known Issues
* Deviations From Plan

Use `## Iteration 1`, `## Iteration 2`, and so on. Later iterations identify their triggering validation/review and findings addressed. `Deviations From Plan` says `None` when applicable. Then transition to `VALIDATING`.

---

## 11. Validation

Authoritative validation occurs after every implementation or fix and before review. The orchestrator runs it or delegates it independently of the implementer's report, using repository-defined commands.

Validation may include compilation, unit tests, PostgreSQL integration tests, MVC/template tests, architecture checks, migrations, static analysis, lint/formatting, and clean database startup. Never claim an unexecuted command passed.

Record every iteration in `.tasks/<task-id>/validation.md` with:

* Implementation Iteration
* Working Tree Revision
* Commands
* Results
* Decision

Use `## Iteration 1`, `## Iteration 2`, and so on. Identify the validated working-tree state with suitable diff metadata or a tree hash so later changes cannot rely on stale evidence.

On implementation-related failure, record it, increment validation, transition to `IMPLEMENTING`, and give the report to the implementer. Validation failures do not consume review attempts.

If required validation cannot be performed safely because of the environment, record evidence and mark the task `BLOCKED`. After validation `PASS`, transition to `PRIMARY_REVIEW`.

---

## 12. Critical Reviewer

Create this independent, read-only agent only for `CRITICAL` tasks. Provide the primary-review inputs plus the latest primary review.

It performs an adversarial full review emphasizing security/authorization, transactions, concurrency/locking, migrations/invariants, inventory/monetary consistency, failure safety, and tests that may provide false confidence.

Return exactly `PASS` or `FAIL` in `.tasks/<task-id>/critical-review-<attempt>.md`, using the primary-review finding format. For `STANDARD` tasks, status and result are `NOT_REQUIRED`.

---

## 13. Review / Fix Loops

Maximum failed primary-review decisions: `5`.

Maximum failed critical-review decisions: `5`.

Counters are independent. Validation failures consume neither limit.

On primary `FAIL`: save the report, increment the primary counter, transition to `IMPLEMENTING`, return findings to the implementer, then validate before primary review.

On critical `FAIL`: save the report, increment the critical counter, transition to `IMPLEMENTING`, return findings to the implementer, then pass validation and primary review before critical review.

Reuse live reviewers when possible, but every attempt independently examines the complete result. Recreate missing runtime agents from artifacts.

If either counter reaches five with the latest result still `FAIL`, set status/stage to `BLOCKED`, write `blocked.md`, stop, and do not continue to another task.

---

## 14. Finalization and Completion

After all required reviews pass:

1. Transition to `FINALIZING`.
2. Confirm validation applies to current implementation.
3. Write `.tasks/<task-id>/final.md`.
4. Mark the roadmap checkbox complete.
5. Update `STATE.md` and transition to `COMMITTING`.
6. Inspect the complete diff and staged list for scope and secrets.
7. Stage only task files.
8. Create the local task commit and verify its contents.
9. Set `STATE.md` to `DONE` with `task_commit_created: true`, stage that state change, and amend the task commit without changing its message.
10. Verify that the amended commit contains the complete task and that the working tree contains no uncommitted current-task changes.
11. Begin the next eligible task with fresh agents.

Do not push unless separately requested. Do not create intermediate planning, implementation, validation, review, fix, or checkpoint commits. The final amend is part of creating the one resulting task commit; it must not introduce a second history entry. Include the task ID in the commit message; no naming convention is otherwise prescribed.

A commit cannot contain its own hash. Do not store the final hash in `STATE.md` or another file in the same commit. Git history and task ID provide the association.

`final.md` contains Task, Result, Validation, Reviews, Important Implementation Decisions, and Follow-up Notes. Follow-up notes must not silently expand scope.

---

## 15. Task Artifacts

Expected structure:

```text
.tasks/<task-id>/
  task.md
  implementation-plan.md
  implementation-result.md
  validation.md
  review-1.md ... review-5.md
  critical-review-1.md ... critical-review-5.md
  final.md
```

Create only artifacts required by actual attempts. `STANDARD` tasks have no critical-review artifacts. Blocked tasks add `blocked.md`.

Artifacts contain conclusions and evidence, not hidden chain-of-thought or transcripts.

---

## 16. Git and Scope Safety

Inspect the working tree before implementation and the final commit. Existing changes belong to the user unless proven otherwise. Never overwrite, revert, delete, stage, or commit unrelated changes.

Keep the diff focused. Do not modify applied Flyway migrations, weaken tests, remove protections, or commit secrets, credentials, raw tokens, production values, or sensitive data.

If unrelated changes prevent a task-only commit, stop and request direction instead of including or discarding them.

---

## 17. Blocking Rules

Mark `BLOCKED` rather than guessing when requirements conflict, an architecture decision is missing, infrastructure is unavailable, no permitted model is available, safe work requires changing scope, five primary or critical reviews fail, required validation cannot be completed safely, or the final task-only commit cannot be created.

Write `.tasks/<task-id>/blocked.md` with Task, Stage, Attempts, Blocking Problem, Evidence, Decisions Needed, and Recommended Next Action. Then stop without a completion commit or subsequent task.

---

## 18. Core Principle

Agents are disposable. Repository state is persistent.

Never depend on an agent remembering what should be on disk. The workflow must recover after any orchestrator, planner, implementer, or reviewer context disappears.
