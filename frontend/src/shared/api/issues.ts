import { httpClient } from './httpClient';
import type { OrderStatus } from './bookings';

export type IssueUrgencyType = 'STANDARD' | 'SOS';

export interface ClarificationAnswer {
  question: string;
  answer: string;
}

export interface ClassifyIssueRequest {
  description: string;
  imageKeys: string[];
  /** The customer's own category pick, when the flow has one. A hint the backend may overrule. */
  selectedCategoryId?: number;
  /** **Every** answer so far, not just the newest — the endpoint is stateless and re-classifies
   *  against the whole conversation on each call. */
  clarificationAnswers?: ClarificationAnswer[];
}

export interface ClassifyQuestion {
  id: string;
  question: string;
  options: string[];
}

/**
 * Carries no `confidence` or `explanation`: those are real, but they are backend diagnostics
 * (persisted and logged) rather than anything a customer can act on. The previous shape sent
 * both to a UI that was explicitly documented as never allowed to render either — the fields
 * are gone rather than shipped-and-ignored.
 *
 * `questions` holds at most one entry. Pronto asks one question at a time and re-classifies
 * after each answer, so `QUESTIONS` may come back more than once, up to the server-side limit.
 *
 * `UNSUPPORTED_PROFESSION` means classification SUCCEEDED and Pronto does not offer the trade
 * that was identified: `suggestedCategoryId`/`suggestedCategoryCode` are `null`, `questions` is
 * empty, and `detectedProfession` names the trade. It is a terminal state — there is no issue to
 * create and no professional to match — and deliberately distinct from a `CLASSIFIED` result that
 * later finds zero available professionals, which is a supported trade with nobody free right now.
 */
/** What the customer wants done. Mirrors the backend `ai.taxonomy.Intent` enum. */
export type ClassificationIntent =
  | 'REPAIR'
  | 'INSTALLATION'
  | 'MAINTENANCE'
  | 'PROJECT'
  | 'DIAGNOSIS'
  | 'EMERGENCY';

/** How soon, judged from the described situation rather than the trade. Mirrors `ai.taxonomy.Urgency`. */
export type ClassificationUrgency = 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';

export interface ClassifyIssueResponse {
  status: 'CLASSIFIED' | 'QUESTIONS' | 'UNSUPPORTED_PROFESSION';
  /** The trade Pronto identified, in Hebrew. Populated on every status; rendered only on
   *  `UNSUPPORTED_PROFESSION`, where naming the trade is the whole content of the message. */
  detectedProfession: string | null;
  /**
   * The same trade as a stable code from the backend's versioned profession taxonomy, e.g.
   * `GAS_TECHNICIAN`. Additive and currently unread by this app — `detectedProfession` remains
   * what the UI renders, because it is already Hebrew and already customer-facing.
   *
   * It exists so a screen that needs to *branch* (rather than display) has something stable to
   * branch on: matching on the Hebrew string would break the moment the model rephrases it.
   * Populated on every status, including `UNSUPPORTED_PROFESSION` — a trade Pronto does not
   * dispatch is still classified, and this is the field that says which one.
   */
  professionCode: string | null;
  /** The concrete problem under that profession, e.g. `BURST_PIPE_OR_MAJOR_LEAK`. Additive. */
  subcategoryCode: string | null;
  intent: ClassificationIntent | null;
  urgency: ClassificationUrgency | null;
  suggestedCategoryId: number | null;
  suggestedCategoryCode: string | null;
  questions: ClassifyQuestion[];
}

/**
 * `POST /api/issues/classify` — stateless preview, never writes to the database. Called once
 * per clarification round with the same `description`/`imageKeys` and the accumulated
 * `clarificationAnswers`. See `docs/architecture/api-contract-issues.md` §2.1.
 */
export function classifyIssue(payload: ClassifyIssueRequest): Promise<ClassifyIssueResponse> {
  return httpClient.post<ClassifyIssueResponse>('/api/issues/classify', payload);
}

export interface CreateIssueRequest {
  categoryId: number;
  description: string;
  urgencyType: IssueUrgencyType;
  imageKeys: string[];
  /** Persisted with the issue and replayed to the professional — see `IssueDetailResponse`. */
  clarificationAnswers?: ClarificationAnswer[];
}

