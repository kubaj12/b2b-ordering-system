# Orchestrator State

> This file stores workflow state only.
> Do not use it as a specification, roadmap, implementation plan, or conversation log.
>
> `project.md` defines requirements.
> `plan.md` defines the roadmap.
> `AGENTS.md` defines the workflow.

```yaml
state_version: 2

workflow:
  status: IDLE
  stage: null

current_task:
  id: null
  phase: null
  source: null
  source_text: null
  risk: null
  risk_reason: null
  critical_review_required: null

attempts:
  implementation: 0
  validation: 0
  primary_review: 0
  critical_review: 0

limits:
  primary_review: 5
  critical_review: 5

agents:
  planner:
    status: NOT_STARTED
    preferred_model: gpt-5.6-terra
    actual_model: null
    reasoning_effort: medium
    actual_reasoning_effort: null
    runtime_id: null
  implementer:
    status: NOT_STARTED
    preferred_model: gpt-5.6-luna
    actual_model: null
    reasoning_effort: medium
    actual_reasoning_effort: null
    runtime_id: null
  critical_reviewer:
    required: null
    status: NOT_REQUIRED
    preferred_model: gpt-6-astra
    actual_model: null
    reasoning_effort: low
    actual_reasoning_effort: null
    runtime_id: null

artifacts:
  task: null
  implementation_plan: null
  implementation_result: null
  validation: null
  latest_primary_review: null
  latest_critical_review: null
  final_result: null
  blocked: null

verification:
  validation_result: null
  validated_revision: null
  primary_review_result: null
  critical_review_result: NOT_REQUIRED
  commands: []

next_action:
  role: ORCHESTRATOR
  action: SELECT_NEXT_TASK
  inputs: []

blocking:
  is_blocked: false
  reason: null

completion:
  finalization_complete: false
  plan_checkbox_updated: false
  task_commit_created: false
  completed_at: null
```

## State Values

### Workflow status

`IDLE`, `IN_PROGRESS`, `DONE`, or `BLOCKED`.

### Workflow stage

`PLANNING`, `IMPLEMENTING`, `VALIDATING`, `PRIMARY_REVIEW`, `CRITICAL_REVIEW`, `FINALIZING`, `COMMITTING`, `DONE`, or `BLOCKED`.

### Agent status

`NOT_STARTED`, `RUNNING`, `COMPLETED`, `FAILED`, or `NOT_REQUIRED`.

### Risk

`STANDARD` or `CRITICAL`.

The planner records the reasoned classification in `implementation-plan.md`. The `current_task` risk fields are its operational mirror.

### Review result

`PASS`, `FAIL`, `PENDING`, or `NOT_REQUIRED`.

### Validation result

`PASS`, `FAIL`, or `PENDING`.

---

## Model Selection

Preferred models and reasoning efforts are initialized above. If unavailable, follow the one-level-at-a-time upward ladder in `AGENTS.md`; never fall back downward.

Set `actual_model` and `actual_reasoning_effort` when creating an agent. `runtime_id` is advisory and may be replaced when an instance disappears. Recovery depends on artifacts, not runtime IDs.

---

## Counter Rules

* Increment `implementation` for every implementation or fix iteration.
* Increment `validation` for every authoritative validation decision.
* Increment `primary_review` for every formal planner review decision.
* Increment `critical_review` for every formal critical review decision.
* Validation failures do not increment review counters.
* Review limits are independent.
* Reaching a limit while the latest corresponding result is `FAIL` blocks the task.

---

## State Updates

Update this file for every durable transition: task selection; planning; accepted risk classification; implementation; validation; primary or critical review; fix start; finalization; commit preparation/completion; task completion; or blocking.

Do not record insignificant reasoning. State must always tell a replacement orchestrator what to do next. `next_action.inputs` lists only required artifact paths and does not duplicate their content.

---

## Recovery

1. Read this file.
2. If status is `IDLE`, select the next eligible unchecked task.
3. If `IN_PROGRESS`, inspect stage and next action.
4. Verify referenced artifacts.
5. Verify the working tree and `HEAD` before modifying, staging, or committing.
6. Confirm `validated_revision` still identifies current implementation before relying on validation.
7. Resume a runtime agent only if it still exists.
8. Otherwise recreate the role using model fallback and repository artifacts.
9. Resume without skipping validation or review.

If state and artifacts disagree, inspect both and do not silently reconstruct uncertainty.

At `FINALIZING`, verify current validation/reviews and prepare `final.md`, `plan.md`, and completion state.

At `COMMITTING`, stage only current-task files and create the local task commit. After it succeeds, set this state to `DONE` with `task_commit_created: true` and amend that same commit. Verify that exactly one resulting task commit contains the complete task and that no current-task changes remain uncommitted. Do not create another history entry to record the commit hash.

---

## Completion

A `STANDARD` task may be finalized only when:

```text
validation_result == PASS
AND primary_review_result == PASS
AND critical_review_result == NOT_REQUIRED
```

A `CRITICAL` task may be finalized only when:

```text
validation_result == PASS
AND primary_review_result == PASS
AND critical_review_result == PASS
```

A finalized task reaches `DONE` only when:

```text
finalization_complete == true
AND plan_checkbox_updated == true
AND task_commit_created == true
```

Do not store the final commit hash here because this completed state belongs to that same commit. Use the task ID in Git history.

Retain completed task state until the next task begins. Then replace `current_task` and reset task-specific counters, agents, artifacts, verification, blocking, and completion fields while retaining limits and preferred models.

Historical details belong in `.tasks/<task-id>/`.
