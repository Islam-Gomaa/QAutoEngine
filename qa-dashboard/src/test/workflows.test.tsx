import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '../App';
import { admin, member, mount, project, server, type Call } from './harness';

const empty = { items: [], total: 0 };
const dashboard = {
  stats: { projects: 1, applications: 2, testCases: 3, runs: 4, passRate: 80, failed: 5, blocked: 6, openDefects: 7 },
  trend: [{ day: '2026-11-01', runs: 1, passed: 4, failed: 1, blocked: 0, inconclusive: 0 }], distribution: { passed: 4, failed: 1, blocked: 0, inconclusive: 0 },
  recentRuns: [], recentDefects: [], projects: [], channels: [], activity: [],
};
const shell = { 'GET /notifications': { items: [], unread: 0 }, 'GET /dashboard': dashboard };
const env = (over: object) => ({ id: 'e1', targetId: 't1', targetName: 'Shop', name: 'Fixture', classification: 'LOCAL', baseUrl: 'http://fixture.qa.internal:8090/', mode: 'READ_ONLY', channel: 'WEB', description: '', active: true, applicationActive: true, executable: true, includePaths: [], excludePaths: [], apiSpecPath: null, apiSlowMillis: 1000, loginMethod: null, credentialName: null, lastRunStatus: null, lastRunAt: null, ...over });
const signedIn = (me = admin, role: string | null = 'PROJECT_ADMIN') => ({ ...shell, 'GET /auth/me': me, 'GET /projects': [project(role)] });

describe('signing in', () => {
  it('reports a refused login without detail', async () => {
    server({ 'GET /auth/me': new Response('', { status: 401 }), 'GET /auth/csrf': {}, 'POST /auth/login': () => new Response('', { status: 401 }) });
    mount(<App />);
    await userEvent.type(await screen.findByLabelText(/Email/), 'a@b.c');
    await userEvent.type(screen.getByLabelText(/^Password/), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Sign-in failed.')).toBeInTheDocument();
  });
});

describe('session', () => {
  it('signs in, signs out, and drops the previous account', async () => {
    let signedIn2 = false;
    server({ ...signedIn(), 'GET /auth/me': () => (signedIn2 ? admin : new Response('', { status: 401 })), 'GET /auth/csrf': {}, 'POST /auth/login': () => { signedIn2 = true; return admin; }, 'POST /auth/logout': () => { signedIn2 = false; } });
    mount(<App />);
    await userEvent.type(await screen.findByLabelText(/Email/), 'admin@qa.local');
    await userEvent.type(screen.getByLabelText(/^Password/), 'right');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Account' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Sign out' }));
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByTestId('stat-Open defects')).toBeNull();
  });
  it('returns to sign-in when the server ends the session', async () => {
    server({ ...signedIn(), 'GET /dashboard': () => new Response('', { status: 401 }) });
    mount(<App />);
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });
});

describe('navigation', () => {
  it('shows an organization admin everything', async () => {
    server(signedIn());
    mount(<App />);
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    for (const item of ['Dashboard', 'Projects', 'Applications & Environments', 'Test Suites', 'Test Cases', 'Test Execution', 'Defects', 'Evidence', 'Reports', 'Analytics', 'Users & Roles', 'Credentials', 'Login Profiles', 'Settings']) {
      expect(await within(nav).findByRole('link', { name: item })).toBeInTheDocument();
    }
  });
  it('hides administration from a viewer, and sends a direct visit home', async () => {
    server(signedIn(member, 'VIEWER'));
    mount(<App />, '/users');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    await within(nav).findByRole('link', { name: 'Settings' });
    expect(within(nav).queryByRole('link', { name: 'Users & Roles' })).toBeNull();
    expect(within(nav).queryByRole('link', { name: 'Credentials' })).toBeNull();
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
  });
  it('shows real totals, and no rate when there are no results', async () => {
    server({ ...signedIn(), 'GET /dashboard': { ...dashboard, stats: { ...dashboard.stats, passRate: null } } });
    mount(<App />);
    expect(await screen.findByTestId('stat-Open defects')).toHaveTextContent('7');
    expect(screen.getByTestId('stat-Pass rate')).toHaveTextContent('No results');
  });
  it('shows an error state when the dashboard fails', async () => {
    server({ ...signedIn(), 'GET /dashboard': () => new Response(JSON.stringify({ detail: 'Database down' }), { status: 500 }) });
    mount(<App />);
    expect(await screen.findByTestId('error-state')).toHaveTextContent('Database down');
  });
});

