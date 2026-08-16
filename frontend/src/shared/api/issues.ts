import { httpClient } from './httpClient';

export type IssueUrgencyType = 'STANDARD' | 'SOS';

export interface ClarificationAnswer {
  question: string;
  answer: string;
}

export interface ClassifyIssueRequest {
  description: string;
  imageKeys: string[];
  clarificationAnswers?: ClarificationAnswer[];
}

export interface ClassifyQuestion {
  id: string;
  question: string;
  options: string[];
}

export interface ClassifyIssueResponse {
  status: 'CLASSIFIED' | 'QUESTIONS';
  suggestedCategoryId: number | null;
  suggestedCategoryCode: string | null;
  confidence: number | null;
  explanation: string;
  questions: ClassifyQuestion[];
}

/**
 * `POST /api/issues/classify` — stateless preview, never writes to the database. May be
 * called repeatedly (e.g. after editing the description, or with `clarificationAnswers` for
 * the single allowed clarification round). See `docs/architecture/api-contract-issues.md`
 * §2.1.
 */
export function classifyIssue(payload: ClassifyIssueRequest): Promise<ClassifyIssueResponse> {
  return httpClient.post<ClassifyIssueResponse>('/api/issues/classify', payload);
}

export interface CreateIssueRequest {
  categoryId: number;
  description: string;
  urgencyType: IssueUrgencyType;
  imageKeys: string[];
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
