-- Production MS3: record WHICH prompt and WHICH model produced each stored classification.
--
-- Without these two columns the telemetry in issue_classifications cannot answer the only
-- question it exists to answer. "The model disagreed with the customer on 8% of issues last
-- month" is uninterpretable across a prompt revision or a model upgrade: the rate can move
-- because routing changed, or because the thing doing the routing was replaced, and the rows
-- as they stood could not tell those apart. Recording both makes a change in the drift signal
-- attributable, and makes a regression after a prompt bump visible rather than inferred.
--
-- Both are nullable: rows written before this migration genuinely do not know their prompt or
-- model, and inventing a default would be worse than an honest NULL — it would silently
-- attribute old behaviour to whatever is current. Backfilling is not possible for the same
-- reason.
--
-- Deliberately NOT stored here: the prompt text, the model's reasoning, or any chain-of-thought
-- (roadmap section 30). ambiguity_reason already on this table stays what it is — a short
-- domain label naming the unresolved fact, never internal deliberation.
ALTER TABLE issue_classifications
    ADD COLUMN prompt_version VARCHAR(40),
    ADD COLUMN model          VARCHAR(80);

COMMENT ON COLUMN issue_classifications.prompt_version IS
    'ai.prompt.ClassificationPromptBuilder.PROMPT_VERSION at the time this row was written, '
    'e.g. classification-v3. NULL for rows written before Production MS3.';

COMMENT ON COLUMN issue_classifications.model IS
    'The pronto.openai.model value that produced this classification, e.g. gpt-4o-mini. NULL '
    'for rows written before Production MS3, and for rows produced by the mock provider.';