describe('projects', () => {
  it('creates a project and opens it', async () => {
    const calls = server({ ...signedIn(), 'POST /projects': project(), 'GET /projects/p1': project(), 'GET /reports/overview': { execution: { passRate: null }, coverage: [] } });
    mount(<App />, '/projects');
    await userEvent.click((await screen.findAllByRole('button', { name: 'New project' }))[0]);
    await userEvent.type(await screen.findByLabelText(/Project name/), 'Shop');
    await userEvent.click(screen.getByRole('button', { name: 'Create project' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.path === '/projects')).toBe(true));
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({ name: 'Shop' });
    expect(await screen.findByRole('heading', { name: 'Shop' })).toBeInTheDocument();
  });
  it('offers no creation to members', async () => {
    server({ ...signedIn(member, 'TESTER') });
    mount(<App />, '/projects');
    await screen.findByRole('heading', { name: 'Projects' });
    await screen.findByTestId('project-row');
    expect(screen.queryByRole('button', { name: 'New project' })).toBeNull();
  });
});

describe('applications', () => {
  it('tells the fixture from a locked real target', async () => {
    server({ ...signedIn(), 'GET /projects/p1/targets': [{ id: 't1', name: 'Shop', type: 'WEB', description: '', active: true }], 'GET /projects/p1/environments': [env({}), env({ id: 'e2', name: 'Real', baseUrl: 'https://shop.example.com/', executable: false })] });
    mount(<App />, '/applications');
    expect(await screen.findByText('Local fixture')).toBeInTheDocument();
    expect(screen.getByText('Real target, locked')).toBeInTheDocument();
  });
  it('does not offer mobile', async () => {
    server({ ...signedIn(), 'GET /projects/p1/targets': [], 'GET /projects/p1/environments': [] });
    mount(<App />, '/applications');
    await userEvent.click((await screen.findAllByRole('button', { name: 'New application' }))[0]);
    await userEvent.click(await screen.findByLabelText('Application type'));
    expect(await screen.findByRole('option', { name: 'API' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /mobile/i })).toBeNull();
  });
});

describe('application status', () => {
  it('shows what is inactive and switches it back only after confirming', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/targets': [{ id: 't1', name: 'Shop', type: 'WEB', description: '', active: false }],
      'GET /projects/p1/environments': [env({ active: false })], 'POST /projects/p1/targets/t1/activate': { id: 't1', name: 'Shop', type: 'WEB', description: '', active: true } });
    mount(<App />, '/applications');
    const card = await screen.findByTestId('application-card');
    expect(within(card).getAllByText('Inactive')).toHaveLength(2);
    await userEvent.click(within(card).getAllByRole('button', { name: 'Activate' })[0]);
    const dialog = await screen.findByRole('dialog', { name: 'Activate application' });
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Activate' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.path === '/projects/p1/targets/t1/activate')).toBe(true));
  });
  it('cannot start a run on an inactive environment or application', async () => {
    server({ ...signedIn(), 'GET /projects/p1/runs': empty, 'GET /projects/p1/suites': [],
      'GET /projects/p1/environments': [env({}), env({ id: 'e3', name: 'Paused', active: false }), env({ id: 'e4', name: 'Parked', applicationActive: false })] });
    mount(<App />, '/execution');
    await userEvent.click(await screen.findByLabelText('Environment'));
    expect(await screen.findByRole('option', { name: /Paused.*inactive/ })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('option', { name: /Parked.*inactive/ })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('option', { name: /Fixture/ })).not.toHaveAttribute('aria-disabled');
  });
});

