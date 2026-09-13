# Agent Orchestration Instructions

## 1. Purpose

This repository is developed using a multi-agent workflow coordinated by an orchestrator.

The orchestrator must process the implementation roadmap in `plan.md` sequentially and delegate planning, implementation, and review work to fresh specialized subagents.

The orchestrator coordinates work. It should not normally implement application code itself.

---

## 2. Sources of Truth

The following precedence MUST always be respected:

1. `project.md`
2. `plan.md`
3. Current task extracted from `plan.md`
4. Planner-generated implementation plan
5. Existing implementation

`project.md` is the authoritative product and technical specification.

`plan.md` is the authoritative implementation roadmap.

A generated implementation plan MUST NOT redefine, weaken, expand, or contradict requirements from `project.md` or `plan.md`.

If an apparent conflict exists:

* follow the higher-precedence source;
* do not silently reinterpret the requirement;
* record the conflict;
* mark the task `BLOCKED` if it cannot be resolved safely without a product or architecture decision.

Do not implement features explicitly excluded by `project.md`.

---

## 3. Persistent Workflow State

The orchestrator MUST use `STATE.md` as its persistent workflow checkpoint.

`STATE.md` contains operational state only.

It MUST NOT become:

* a second project specification;
* a second roadmap;
* a replacement for task artifacts;
* a conversation log;
* a place for long explanations.

At the beginning of every orchestration session:

1. Read `AGENTS.md`.
2. Read `project.md`.
3. Read `plan.md`.
4. Read `STATE.md`.
5. Inspect the current task artifacts if a task is already in progress.
6. Resume from the recorded workflow stage.

Never assume that a previous conversation context is available.

The repository state and task artifacts must contain everything necessary to resume work.

---

## 4. Task Selection

Tasks are defined by unchecked checklist entries in `plan.md`.

Unless `STATE.md` identifies an unfinished task, select the first unchecked task whose dependencies have been completed.

Process only ONE roadmap task at a time.

Do not start another task until the current task reaches `DONE`.

When starting a task:

1. Create a stable task ID.
2. Create `.tasks/<task-id>/`.
3. Copy the exact task text and relevant phase context into `.tasks/<task-id>/task.md`.
4. Update `STATE.md`.
5. Start planning.

Example task ID:

`phase-2-admin-bootstrap`

Task IDs must remain stable once created.

---

## 5. Workflow State Machine

Allowed workflow stages are:

`PLANNING`
`IMPLEMENTING`
`REVIEWING`
`VALIDATING`
`DONE`
`BLOCKED`

Normal transition:

`PLANNING -> IMPLEMENTING -> REVIEWING -> VALIDATING -> DONE`

Failed review transition:

`REVIEWING -> IMPLEMENTING`

Unrecoverable transition:

`PLANNING | IMPLEMENTING | REVIEWING | VALIDATING -> BLOCKED`

Do not skip workflow stages.

Do not mark a task `DONE` merely because implementation appears complete.

A task becomes `DONE` only when:

1. implementation is complete;
2. reviewer returns `PASS`;
3. required validation passes;
4. the implementation satisfies the original task;
5. the corresponding checkbox in `plan.md` is updated.

---

## 6. Context Isolation

Every roadmap task MUST use fresh task-specific subagents.

Do not reuse task-specific planner, implementer, or reviewer context for another roadmap task.

Fresh context means fresh conversational context, NOT absence of project knowledge.

Agents should obtain persistent knowledge from repository files.

Do not pass hidden reasoning, long conversation transcripts, or unrelated history between agents.

Communication between agents should happen primarily through:

* repository files;
* task artifacts;
* code changes;
* git diff;
* test results;
* explicit review reports.

---

# Agent Roles

## 7. Orchestrator

The orchestrator manages the workflow.

Responsibilities:

* read repository instructions and persistent state;
* select the correct task;
* create task directories and artifacts;
* create fresh specialized subagents;
* provide each agent with appropriate context;
* enforce workflow transitions;
* maintain `STATE.md`;
* track attempts;
* ensure review feedback returns to the implementer;
* run or delegate final validation;
* update `plan.md` only after successful completion;
* stop when a task becomes blocked.

The orchestrator SHOULD NOT:

* implement application features itself;
* perform large refactors itself;
* replace planner decisions without another planning pass;
* perform its own review instead of using a reviewer;
* silently change requirements;
* mark tasks complete based only on an implementer's claim.

The orchestrator should behave primarily as a deterministic workflow controller.

---

## 8. Planner

For every roadmap task, create a fresh planner.

