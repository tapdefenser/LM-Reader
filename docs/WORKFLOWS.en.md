# Cat-paw workflows

[简体中文](WORKFLOWS.zh-CN.md) · [User guide](USAGE.en.md)

Open **Main menu → Translation workflows**. Start with the built-in local translation workflow, or copy one of the standard, whole-comic fast translation and direct vision-language reference templates. Built-in templates are read-only; copy them before editing. Select the resulting workflow in the comic's translation options.

## Structure and editing

Each workflow has a comic scope, one chapter loop and one page loop. New workflows start with a Seg step in the page loop. Add ordinary steps at a scope's end; select a card to open its parameter drawer. The three-dot menu moves, copies or deletes a step. Moves are checked against scope/type rules and cannot move a step into itself. Undo retains the last 50 structure edits.

```text
Comic
  Each chapter [sync / async]
    Each page [sync / async]
      Seg → page bubbles
      Each bubble
        OCR → source text
        Translate / API → translated text
```

The top menu provides names/retry settings, templates, unified API binding, generated Cat-paw text and instructions. The generated text reflects the actual tree and variable bindings. Changing a parameter with unsaved edits prompts you to save, discard or continue editing.

Each manga's **Translation options → SEG text scope** offers bubbles, free text, or both (the default). The selection is saved per manga and captured in new task snapshots. Existing queued tasks keep their snapshot. The Seg step still accepts only an image; it has no scope parameter.

Text blocks are assigned to balloon contours before filtering the scope. Connected balloons retain separate text targets and significant mask components; overlapping balloon boxes no longer suppress each other solely by containment. Local OCR and API image attachments use the same isolated crops. OCR joins layout line breaks within each region using the source language's spacing rules before passing text to translation steps.

## Steps and variables

| Step | Use |
|---|---|
| Seg / OCR / local translation | Detect regions using the manga's text scope, recognize text and run installed offline models |
| API / streaming API | Send prompts/context and optional images; validate typed output |
| Fill translated bubbles | Map ordered output back to existing bubble identities |
| Each item / If | Iterate a list/dictionary or choose a conditional branch |
| Append / merge / match-replace | Build text/lists and apply glossary substitutions |
| Add context / add glossary names | Construct few-shot messages and add missing comic names |

The `{}` control lists variables available in the current scope. Inputs filter by type; outputs also filter out read-only fields. Prompts insert stable variable references through the variable picker. Renaming a variable preserves its identity; manually typed unbound placeholders fail validation. Images must be selected as API attachments, rather than serialized into text.

Comic variables include source/target languages, resolved style and the shared glossary. Chapter/page/item variables belong to their loops. A custom variable is available after its declaration to the end of its branch. Asynchronous child branches read parent variables without mutating them; use collection outputs to gather results in source order.

## Concurrency and API binding

Sync/async loop controls affect scheduling. Actual requests remain subject to engine/API concurrency limits; a queue waiting for a permit is not an active request. The queue displays the current step and actual Seg/OCR/API activity.

Copied/imported templates must bind API references to configurations on this device. Use the unified binding action or choose a configuration in each request step. Exported workflows do not transfer API keys. Image API steps may send page/bubble images to the configured provider; inspect prompts and logs before sharing.

The glossary is shared by the comic and read at execution time. Added names fill missing original terms without replacing existing entries. Match-replace uses the longest matching original term and does not recursively match replacements.

## Saving and recovery

Queued jobs use a fixed workflow snapshot. A streaming step can fill validated list entries progressively; later steps wait for the full response to finish and validate. Persisted page translations and edits survive restarts, while arbitrary workflow variables and intermediate steps do not resume as checkpoints.

Foreground task notifications support background work, pause/cancel and access to queues. Process termination requires manual retry of interrupted work. See [backup and task recovery](BACKUP.en.md) for persistence and export boundaries.

Import/export carries structure, stable identifiers and API references. Verify bindings and available offline models before running an imported workflow. If a workflow is referenced by comics, deletion lists those references; already queued jobs retain their snapshots.