describe('application setup', () => {
  /** Stands in for the server's rules: user information is refused, everything else comes back lower-cased with a slash. */
  const check = (c: Call) => {
    const url = (c.body as { baseUrl: string }).baseUrl;
    if (url.includes('@')) return new Response(JSON.stringify({ detail: 'Base URL must not contain user information' }), { status: 409 });
    return { baseUrl: url.toLowerCase().replace(/\/?$/, '/'), origin: '', executable: url.includes('fixture.qa.internal') };
  };
  const routes = { ...signedIn(), 'GET /projects/p1/targets': [], 'GET /projects/p1/environments': [], 'POST /projects/p1/base-url-check': check };
  const next = () => userEvent.click(screen.getByRole('button', { name: 'Next' }));

  it('starts from Applications', async () => {
    server(routes);
    mount(<App />, '/applications');
    await userEvent.click(await screen.findByRole('link', { name: 'Set up application' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Set up application' })).toBeInTheDocument();
    expect(screen.getByText('Step 1 of 3')).toBeInTheDocument();
  });

  it('is only for project admins', async () => {
    server({ ...routes, ...signedIn(member, 'TESTER') });
    mount(<App />, '/applications/new');
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument();
  });

  it('checks each step before moving on, and keeps what was entered when going back', async () => {
    const calls = server(routes);
    mount(<App />, '/applications/new');
    await userEvent.click(await screen.findByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Enter the application name.')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Fix 1 field to continue.');
    await userEvent.type(screen.getByLabelText(/Application name/), 'Shop');
    await userEvent.type(screen.getByLabelText('Description'), 'Storefront');
    await next();
    const heading = await screen.findByRole('heading', { level: 2, name: 'Environments' });
    await waitFor(() => expect(document.activeElement).toBe(heading));

    await next();
    expect(await screen.findByText('Enter the environment name.')).toBeInTheDocument();
    expect(screen.getByText('Enter the base URL.')).toBeInTheDocument();
    expect(calls.some((c) => c.path.endsWith('/base-url-check'))).toBe(false);
    await userEvent.type(screen.getByLabelText(/Environment name/), 'Staging');
    await userEvent.type(screen.getByLabelText(/Base URL/), 'staging.shop.example');
    await next();
    expect(await screen.findByText('Use a full http:// or https:// address.')).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText(/Base URL/));
    await userEvent.type(screen.getByLabelText(/Base URL/), 'https://user:pw@staging.shop.example');
    await next();
    expect(await screen.findByText('Base URL must not contain user information')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Environments' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(await screen.findByLabelText(/Application name/)).toHaveValue('Shop');
    expect(screen.getByLabelText('Description')).toHaveValue('Storefront');
    await next();
    expect(await screen.findByLabelText(/Environment name/)).toHaveValue('Staging');
    expect(screen.getByLabelText(/Base URL/)).toHaveValue('https://user:pw@staging.shop.example');
  });

  it('refuses two environments with the same name', async () => {
    server(routes);
    mount(<App />, '/applications/new');
    await userEvent.type(await screen.findByLabelText(/Application name/), 'Shop');
    await next();
    await userEvent.click(await screen.findByRole('button', { name: 'Add another environment' }));
    for (const field of screen.getAllByLabelText(/Environment name/)) await userEvent.type(field, 'QA');
    for (const button of screen.getAllByRole('button', { name: 'Use local fixture' })) await userEvent.click(button);
    await next();
    expect(await screen.findByText('Each environment needs a different name.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Remove environment 2' }));
    await next();
    expect(await screen.findByRole('heading', { level: 2, name: 'Review and save' })).toBeInTheDocument();
  });

  it('offers UAT, and not production', async () => {
    server(routes);
    mount(<App />, '/applications/new');
    await userEvent.type(await screen.findByLabelText(/Application name/), 'Shop');
    await next();
    await userEvent.click(await screen.findByLabelText('Environment type'));
    expect(await screen.findByRole('option', { name: 'UAT' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /production/i })).toBeNull();
  });

  it('reviews the URLs as they will be saved, then creates everything in one request', async () => {
    const calls = server({ ...routes, 'POST /projects/p1/targets': { id: 't9', name: 'Shop', type: 'WEB', description: '', active: true } });
    mount(<App />, '/applications/new');
    await userEvent.type(await screen.findByLabelText(/Application name/), 'Shop');
    await next();
    await userEvent.type(await screen.findByLabelText(/Environment name/), 'Fixture');
    await userEvent.click(screen.getByRole('button', { name: 'Use local fixture' }));
    await userEvent.click(screen.getByRole('button', { name: 'Add another environment' }));
    await userEvent.type(screen.getAllByLabelText(/Environment name/)[1], 'Acceptance');
    await userEvent.click(screen.getAllByLabelText('Environment type')[1]);
    await userEvent.click(await screen.findByRole('option', { name: 'UAT' }));
    await userEvent.type(screen.getAllByLabelText(/Base URL/)[1], 'HTTPS://UAT.Shop.Example');
    await userEvent.click(screen.getAllByLabelText('Active')[1]);
    await next();

    await screen.findByRole('heading', { level: 2, name: 'Review and save' });
    const rows = screen.getAllByTestId('review-environment');
    expect(rows[0]).toHaveTextContent('http://fixture.qa.internal:8090/');
    expect(rows[0]).toHaveTextContent('Local fixture');
    expect(rows[1]).toHaveTextContent('https://uat.shop.example/');
    expect(rows[1]).toHaveTextContent('Real target, locked');
    expect(rows[1]).toHaveTextContent('Inactive');
    expect(calls.some((c) => c.method === 'POST' && c.path === '/projects/p1/targets')).toBe(false);

    await userEvent.click(screen.getByRole('button', { name: 'Save application' }));
    await screen.findByRole('heading', { level: 1, name: 'Applications & Environments' });
    const saves = calls.filter((c) => c.method === 'POST' && c.path === '/projects/p1/targets');
    expect(saves).toHaveLength(1);
    expect(saves[0].body).toEqual({
      name: 'Shop', type: 'WEB', description: '', active: true, environments: [
        { name: 'Fixture', classification: 'QA', baseUrl: 'http://fixture.qa.internal:8090/', description: '', active: true, channel: 'WEB', apiSpecPath: null },
        { name: 'Acceptance', classification: 'UAT', baseUrl: 'https://uat.shop.example/', description: '', active: false, channel: 'WEB', apiSpecPath: null },
      ],
    });
  });

  it('shows a refused save and keeps the review open', async () => {
    server({ ...routes, 'POST /projects/p1/targets': () => new Response(JSON.stringify({ detail: 'Request conflicts with existing data' }), { status: 409 }) });
    mount(<App />, '/applications/new');
    await userEvent.type(await screen.findByLabelText(/Application name/), 'Shop');
    await next();
    await userEvent.type(await screen.findByLabelText(/Environment name/), 'Fixture');
    await userEvent.click(screen.getByRole('button', { name: 'Use local fixture' }));
    await next();
    await userEvent.click(await screen.findByRole('button', { name: 'Save application' }));
    expect(await screen.findByText(/Request conflicts with existing data/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Review and save' })).toBeInTheDocument();
  });
});

describe('execution', () => {
  const routes = { ...signedIn(), 'GET /projects/p1/runs': empty, 'GET /projects/p1/suites': [], 'GET /projects/p1/environments': [env({}), env({ id: 'e2', name: 'Real', executable: false })] };
  it('starts a fixture run with an idempotency key, and cannot pick a locked target', async () => {
    const calls = server({ ...routes, 'POST /projects/p1/runs': { id: 'r1' }, 'GET /projects/p1/runs/r1/progress': () => new Response('{}', { status: 404 }) });
    mount(<App />, '/execution');
    await userEvent.click(await screen.findByLabelText('Environment'));
    expect(await screen.findByRole('option', { name: /Real.*locked/ })).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(screen.getByRole('option', { name: /Fixture/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Start fixture run' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ environmentId: 'e1', suiteId: null }));
  });
  it('shows the server refusal', async () => {
    server({ ...routes, 'POST /projects/p1/runs': () => new Response(JSON.stringify({ detail: 'Real targets are locked' }), { status: 409 }) });
    mount(<App />, '/execution');
    await userEvent.click(await screen.findByLabelText('Environment'));
    await userEvent.click(await screen.findByRole('option', { name: /Fixture/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Start fixture run' }));
    expect(await screen.findByTestId('error-state')).toHaveTextContent('Real targets are locked');
  });
  it('does not let a viewer start runs', async () => {
    server({ ...routes, ...signedIn(member, 'VIEWER') });
    mount(<App />, '/execution');
    expect(await screen.findByText('Your role can view runs but not start them.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start fixture run' })).toBeNull();
  });
});

describe('evidence', () => {
  const evidence = (type: string) => ({ items: [{ id: 'v1', runId: 'r1aaaaaa', step: 2, type, sha256: 'a'.repeat(64), size: 10, createdAt: '2026-11-01T00:00:00Z', expiresAt: '2026-12-01T00:00:00Z', legalHold: false, inline: true }], total: 1 });
  it('shows hostile text as text', async () => {
    const hostile = '<img src=x onerror="window.hacked=1"><script>window.hacked=1</script>';
    server({ ...signedIn(), 'GET /projects/p1/evidence': evidence('SIGNAL_LOG'), 'GET /projects/p1/evidence/v1/content': () => new Response(hostile) });
    mount(<App />, '/evidence');
    await userEvent.click(await screen.findByRole('button', { name: 'View' }));
    const shown = await screen.findByTestId('evidence-text');
    expect(shown).toHaveTextContent('onerror');
    expect(shown.querySelector('img,script')).toBeNull();
    expect((window as unknown as { hacked?: number }).hacked).toBeUndefined();
  });
  it('never renders a DOM snapshot', async () => {
    server({ ...signedIn(), 'GET /projects/p1/evidence': evidence('DOM_SNAPSHOT') });
    mount(<App />, '/evidence');
    await userEvent.click(await screen.findByRole('button', { name: 'View' }));
    expect(await screen.findByRole('link', { name: 'Download DOM snapshot' })).toHaveAttribute('download');
    expect(document.querySelector('iframe')).toBeNull();
  });
});

describe('defects', () => {
  const defect = { id: 'd1', number: 12, type: 'SERVER_ERROR', severity: 'HIGH', priority: 'HIGH', title: 'Crash', url: '/crash', detail: 'HTTP 500', expectedResult: 'Loads', status: 'OPEN', module: '', source: 'AUTOMATED', occurrences: 2, firstSeenRun: 'r1', lastSeenRun: 'r1', application: 'Shop', createdAt: '2026-11-01T00:00:00Z' };
  it('filters on the server', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/defects': { items: [defect], total: 1 } });
    mount(<App />, '/defects');
    await screen.findByTestId('defect-row');
    await userEvent.click(screen.getByLabelText('Severity'));
    await userEvent.click(await screen.findByRole('option', { name: 'Critical' }));
    await waitFor(() => expect(calls.some((c) => c.path.includes('severity=CRITICAL'))).toBe(true));
  });
  it('lets a lead change status, and only shows it to a viewer', async () => {
    const trace = { defect, findings: [], testCases: [] };
    const calls = server({ ...signedIn(), 'GET /projects/p1/defects/d1': trace, 'PATCH /projects/p1/defects/d1': defect });
    const first = mount(<App />, '/defects/d1');
    await userEvent.click(await screen.findByLabelText('Status'));
    await userEvent.click(await screen.findByRole('option', { name: 'Fixed' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PATCH')?.body).toEqual({ status: 'FIXED' }));
    first.unmount();
    server({ ...signedIn(member, 'VIEWER'), 'GET /projects/p1/defects/d1': trace });
    mount(<App />, '/defects/d1');
    await screen.findByText('Triage');
    expect(screen.queryByLabelText('Status')).toBeNull();
  });
});

describe('credentials', () => {
  it('lists names only and clears nothing into the page', async () => {
    server({ ...signedIn(), 'GET /projects/p1/credentials': [{ id: 'c1', name: 'login', createdAt: '2026-11-01T00:00:00Z', usedBy: ['Fixture'] }] });
    mount(<App />, '/credentials');
    expect(await screen.findByText('login')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled();
  });
  it('sends what was typed and uses password fields', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/credentials': [], 'POST /projects/p1/credentials': { id: 'c1' } });
    mount(<App />, '/credentials');
    await userEvent.click(await screen.findByRole('button', { name: 'New credential' }));
    await userEvent.type(await screen.findByLabelText(/Credential name/), 'login');
    const secret = screen.getByLabelText('Password');
    expect(secret).toHaveAttribute('type', 'password');
    await userEvent.type(secret, 's3cret');
    await userEvent.click(screen.getByRole('button', { name: 'Save credential' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ name: 'login', password: 's3cret' }));
  });
});

describe('reports', () => {
  it('links real exports and shows an empty period honestly', async () => {
    const zero = { execution: { runs: 0, succeeded: 0, failed: 0, cancelled: 0, active: 0, passed: 0, failedSteps: 0, blocked: 0, inconclusive: 0, passRate: null }, projects: [], coverage: [], defects: { bySeverity: [], byStatus: [], byType: [] }, channels: [], trend: [{ day: '2026-11-01', runs: 0, passed: 0, failed: 0, passRate: null, defectsOpened: 0, defectsClosed: 0 }], days: 30 };
    server({ ...signedIn(), 'GET /reports/overview': zero });
    mount(<App />, '/reports');
    expect(await screen.findByRole('link', { name: 'Export Excel' })).toHaveAttribute('href', expect.stringContaining('/api/v1/reports/export.xlsx'));
    expect(await screen.findByTestId('stat-Pass rate')).toHaveTextContent('No results');
    expect(screen.getByText('No results in this period')).toBeInTheDocument();
  });
});