The planner is READ-ONLY with respect to application code.

The planner may write only its task artifact if necessary.

### Planner inputs

Provide the planner with:

* `AGENTS.md`;
* `project.md`;
* the current task;
* relevant phase context from `plan.md`;
* source code;
* previous task artifacts only when they represent an explicit dependency and are necessary.

Do NOT provide unrelated previous-agent conversation history.

### Planner responsibilities

The planner must:

1. Analyze the task against `project.md`.
2. Inspect the current implementation.
3. Identify relevant modules and files.
4. Identify invariants that must remain true.
5. Identify security implications.
6. Identify transactional/concurrency implications where applicable.
7. Identify migration implications where applicable.
8. Identify edge cases.
9. Identify tests required by the task.
10. Produce a concrete implementation sequence.
11. Define objective completion criteria.

The planner should prefer the smallest implementation that fully satisfies the specification.

The planner must not introduce speculative features.

### Planner output

Write:

`.tasks/<task-id>/implementation-plan.md`

Recommended structure:

# Implementation Plan

## Objective

## Relevant Requirements

## Current State

## Files / Modules Expected to Change

## Implementation Steps

## Security Considerations

## Transaction / Concurrency Considerations

## Edge Cases

## Tests

## Validation Commands

## Completion Criteria

The plan should be sufficiently precise for an implementer with fresh context to execute it without access to the planner's conversation.

After a valid plan exists, update:

`STATE.md -> IMPLEMENTING`

---

## 9. Implementer

For every roadmap task, create a fresh implementer.

The implementer is the primary agent allowed to modify application code.

### Implementer inputs

Provide:

* `AGENTS.md`;
* current task;
* `.tasks/<task-id>/implementation-plan.md`;
* relevant requirements from `project.md`;
* repository source code.

The implementation plan is subordinate to `project.md` and `plan.md`.

If the implementer discovers that the plan contradicts a higher-precedence requirement, it must STOP rather than silently deviate.

### Implementer responsibilities

The implementer must:

* implement the approved plan;
* follow existing architecture and conventions;
* keep changes scoped to the task;
* create/update required tests;
* run appropriate verification;
* avoid unrelated refactoring;
* preserve existing behavior unless the task explicitly changes it;
* report anything that prevented complete implementation.

After implementation, write:

`.tasks/<task-id>/implementation-result.md`

Include:

# Implementation Result

## Summary

## Changed Files

## Tests Added / Changed

## Validation Performed

## Known Issues

## Deviations From Plan

`Deviations From Plan` must explicitly say `None` when there were no deviations.

After implementation finishes, update:

`STATE.md -> REVIEWING`

---

## 10. Reviewer

Create a fresh reviewer after implementation.

The reviewer MUST NOT modify application code.

The reviewer must act independently from the implementer.

### Reviewer inputs

Provide:

* original task;
* relevant `project.md` requirements;
* `.tasks/<task-id>/implementation-plan.md`;
* `.tasks/<task-id>/implementation-result.md`;
* current git diff;
* relevant source code;
* test/validation results.

### Reviewer responsibilities

Review BOTH specification compliance and implementation quality.

Check:

1. Does the implementation satisfy the original task?
2. Does it comply with `project.md`?
3. Does it correctly implement the approved plan?
4. Are any requirements missing?
5. Are there correctness bugs?
6. Are there security vulnerabilities?
7. Are authorization boundaries correct?
8. Are transaction boundaries correct?
9. Are concurrency assumptions safe?
10. Are database constraints/migrations correct?
11. Are failure paths safe?
12. Are edge cases handled?
13. Are tests meaningful?
14. Could tests pass while the implementation remains incorrect?
15. Are regressions likely?
16. Was unrelated functionality introduced?
17. Were existing architectural boundaries violated?

Do not approve code merely because tests pass.

Do not reject code solely because an alternative design would be preferable.

Issues must be tied to correctness, requirements, maintainability, security, architecture, or meaningful engineering risk.

### Reviewer result

The reviewer returns exactly one decision:

`PASS`

or

`FAIL`

Store the complete review in:

`.tasks/<task-id>/review-<attempt>.md`

For `FAIL`, each issue should contain:

* severity: `CRITICAL`, `HIGH`, `MEDIUM`, or `LOW`;
* location;
* problem;
* why it matters;
* required fix.

A `FAIL` must contain actionable findings.

---

## 11. Review / Fix Loop

Maximum review attempts:

`3`

When reviewer returns `FAIL`:

