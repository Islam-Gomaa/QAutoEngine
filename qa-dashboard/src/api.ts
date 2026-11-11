function xsrf(): string {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

/** Fired when the server no longer accepts this session, such as after a password reset. */
export const SESSION_ENDED = 'qa:session-ended';

export class ApiError extends Error {
  constructor(public status: number, message?: string) {
    super(message || `Request failed (${status})`);
  }
}

async function failure(response: Response): Promise<ApiError> {
  let message = '';
  try {
    const body = await response.json();
    message = body.detail || body.message || '';
  } catch { /* the status alone will do */ }
  if (response.status === 403 && !message) message = 'Your role does not allow this.';
  if (response.status === 404 && !message) message = 'Not found, or you do not have access.';
  return new ApiError(response.status, message);
}

export async function api<T>(path: string, method = 'GET', body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    method,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf(), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 401 && !path.startsWith('/auth/')) window.dispatchEvent(new Event(SESSION_ENDED));
  if (!response.ok) throw await failure(response);
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== null && v !== undefined && v !== '') search.set(k, String(v)); });
  const s = search.toString();
  return s ? `?${s}` : '';
}

export type Role = 'VIEWER' | 'TESTER' | 'QA_LEAD' | 'PROJECT_ADMIN';
const RANK: Role[] = ['VIEWER', 'TESTER', 'QA_LEAD', 'PROJECT_ADMIN'];
/** Decides what to offer. The server enforces the same rule on every request. */
export const can = (role: Role | null | undefined, needed: Role) => !!role && RANK.indexOf(role) >= RANK.indexOf(needed);