export interface IssueImage {
  id: number;
  imageUrl: string;
  uploadedAt: string;
}

export interface IssueResponse {
  id: number;
  customerId: number;
  categoryId: number;
  description: string;
  urgencyType: IssueUrgencyType;
  status: string;
  images: IssueImage[];
  createdAt: string;
}

/**
 * `POST /api/issues` — the first (and only) write in the issue-creation journey: persists
 * the `issues` row plus its `issue_images` rows in one transaction. See
 * `docs/architecture/api-contract-issues.md` §2.2.
 */
export function createIssue(payload: CreateIssueRequest): Promise<IssueResponse> {
  return httpClient.post<IssueResponse>('/api/issues', payload);
}

/**
 * `PATCH /api/issues/{issueId}/category` — CUSTOMER only, own issue only, and only while the
 * issue is still `OPEN`. The single mutation this API allows on an issue that already exists.
 *
 * It is what makes "go back from the address step and correct the classification" keep one issue:
 * the alternative was `POST /api/issues` again, which left the first issue behind as an `OPEN`
 * orphan holding the same description, photos and clarification answers. Everything except
 * `categoryId` is preserved server-side — this endpoint has no shape to send anything else in.
 *
 * Errors: `404 NOT_FOUND` (no such issue), `403 FORBIDDEN` (not the caller's issue),
 * `400 VALIDATION_ERROR` (`categoryId` is not a real category), `409 ISSUE_NOT_EDITABLE` (the
 * issue is no longer `OPEN` — typically booked from another tab while this screen was open).
 */
export function updateIssueCategory(issueId: number, categoryId: number): Promise<IssueResponse> {
  return httpClient.patch<IssueResponse>(`/api/issues/${issueId}/category`, { categoryId });
}

/**
 * Milestone 3 addition, per `docs/architecture/api-contract-bookings.md` §2.1 — this DTO,
 * unlike the `bookings` package's own DTOs, was NOT touched by the Milestone 8 merge, so it
 * matches that doc's §2.1 prose exactly.
 */
export interface LatestOrderSummary {
  id: number;
  professionalId: number;
  professionalName: string;
  orderStatus: OrderStatus;
  bookedStart: string;
  bookedEnd: string | null;
  finalPrice: number;
  createdAt: string;
}

/** One question/answer pair from the clarification flow — customer-supplied fact, not AI output. */
export interface ClarificationEntry {
  question: string;
  answer: string;
}

/**
 * Pronto's Professional Brief. Returned **only to a professional** with an order on the issue,
 * and `null` otherwise (including for issues created before the feature existed).
 *
 * This is the AI's interpretation and is a separate object from `IssueDetailResponse.description`,
 * which is and remains the customer's own untouched words — screens must keep the two visually
 * distinct. `status` distinguishes "still generating" from "generation failed"; both are
 * non-blocking. Every list may legitimately be empty.
 */
export interface ProntoAnalysis {
  status: 'PENDING' | 'READY' | 'FAILED';
  customerProblemSummary: string | null;
  clarificationSummary: string | null;
  imageObservations: string[];
  likelyIssue: {
    description: string;
    confidence: number | null;
    evidence: string[];
  } | null;
  possibleCauses: string[];
  recommendedTools: string[];
  recommendedParts: string[];
  safetyNotes: string[];
}

export interface IssueDetailResponse {
  id: number;
  customerId: number;
  categoryId: number;
  categoryCode: string;
  /** The customer's own report, verbatim. Never rewritten by anything in the system. */
  description: string;
  urgencyType: IssueUrgencyType;
  status: string;
  images: IssueImage[];
  clarifications: ClarificationEntry[];
  prontoAnalysis: ProntoAnalysis | null;
  latestOrder: LatestOrderSummary | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * `GET /api/issues/{id}` — either CUSTOMER (must own the issue) or PROFESSIONAL (must have
 * an order against it, any status). See `docs/architecture/api-contract-bookings.md` §2.1.
 */
export function getIssue(issueId: number): Promise<IssueDetailResponse> {
  return httpClient.get<IssueDetailResponse>(`/api/issues/${issueId}`);
}
