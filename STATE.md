# Orchestrator State

> This file stores workflow state only.
> Do not use it as a specification, roadmap, implementation plan, or conversation log.
>
> `project.md` defines the project requirements.
> `plan.md` defines the implementation roadmap.
> `AGENTS.md` defines the agent workflow.

```yaml
state_version: 1

workflow:
  status: IDLE
  stage: null

current_task:
  id: null
  phase: null
  source: null
  source_text: null
  risk: null

attempts:
  implementation: 0
  review: 0

agents:
  planner:
    status: NOT_STARTED
  implementer:
    status: NOT_STARTED
  reviewer:
    status: NOT_STARTED
  final_reviewer:
    status: NOT_REQUIRED

artifacts:
  task: null
  implementation_plan: null
  implementation_result: null
  latest_review: null
  final_review: null
  final_result: null
  blocked: null

verification:
  reviewer_result: null
  final_reviewer_result: null
  validation_result: null
  commands: []

next_action:
  role: ORCHESTRATOR
  action: SELECT_NEXT_TASK

blocking:
  is_blocked: false
  reason: null

completion:
  plan_checkbox_updated: false
  completed_at: null
  git_commit: null
```

## State Values

### `workflow.status`

Allowed values:

* `IDLE`
* `IN_PROGRESS`
* `DONE`
* `BLOCKED`

### `workflow.stage`

Allowed values while a task is active:

* `PLANNING`
* `IMPLEMENTING`
* `REVIEWING`
* `VALIDATING`
* `DONE`
* `BLOCKED`

### Agent status

Allowed values:

* `NOT_STARTED`
* `RUNNING`
* `COMPLETED`
* `FAILED`
* `NOT_REQUIRED`

### Risk

Allowed values:

* `NORMAL`
* `HIGH`

### Review results

Allowed values:

* `PASS`
* `FAIL`
* `PENDING`
* `NOT_REQUIRED`

### Validation result

Allowed values:

* `PASS`
* `FAIL`
* `PENDING`

---

## State Update Rules

The orchestrator must update this file whenever a durable workflow transition occurs.

Examples:

* task selected;
* planning started;
* planning completed;
* implementation started;
* implementation completed;
* review completed;
* review failed;
* fix cycle started;
* validation started;
* validation completed;
* task completed;
* task blocked.

Do not update state for insignificant intermediate reasoning.

The state file should always describe what the NEXT orchestrator instance should do if the current session disappears immediately.

---

## Recovery Rule

When starting or resuming orchestration:

1. Read this file.
2. If `workflow.status` is `IDLE`, select the next eligible unchecked task from `plan.md`.
3. If `workflow.status` is `IN_PROGRESS`, inspect `workflow.stage`.
4. Verify that referenced artifacts actually exist.
5. Verify repository state before taking destructive or modifying actions.
6. Resume the recorded stage.
7. Never assume an agent from a previous session still exists.

If state and repository artifacts disagree, inspect the repository and artifacts before changing state.

Do not silently reconstruct uncertain state.

---

## Completion Rule

A roadmap task may be marked complete only when:

```text
reviewer_result == PASS
AND
validation_result == PASS
AND
plan_checkbox_updated == true
```

For tasks requiring an additional high-risk review:

```text
reviewer_result == PASS
AND
final_reviewer_result == PASS
AND
validation_result == PASS
AND
plan_checkbox_updated == true
```

After completion, retain the completed task state until the orchestrator begins the next task.

When the next task begins, replace `current_task` and reset task-specific counters, agents, artifacts, verification, blocking, and completion fields.

Historical information belongs in `.tasks/<task-id>/`, not in this file.

```
```
