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
 */
export interface ClassifyIssueResponse {
  status: 'CLASSIFIED' | 'QUESTIONS';
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
