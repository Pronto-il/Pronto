import { httpClient } from './httpClient';

export interface UploadImageResponse {
  imageKey: string;
  imageUrl: string;
  contentType: string;
  sizeBytes: number;
}

/**
 * `POST /api/storage/images` — uploads a single image and returns its storage key, later
 * passed to `classifyIssue`/`createIssue`. Multipart, single part named `file`, per
 * `docs/architecture/api-contract-issues.md` §2.3.
 */
export function uploadImage(file: File): Promise<UploadImageResponse> {
  const formData = new FormData();
  formData.append('file', file);
  return httpClient.post<UploadImageResponse>('/api/storage/images', formData);
}
