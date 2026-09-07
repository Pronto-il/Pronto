import type { ClarificationAnswer, ClassifyIssueResponse } from '../../shared/api';

/**
 * The evidence a classification was computed from, reduced to one comparable string.
 *
 * <p>Resuming a draft at `ISSUE_CLARIFY`/`ISSUE_REVIEW` used to re-run `POST /api/issues/classify`
 * unconditionally, purely because the previous answer had not been kept anywhere. That is a full
 * model call — and, on the measured baseline, several seconds of a customer staring at an overlay
 * — spent re-deriving a result that had not changed. Caching the answer alongside this signature
 * is what makes reuse safe: the cache is used only when the evidence is byte-for-byte the evidence
 * that produced it, and any difference at all is a miss.
 *
 * <p><b>Every input the backend classifies on is in here</b>, which is the whole correctness
 * argument. Description, photos, the customer's profession hint and the clarification transcript
 * are exactly the four things `ClassifyIssueRequest` carries, so there is no way to change what
 * the server would answer without changing this string. Getting that set wrong in the *other*
 * direction — omitting a field — would serve a stale classification for evidence that has moved
 * on, which is far worse than an unnecessary call, so anything added to the request shape must be
 * added here too.
 *
 * <p>Image keys are NOT sorted. Order is stable in practice (the uploader appends), and sorting
 * would make "the customer removed the first photo and added another" collide with a genuinely
 * different set often enough to matter. A false miss costs one call; a false hit shows the
 * customer an answer to a question they no longer asked.
 */
export function classificationSignature(input: {
  description: string;
  imageKeys: string[];
  selectedCategoryId?: number;
  clarificationAnswers?: ClarificationAnswer[];
}): string {
  return JSON.stringify({
    // Trimmed to match what `DescribeIssueStep` actually submits, so trailing whitespace the
    // customer never sees does not invalidate a perfectly good result.
    description: input.description.trim(),
    imageKeys: input.imageKeys,
    selectedCategoryId: input.selectedCategoryId ?? null,
    clarificationAnswers: (input.clarificationAnswers ?? []).map((answer) => [
      answer.question,
      answer.answer,
    ]),
  });
}

/**
 * A classification plus the signature of the evidence behind it, as persisted in the booking
 * draft. Stored rather than recomputed so a resumed draft can skip the model call entirely.
 */
export interface CachedClassification {
  signature: string;
  result: ClassifyIssueResponse;
}

/**
 * The cached result, or `undefined` when it cannot be trusted for this evidence.
 *
 * <p>Deliberately a plain equality check with no staleness window. A time-based expiry would be
 * guessing: the classification of a description that has not changed does not go stale on a
 * timer, and Pronto re-validates everything that actually matters — image ownership, the category,
 * the issue itself — server-side at creation time regardless of what this returns.
 */
export function readCachedClassification(
  cached: CachedClassification | undefined,
  signature: string,
): ClassifyIssueResponse | undefined {
  return cached && cached.signature === signature ? cached.result : undefined;
}
