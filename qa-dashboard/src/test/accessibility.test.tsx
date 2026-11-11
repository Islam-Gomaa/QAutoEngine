import axe from 'axe-core';
import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '../App';
import { admin, mount, project, server } from './harness';

/*
 * Automated WCAG 2.2 A and AA checks on every screen, filled with realistic data.
 * jsdom has no layout, so axe cannot judge colour contrast here: it reports it as
 * incomplete, not as a pass. The browser journey runs the same rules on the real pages.
 */

const at = '2026-11-01T10:00:00Z';
const page = <T,>(items: T[]) => ({ items, total: items.length });

const env = { id: 'e1', targetId: 't1', targetName: 'Shop', name: 'Fixture', classification: 'LOCAL', baseUrl: 'http://fixture.qa.internal:8090/', mode: 'READ_ONLY', channel: 'WEB', description: 'Local fixture', active: true, applicationActive: true, executable: true, includePaths: [], excludePaths: [], apiSpecPath: null, apiSlowMillis: 1000, loginMethod: 'PASSWORD', credentialName: 'login', lastRunStatus: 'SUCCEEDED', lastRunAt: at };
const locked = { ...env, id: 'e2', name: 'Real', active: false, classification: 'EXTERNAL', baseUrl: 'https://shop.example.com/', executable: false, loginMethod: null, credentialName: null, lastRunStatus: null, lastRunAt: null };
const suite = { id: 's1', name: 'Checkout', description: 'Checkout paths', module: 'Cart', channel: 'WEB', archivedAt: null, cases: 2, automatedCases: 1 };
const testCase = { id: 'c1', title: 'Cart loads', description: 'The cart page loads', suiteId: 's1', suiteName: 'Checkout', source: 'AUTOMATED', priority: 'HIGH', status: 'READY', tags: ['smoke'], module: 'Cart', latestVersion: 2, lastVerdict: 'PASSED', archivedAt: null };
const manualCase = { ...testCase, id: 'c2', title: 'Coupon applies', source: 'MANUAL', priority: 'MEDIUM', status: 'DRAFT', tags: [], latestVersion: 1, lastVerdict: null };
const run = { id: 'r1aaaaaa', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', suiteId: null, suiteName: null, status: 'SUCCEEDED', createdAt: at, finishedAt: at, stopReason: null, startedBy: 'admin@qa.local', passed: 7, failed: 2, inconclusive: 1, blocked: 1 };
const defect = { id: 'd1', number: 12, type: 'SERVER_ERROR', severity: 'HIGH', priority: 'HIGH', title: 'Crash on cart', url: '/cart', detail: 'HTTP 500', expectedResult: 'Loads', status: 'OPEN', module: 'Cart', source: 'AUTOMATED', occurrences: 2, firstSeenRun: 'r1aaaaaa', lastSeenRun: 'r1aaaaaa', application: 'Shop', createdAt: at };
const evidence = { id: 'v1', runId: 'r1aaaaaa', step: 2, type: 'SCREENSHOT', sha256: 'a'.repeat(64), size: 2048, createdAt: at, expiresAt: '2026-12-01T00:00:00Z', legalHold: false, inline: true };
const activity = { at, actor: 'admin@qa.local', action: 'RUN_STARTED', object: 'Run r1aaaaaa', detail: 'Fixture', projectId: 'p1', projectName: 'Shop' };
const trend = [{ day: '2026-10-31', runs: 2, passed: 9, failed: 1, passRate: 90, defectsOpened: 1, defectsClosed: 0 }, { day: '2026-11-01', runs: 1, passed: 4, failed: 2, passRate: 67, defectsOpened: 0, defectsClosed: 1 }];
const counts = [{ name: 'HIGH', count: 2 }, { name: 'LOW', count: 1 }];
const overview = {
  days: 30,
  execution: { runs: 3, succeeded: 2, failed: 1, cancelled: 0, active: 0, passed: 13, failedSteps: 3, blocked: 1, inconclusive: 1, passRate: 81 },
  projects: [{ project: 'Shop', runs: 3, passRate: 81, openDefects: 2, criticalDefects: 0, fixedDefects: 1, testCases: 2 }],
  coverage: [{ project: 'Shop', total: 2, automated: 1, manual: 1, ready: 1, draft: 1, deprecated: 0, inSuite: 2, executed: 1 }],
  defects: { bySeverity: counts, byStatus: [{ name: 'OPEN', count: 2 }], byType: [{ name: 'SERVER_ERROR', count: 2 }] },
  channels: [{ channel: 'WEB', runs: 2, passed: 9, failed: 2, blocked: 1, passRate: 82, defects: 2 }, { channel: 'API', runs: 1, passed: 4, failed: 1, blocked: 0, passRate: 80, defects: 0 }],
  trend,
};

const routes = {
  'GET /auth/me': admin,
  'GET /projects': [{ ...project(), testCases: 2, runs: 3, openDefects: 2 }],
  'GET /notifications': { items: [{ kind: 'RUN', id: 'r1aaaaaa', projectId: 'p1', title: 'Run finished', detail: 'Fixture', at, unread: true }], unread: 1 },
  'GET /dashboard': {
    stats: { projects: 1, applications: 1, testCases: 2, runs: 3, passRate: 81, failed: 3, blocked: 1, openDefects: 2 },
    trend: trend.map((t) => ({ day: t.day, runs: t.runs, passed: t.passed, failed: t.failed, blocked: 0, inconclusive: 0 })),
    distribution: { passed: 13, failed: 3, blocked: 1, inconclusive: 1 },
    recentRuns: [{ id: 'r1aaaaaa', projectId: 'p1', projectName: 'Shop', applicationName: 'Shop', environmentName: 'Fixture', channel: 'WEB', status: 'SUCCEEDED', createdAt: at, passed: 7, failed: 2, blocked: 1 }],
    recentDefects: [{ id: 'd1', projectId: 'p1', projectName: 'Shop', number: 12, title: 'Crash on cart', severity: 'HIGH', status: 'OPEN', createdAt: at }],
    projects: [{ id: 'p1', name: 'Shop', runs: 3, passRate: 81, openDefects: 2, criticalDefects: 0, lastRunStatus: 'SUCCEEDED', lastRunAt: at }],
    channels: overview.channels, activity: [activity],
  },
  'GET /projects/p1': project(),
  'GET /projects/p1/members': [{ userId: 'u1', email: 'admin@qa.local', role: 'PROJECT_ADMIN' }],
  'GET /users': [{ id: 'u1', email: 'admin@qa.local', role: 'ADMIN', disabled: false }, { id: 'u2', email: 'tester@qa.local', role: 'MEMBER', disabled: true }],
  'GET /reports/overview': overview,
  'GET /activity': [activity],
  'GET /analytics': { days: 30, trend, hotspots: [{ name: '/cart', project: 'Shop', failures: 2, total: 5 }], slowest: [{ operation: 'GET /items', project: 'Shop', calls: 4, averageMs: 120, maxMs: 300 }], defectTypes: counts },
  'GET /projects/p1/targets': [{ id: 't1', name: 'Shop', type: 'WEB', description: 'Storefront', active: true }],
  'GET /projects/p1/environments': [env, locked],
  'GET /projects/p1/suites': [suite],
  'GET /projects/p1/suites/s1': suite,
  'GET /projects/p1/test-cases': page([testCase, manualCase]),
  'GET /projects/p1/test-cases/c1': {
    testCase, steps: JSON.stringify([{ action: 'Open the cart', expected: 'The total shows' }, { action: 'Remove an item', expected: 'The total drops' }]),
    versions: [{ version: 2, steps: JSON.stringify([{ action: 'Open the cart', expected: 'The total shows' }]), contentHash: 'b'.repeat(64) }, { version: 1, steps: '[]', contentHash: 'c'.repeat(64) }],
    history: [{ kind: 'RUN', verdict: 'PASSED', at, runId: 'r1aaaaaa', notes: '', by: 'admin@qa.local' }], defects: [{ id: 'd1', number: 12, title: 'Crash on cart', severity: 'HIGH', status: 'OPEN' }],
  },
  'GET /projects/p1/runs': page([run, { ...run, id: 'r2bbbbbb', channel: 'API', status: 'RUNNING', finishedAt: null }]),
  'GET /projects/p1/runs/r1aaaaaa': run,
  'GET /projects/p1/runs/r1aaaaaa/progress': { run, attempt: 1, jobState: 'DONE', currentOperation: '', steps: 2, defects: 1, durationSeconds: 42 },
  'GET /projects/p1/runs/r1aaaaaa/steps': [
    { attempt: 1, sequence: 1, parent: 0, kind: 'OPEN_PAGE', url: 'http://fixture.qa.internal:8090/', label: 'Home', locators: [], verdict: 'PASSED', status: 200, note: '', durationMs: 0 },
    { attempt: 1, sequence: 2, parent: 1, kind: 'NAVIGATE_LINK', url: 'http://fixture.qa.internal:8090/cart', label: 'Cart', locators: ['a[href="/cart"]'], verdict: 'FAILED', status: 500, note: 'Server error', durationMs: 0 },
  ],
  'GET /projects/p1/runs/r1aaaaaa/findings': [{ defectId: 'd1', number: 12, type: 'SERVER_ERROR', severity: 'HIGH', title: 'Crash on cart', url: '/cart', detail: 'HTTP 500', step: 2 }],
  'GET /projects/p1/runs/r1aaaaaa/api-metrics': [],
  'GET /projects/p1/defects': page([defect]),
  'GET /projects/p1/defects/d1': {
    defect, testCases: [{ id: 'c1', title: 'Cart loads' }],
    findings: [{ runId: 'r1aaaaaa', attempt: 1, step: 2, evidence: [{ id: 'v1', type: 'SCREENSHOT', sha256: 'a'.repeat(64), size: 2048 }], reproduction: [{ sequence: 1, kind: 'OPEN_PAGE', url: '/', label: 'Home' }, { sequence: 2, kind: 'NAVIGATE_LINK', url: '/cart', label: 'Cart' }] }],
  },
  'GET /projects/p1/evidence': page([evidence, { ...evidence, id: 'v2', type: 'SIGNAL_LOG', legalHold: true }]),
  'GET /projects/p1/credentials': [
    { id: 'k1', name: 'login', environmentId: 'e1', environmentName: 'Fixture', shared: false, enabled: true, usernameHint: 'f•••r', storedFields: ['username', 'password'], createdAt: at, updatedAt: at, usedBy: ['Fixture / Default'] },
    { id: 'k2', name: 'legacy', environmentId: null, environmentName: null, shared: true, enabled: false, usernameHint: '', storedFields: [], createdAt: at, updatedAt: at, usedBy: [] },
  ],
  'GET /projects/p1/auth-profiles': [{ id: 'ap1', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', name: 'Default', method: 'PASSWORD', credentialId: 'k1',
    credentialName: 'login', credentialEnabled: true, credentialShared: false, usernameHint: 'f•••r', enabled: true, isDefault: true, loginPath: '/login', submitPath: '/login', successPath: null,
    loginFlowComplete: false, mailbox: null, updatedAt: at }],
  'GET /projects/p1/login-profiles': [
    { environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', method: 'PASSWORD', credentialId: 'k1', credentialName: 'login', loginPath: '/login', submitPath: '/login', successPath: '/' },
    { environmentId: 'e3', environmentName: 'API', applicationName: 'Shop API', channel: 'API', method: null, credentialId: null, credentialName: null, loginPath: null, submitPath: null, successPath: null },
  ],
  'GET /organization': { name: 'Acme QA' },
  'POST /notifications/seen': undefined,
};

/** Routes a screen asked for that the test did not provide. Those screens would show an error, not their content. */
function unrouted(calls: { method: string; path: string }[]) {
  return calls.map((c) => `${c.method} ${c.path.split('?')[0]}`).filter((k) => !(k in routes));
}

async function violations(): Promise<string[]> {
  const result = await axe.run(document.body, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  });
  return result.violations.map((v) => `${v.id} (${v.impact}): ${v.help} at ${v.nodes.map((n) => n.html.slice(0, 140)).join(' | ')}`);
}

async function loaded() {
  await waitFor(() => {
    expect(screen.queryByTestId('loading-state')).toBeNull();
    expect(screen.queryByRole('progressbar')).toBeNull();
  });
}

const screens: [string, string, string][] = [
  ['Dashboard', '/', 'Dashboard'],
  ['Projects', '/projects', 'Projects'],
  ['Project detail', '/projects/p1', 'Shop'],
  ['Applications and environments', '/applications', 'Applications & Environments'],
  ['Application setup', '/applications/new', 'Set up application'],
  ['Test suites', '/suites', 'Test Suites'],
  ['Suite detail', '/suites/s1', 'Checkout'],
  ['Test cases', '/cases', 'Test Cases'],
  ['Test case detail', '/cases/c1', 'Cart loads'],
  ['Test execution', '/execution', 'Test Execution'],
  ['Run detail', '/execution/r1aaaaaa', ''],
  ['Defects', '/defects', 'Defects'],
  ['Defect detail', '/defects/d1', ''],
  ['Evidence', '/evidence', 'Evidence'],
  ['Reports', '/reports', 'Reports'],
  ['Analytics', '/analytics', 'Analytics'],
  ['Users and roles', '/users', 'Users & Roles'],
  ['Credentials', '/credentials', 'Credentials'],
  ['Login profiles', '/login-profiles', 'Login Profiles'],
  ['Settings', '/settings', 'Settings'],
];

describe('accessibility, WCAG 2.2 A and AA', () => {
  it.each(screens)('%s has no violations', async (_name, path, heading) => {
    const calls = server(routes);
    mount(<App />, path);
    await screen.findByRole('heading', { level: 1, ...(heading ? { name: heading } : {}) });
    await loaded();
    expect(unrouted(calls)).toEqual([]);
    expect(screen.queryByTestId('error-state')).toBeNull();
    expect(await violations()).toEqual([]);
  });

  it('sign-in has no violations, including after a refusal', async () => {
    server({ 'GET /auth/me': new Response('', { status: 401 }), 'GET /auth/csrf': {}, 'POST /auth/login': () => new Response('', { status: 401 }) });
    mount(<App />);
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }));
    expect(await violations()).toEqual([]);
    await userEvent.type(screen.getByLabelText(/Email/), 'a@b.c');
    await userEvent.type(screen.getByLabelText(/^Password/), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await screen.findByText('Sign-in failed.');
    expect(await violations()).toEqual([]);
  });

  const dialogs: [string, string, string][] = [
    ['New project', '/projects', 'New project'],
    ['New application', '/applications', 'New application'],
    ['New suite', '/suites', 'New suite'],
    ['New test case', '/cases', 'New test case'],
    ['New defect', '/defects', 'New defect'],
    ['New credential', '/credentials', 'New credential'],
    ['New user', '/users', 'New user'],
  ];
  it.each(dialogs)('the %s form has no violations', async (_name, path, button) => {
    server(routes);
    mount(<App />, path);
    await loaded();
    await userEvent.click((await screen.findAllByRole('button', { name: button }))[0]);
    await screen.findByRole('dialog');
    expect(await violations()).toEqual([]);
  });

  it('every step of application setup has no violations, with errors showing too', async () => {
    server({ ...routes, 'POST /projects/p1/base-url-check': { baseUrl: 'http://fixture.qa.internal:8090/', origin: 'http://fixture.qa.internal:8090', executable: true } });
    mount(<App />, '/applications/new');
    await screen.findByRole('heading', { level: 1, name: 'Set up application' });
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Fix 1 field to continue.');
    expect(await violations()).toEqual([]);
    await userEvent.type(screen.getByLabelText(/Application name/), 'Shop');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { level: 2, name: 'Environments' });
    await userEvent.click(screen.getByRole('button', { name: 'Add another environment' }));
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Fix 4 fields to continue.');
    expect(await violations()).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Remove environment 2' }));
    await userEvent.type(screen.getByLabelText(/Environment name/), 'Fixture');
    await userEvent.click(screen.getByRole('button', { name: 'Use local fixture' }));
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { level: 2, name: 'Sign-in' });
    await userEvent.click(screen.getByLabelText('Sign-in for Fixture'));
    await userEvent.click(await screen.findByRole('option', { name: 'Username, password and email OTP' }));
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Confirm the mailbox is dedicated to testing.');
    expect(await violations()).toEqual([]);
    await userEvent.type(screen.getByRole('textbox', { name: /^Username/ }), 'qa-bot');
    await userEvent.type(screen.getByLabelText(/^Password/), 'pw');
    await userEvent.type(screen.getByLabelText(/^Mailbox address/), 'otp@mail.example');
    await userEvent.type(screen.getByLabelText(/^IMAP server/), 'imap.mail.example');
    await userEvent.type(screen.getByLabelText(/^Mailbox password/), 'mb');
    await userEvent.click(screen.getByLabelText('This is a dedicated test mailbox I am authorized to use'));
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('heading', { level: 2, name: 'Review and save' });
    expect(await violations()).toEqual([]);
  });

  it('the login profile form has no violations, with the email OTP mailbox and its errors showing', async () => {
    server(routes);
    mount(<App />, '/login-profiles');
    await loaded();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Configure' }))[0]);
    await userEvent.click(await screen.findByLabelText('Method'));
    await userEvent.click(await screen.findByRole('option', { name: 'Password and email OTP' }));
    await userEvent.type(screen.getByLabelText(/^Mailbox address/), 'otp@mail.example');
    await userEvent.type(screen.getByLabelText(/^IMAP server/), 'imap.mail.example');
    await userEvent.type(screen.getByLabelText(/^Mailbox password/), 'mb');
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    await screen.findByText('Check the highlighted mailbox fields.');
    expect(await violations()).toEqual([]);
  });

  it('the credential edit form has no violations', async () => {
    server(routes);
    mount(<App />, '/credentials');
    await loaded();
    await userEvent.click((await screen.findAllByRole('button', { name: 'Edit' }))[0]);
    await screen.findByRole('dialog', { name: 'Edit login' });
    expect(await violations()).toEqual([]);
  });

  it('names the notifications button with what its badge shows, past the cap as well', async () => {
    server({ ...routes, 'GET /notifications': { items: [], unread: 12 } });
    mount(<App />);
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' });
    await loaded();
    const button = await screen.findByRole('button', { name: 'Notifications, 9+ unread' });
    expect(button).toHaveTextContent('9+');
    expect(await violations()).toEqual([]);
  });

  // jsdom has no layout, so axe cannot see these boxes scroll. The browser journey found them; this keeps them reachable.
  const scrolling: [string, string, string][] = [
    ['Recent activity', '/', 'Dashboard'],
    ['Step log', '/execution/r1aaaaaa', ''],
  ];
  it.each(scrolling)('the %s scroll box can be reached and scrolled by keyboard', async (name, path, heading) => {
    server(routes);
    mount(<App />, path);
    await screen.findByRole('heading', { level: 1, ...(heading ? { name: heading } : {}) });
    await loaded();
    const region = await screen.findByRole('region', { name });
    expect(region).toHaveAttribute('tabindex', '0');
  });

  it('a chart shown as a table can be reached and scrolled by keyboard', async () => {
    server(routes);
    mount(<App />);
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' });
    await loaded();
    await userEvent.click((await screen.findAllByRole('button', { name: /^Show table: / }))[0]);
    const region = await screen.findByRole('region', { name: /, table$/ });
    expect(region).toHaveAttribute('tabindex', '0');
    expect(await violations()).toEqual([]);
  });

  it('the account menu and notifications have no violations', async () => {
    server(routes);
    mount(<App />);
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' });
    await loaded();
    await userEvent.click(screen.getByRole('button', { name: 'Account' }));
    await screen.findByRole('menuitem', { name: 'Sign out' });
    expect(await violations()).toEqual([]);
    // Keyboard focus starts on the first action, not on the signed-in identity above it.
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'Settings' })));
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
    await userEvent.click(screen.getByRole('button', { name: /^Notifications/ }));
    await screen.findByText('Run finished');
    expect(await violations()).toEqual([]);
  });
});
