function xsrf(): string {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

export class ApiError extends Error {
  constructor(public status: number) {
    super(`Request failed (${status})`);
  }
}

export async function api<T>(path: string, method = 'GET', body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    method,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf(), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new ApiError(response.status);
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export interface Me { userId: string; organizationId: string; email: string; organizationAdmin: boolean }
export interface Project { id: string; name: string }
export interface Target { id: string; name: string }
export interface Environment { id: string; targetId: string; name: string; classification: string; baseUrl: string; mode: string; channel: string }
export interface Run { id: string; environmentId: string; status: string; createdAt: string; finishedAt: string | null; stopReason: string | null }
export interface Step { attempt: number; sequence: number; parent: number; kind: string; url: string; label: string; locators: string[]; verdict: string; status: number; note: string; durationMs: number }
export interface Defect { id: string; type: string; severity: string; title: string; url: string; detail: string; status: string; occurrences: number; firstSeenRun: string; lastSeenRun: string }
export interface Evidence { id: string; type: string; sha256: string; size: number }
export interface ReproStep { sequence: number; kind: string; url: string; label: string }
export interface Trace { defect: Defect; findings: { runId: string; attempt: number; step: number; evidence: Evidence[]; reproduction: ReproStep[] }[]; testCases: string[] }
export interface TestCase { id: string; title: string; latestVersion: number; lastVerdict: string | null }
export interface Summary {
  runs: number; openDefects: number; defectsBySeverity: Record<string, number>; defectsByStatus: Record<string, number>;
  testCases: number; latestRunVerdicts: Record<string, number>; latestRun: string | null; pagesCovered: number; blockedActions: number;
}
export interface ApiMetric { operation: string; calls: number; passed: number; failed: number; blocked: number; averageMs: number; p95Ms: number; maxMs: number }