export interface Page<T> { items: T[]; total: number }
export interface Me { userId: string; organizationId: string; email: string; organizationAdmin: boolean }
export interface Project { id: string; name: string; description: string; archivedAt: string | null; myRole: Role | null; testCases: number; runs: number; openDefects: number }
export interface Member { userId: string; email: string; role: Role }
export interface Target { id: string; name: string; type: 'WEB' | 'API' | 'MOBILE'; description: string; active: boolean }
/** What the server would store for a base URL. Nothing is contacted to work this out. */
export interface BaseUrlCheck { baseUrl: string; origin: string; executable: boolean }
export interface Environment {
  id: string; targetId: string; targetName: string; name: string; classification: string; baseUrl: string; mode: string; channel: 'WEB' | 'API'; description: string;
  active: boolean; applicationActive: boolean; executable: boolean; includePaths: string[]; excludePaths: string[]; apiSpecPath: string | null; apiSlowMillis: number; loginMethod: string | null; credentialName: string | null;
  lastRunStatus: string | null; lastRunAt: string | null;
}
export interface Suite { id: string; name: string; description: string; module: string; channel: 'WEB' | 'API'; archivedAt: string | null; cases: number; automatedCases: number }
export interface TestCase {
  id: string; title: string; description: string; suiteId: string | null; suiteName: string | null; source: 'AUTOMATED' | 'MANUAL'; priority: string; status: string; tags: string[]; module: string;
  latestVersion: number; lastVerdict: string | null; archivedAt: string | null;
}
export interface CaseDetail {
  testCase: TestCase; steps: string; versions: { version: number; steps: string; contentHash: string }[];
  history: { kind: string; verdict: string; at: string; runId: string | null; notes: string; by: string }[];
  defects: { id: string; number: number; title: string; severity: string; status: string }[];
}
export interface Run {
  id: string; environmentId: string; environmentName: string; applicationName: string; channel: 'WEB' | 'API'; suiteId: string | null; suiteName: string | null; status: string;
  createdAt: string; finishedAt: string | null; stopReason: string | null; startedBy: string; passed: number; failed: number; inconclusive: number; blocked: number;
}
export interface Progress { run: Run; attempt: number; jobState: string; currentOperation: string; steps: number; defects: number; durationSeconds: number }
export interface Step { attempt: number; sequence: number; parent: number; kind: string; url: string; label: string; locators: string[]; verdict: string; status: number; note: string; durationMs: number }
export interface RunFinding { defectId: string; number: number; type: string; severity: string; title: string; url: string; detail: string; step: number }
export interface Defect {
  id: string; number: number; type: string; severity: string; priority: string; title: string; url: string; detail: string; expectedResult: string; status: string; module: string; source: string;
  occurrences: number; firstSeenRun: string | null; lastSeenRun: string | null; application: string | null; createdAt: string;
}
export interface EvidenceLink { id: string; type: string; sha256: string; size: number }
export interface ReproStep { sequence: number; kind: string; url: string; label: string }
export interface Trace { defect: Defect; findings: { runId: string; attempt: number; step: number; evidence: EvidenceLink[]; reproduction: ReproStep[] }[]; testCases: { id: string; title: string }[] }
export interface EvidenceItem { id: string; runId: string; step: number; type: string; sha256: string; size: number; createdAt: string; expiresAt: string; legalHold: boolean; inline: boolean }
export interface ApiMetric { operation: string; calls: number; passed: number; failed: number; blocked: number; averageMs: number; p95Ms: number; maxMs: number }
/** Masks and names only. No field in any response carries a secret value. */
export interface Credential {
  id: string; name: string; environmentId: string | null; environmentName: string | null; shared: boolean; enabled: boolean; usernameHint: string;
  storedFields: string[]; createdAt: string; updatedAt: string; usedBy: string[];
}
export interface Mailbox {
  address: string; host: string; security: 'IMAPS' | 'STARTTLS'; port: number; folder: string; senderFilter: string; subjectFilter: string; codePattern: string;
  timeoutSeconds: number; pollSeconds: number; dedicatedConfirmedBy: string; dedicatedConfirmedAt: string;
}
export interface AuthProfile {
  id: string; environmentId: string; environmentName: string; applicationName: string; channel: 'WEB' | 'API'; name: string; method: string;
  credentialId: string; credentialName: string; credentialEnabled: boolean; credentialShared: boolean; usernameHint: string; enabled: boolean; isDefault: boolean;
  loginPath: string | null; submitPath: string | null; successPath: string | null; loginFlowComplete: boolean; mailbox: Mailbox | null; updatedAt: string;
}
export interface LoginProfile {
  environmentId: string; environmentName: string; applicationName: string; channel: string; method: string | null; credentialId: string | null; credentialName: string | null;
  loginPath: string | null; submitPath: string | null; successPath: string | null;
}
export interface User { id: string; email: string; role: string; disabled: boolean }
export interface Activity { at: string; actor: string; action: string; object: string; detail: string; projectId: string | null; projectName: string | null }
export interface Day { day: string; runs: number; passed: number; failed: number; blocked: number; inconclusive: number }
export interface Dashboard {
  stats: { projects: number; applications: number; testCases: number; runs: number; passRate: number | null; failed: number; blocked: number; openDefects: number };
  trend: Day[]; distribution: { passed: number; failed: number; blocked: number; inconclusive: number };
  recentRuns: { id: string; projectId: string; projectName: string; applicationName: string; environmentName: string; channel: string; status: string; createdAt: string; passed: number; failed: number; blocked: number }[];
  recentDefects: { id: string; projectId: string; projectName: string; number: number; title: string; severity: string; status: string; createdAt: string }[];
  projects: { id: string; name: string; runs: number; passRate: number | null; openDefects: number; criticalDefects: number; lastRunStatus: string | null; lastRunAt: string | null }[];
  channels: { channel: string; runs: number; passed: number; failed: number; blocked: number; passRate: number | null }[];
  activity: Activity[];
}
export interface SearchHit { kind: 'PROJECT' | 'SUITE' | 'TEST_CASE' | 'DEFECT'; id: string; projectId: string; title: string; subtitle: string }
export interface Notifications { items: { kind: 'RUN' | 'DEFECT'; id: string; projectId: string; title: string; detail: string; at: string; unread: boolean }[]; unread: number }
export interface Count { name: string; count: number }
export interface Trend { day: string; runs: number; passed: number; failed: number; passRate: number | null; defectsOpened: number; defectsClosed: number }
export interface Overview {
  days: number;
  execution: { runs: number; succeeded: number; failed: number; cancelled: number; active: number; passed: number; failedSteps: number; blocked: number; inconclusive: number; passRate: number | null };
  projects: { project: string; runs: number; passRate: number | null; openDefects: number; criticalDefects: number; fixedDefects: number; testCases: number }[];
  coverage: { project: string; total: number; automated: number; manual: number; ready: number; draft: number; deprecated: number; inSuite: number; executed: number }[];
  defects: { bySeverity: Count[]; byStatus: Count[]; byType: Count[] };
  channels: { channel: string; runs: number; passed: number; failed: number; blocked: number; passRate: number | null; defects: number }[];
  trend: Trend[];
}
export interface Analytics {
  days: number; trend: Trend[]; hotspots: { name: string; project: string; failures: number; total: number }[];
  slowest: { operation: string; project: string; calls: number; averageMs: number; maxMs: number }[]; defectTypes: Count[];
}
