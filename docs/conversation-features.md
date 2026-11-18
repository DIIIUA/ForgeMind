# Conversation features in 0.2.7-alpha (development)

This describes the local development version. It does not announce a published release.

## Finding the controls

| Feature | Location |
| --- | --- |
| Answer start, follow mode, previous/next message | Reading controls at the lower left of the chat; “Чтение” opens the options |
| Copy conversation | Chat overflow → “Копировать весь чат”; copies the active visible user/assistant branch |
| Copy reasoning | Reasoning action beside an assistant message |
| Edit assistant answer | Edit action on a completed final answer without tool calls; creates an alternate answer |
| Regenerate | Retry action; confirmation first, then an option to return to the old branch |
| Branch from a fragment | Select message text → “Ветка”; a separate numbered chat starts from that excerpt |
| Model capabilities | Model selector rows; metadata supplied by the provider, with “?” for unknown support |
| Context warning and compaction log | Context indicator and chat overflow → “История сжатия” |
| Stream speech | Chat overflow → “Потоковая озвучка”; emits completed phrases, skips code/tool markup |
| Scheduled prompts | Chat overflow → “Расписания”; once or daily, with an explicit time zone |
| Generation preset | Chat overflow → assistant profiles → create/edit; blank parameter fields inherit provider values |
| Tool grants | Approval card → allow for this chat; chat overflow → “Разрешения инструментов” revokes grants |
| Compare archive before import | ZIP import opens a comparison dialog; applying it produces a report |

## Behavior

- Reading upward disables following; the bottom button resumes it. Pinning a new answer and following at the bottom are separate options.
- Editing an answer and regenerating preserve previous variants. A fragment branch contains the selected excerpt and its origin; it does not copy unrelated history.
- Sending “Сейчас” while a tool approval is waiting declines that pending call and delivers the new user input. It never approves the call on the user's behalf.
- Voice input from the floating ticker is sent immediately, independently of the normal voice auto-send preference. A running generation is cancelled and its partial history saved before the voice turn starts. Delayed inputs remain queued after that turn. A newer explicit Stop takes precedence; a failed send preserves the recognised text in the input field.
- Profile temperature, output token cap, and reasoning mode apply to model requests, including fallback requests. An explicit output cap also bounds the thinking budget. Provider APIs may support only some parameters.
- Edited approval arguments are validated and frozen when accepted. The reviewed arguments are used in execution and in the saved call record. Destructive operations have a cancellable three-second grace period.
- Remembered grants are scoped to a chat, workspace and tool identity/schema/app version. A grant cannot bypass Termux deletion or schedule confirmation, or the dangerous-command check. Existing explicit full-autopilot settings keep their own policy. Revocation applies to future calls.
- `schedule_manage` exposes the same task registry to the agent. Mutations require owner approval. Tasks use their chat's model and instructions. An interrupted task with an unknown outcome is paused instead of replayed. `run_now` preserves the original task and creates a separate once task.
- Scheduling uses WorkManager. Network availability, Android battery restrictions, foreground-service limits and user force-stop can delay or prevent a run. It is not an exact alarm service. Pending approvals still require the owner to return to the chat.
- Archive format 4 includes generation presets and schedules; older formats remain readable. Existing profiles and memory notes win on ID collisions. Imported schedules are disabled. Existing attachment files are preserved; restored files use an isolated import directory.
- Archive preview binds to the staged bytes and current database state. A changed archive or changed data requires a fresh preview. Chat data is transactional; schedule import failures are included in the result as warnings.

## Compatibility

Room schema 9 adds `assistant_profiles.generationConfigJson`, default `{}`; existing profiles inherit provider settings. A source-code rollback does not downgrade an already migrated database. Export data before installing an older application version.

New feature dialogs currently use Russian labels. This change does not claim completion of the application's full translation backlog or device-specific audio acceptance.