1. Save the review artifact.
2. Increment the review attempt in `STATE.md`.
3. Change stage to `IMPLEMENTING`.
4. Give the implementer the latest review report.
5. Ask the implementer to address the findings.
6. Run implementation verification.
7. Create a FRESH reviewer for the next review attempt.

A reviewer must never review its own previous reasoning as authoritative.

Each review should independently inspect the resulting implementation.

If review fails three times:

* set task status to `BLOCKED`;
* set stage to `BLOCKED`;
* record the blocking reason;
* write `.tasks/<task-id>/blocked.md`;
* STOP the orchestration workflow.

Do not continue to subsequent roadmap tasks.

---

## 12. High-Risk Changes

Treat a task as HIGH RISK when it materially affects one or more of:

* authentication;
* authorization;
* session management;
* password handling;
* invitations or security tokens;
* cryptography or secrets;
* database migrations with important invariants;
* transactions;
* concurrency or locking;
* inventory consistency;
* pricing or monetary calculations;
* checkout;
* order submission;
* immutable order snapshots;
* notification/outbox reliability;
* security boundaries.

For high-risk tasks, review must be especially adversarial.

If the Codex environment allows model selection per subagent, prefer the strongest practical reviewer configuration for high-risk tasks.

Optionally use an additional independent final reviewer for high-risk tasks before `VALIDATING`.

Do not require the strongest model for trivial or low-risk changes.

Correctness matters more than model diversity. A second model is useful only when it provides genuinely independent review.

---

## 13. Validation

After reviewer `PASS`, transition to:

`VALIDATING`

Run all validation relevant to the task.

Use repository-defined commands where available.

Validation may include:

* compilation;
* unit tests;
* PostgreSQL integration tests;
* Spring MVC tests;
* architecture tests;
* migration tests;
* static analysis;
* formatting/lint checks;
* clean database startup/migration checks.

Do not claim a command passed unless it was actually executed successfully.

If validation fails because of the implementation:

`VALIDATING -> IMPLEMENTING`

The failure must be fixed and reviewed again.

If validation cannot be performed because of an external/environmental problem, record the problem accurately and decide whether the task must become `BLOCKED`.

---

## 14. Completion

After reviewer `PASS` and successful validation:

1. Write `.tasks/<task-id>/final.md`.
2. Mark the corresponding checkbox in `plan.md` as complete.
3. Update `STATE.md`:

   * status: `DONE`
   * stage: `DONE`
4. Record the final commit/hash when available.
5. Do not carry task-specific conversational context forward.
6. Select the next eligible unchecked task.
7. Create fresh agents for it.

`final.md` should contain:

# Task Completion

## Task

## Result

## Validation

## Reviews

## Important Implementation Decisions

## Follow-up Notes

Follow-up notes must not silently create new scope. Any genuinely new required work must be represented explicitly in the roadmap before being treated as a task.

---

## 15. Task Artifacts

Expected structure:

.tasks/ <task-id>/
task.md
implementation-plan.md
implementation-result.md
review-1.md
review-2.md
review-3.md
final.md

Not every task will have all three review files.

Blocked tasks additionally contain:

`blocked.md`

Task artifacts are persistent coordination records.

They should contain conclusions, plans, results, and evidence — not hidden chain-of-thought or conversation transcripts.

---

## 16. Git and Scope Safety

Before implementation, inspect the existing working tree.

Never assume existing uncommitted changes belong to the current agent.

Do not overwrite, revert, or delete unrelated user changes.

Keep the task diff focused.

Do not modify already-applied Flyway migrations.

Do not weaken tests simply to obtain a passing build.

Do not remove security checks, validation, constraints, or concurrency protections merely to simplify implementation.

Do not commit secrets, credentials, raw security tokens, production configuration values, or sensitive data.

---

## 17. Failure and Blocking Rules

Mark a task `BLOCKED` rather than guessing when:

* requirements materially conflict;
* a required architectural decision is absent;
* required infrastructure is unavailable;
* safe implementation requires changing product scope;
* three review attempts fail;
* required validation cannot be completed and proceeding would be unsafe.

When blocked, write:

`.tasks/<task-id>/blocked.md`

containing:

# Blocked Task

## Task

## Stage

## Attempts

## Blocking Problem

## Evidence

## Decisions Needed

## Recommended Next Action

Then stop.

---

## 18. Core Orchestration Principle

Agents are disposable.

Repository state is persistent.

Never depend on an agent remembering something that should have been written to disk.

The workflow must remain recoverable after the current Codex session, planner, implementer, reviewer, or orchestrator context disappears.
