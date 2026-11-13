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
const credential = (over: object) => ({ id: 'c1', name: 'login', environmentId: 'e1', environmentName: 'Fixture', shared: false, enabled: true, usernameHint: 'f•••r',
  storedFields: ['username', 'password'], createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-02T00:00:00Z', usedBy: ['Fixture / Default'], ...over });

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
  const routes = { ...signedIn(), 'GET /projects/p1/targets': [], 'GET /projects/p1/environments': [], 'GET /projects/p1/login-profiles': [], 'POST /projects/p1/base-url-check': check };
  const next = () => userEvent.click(screen.getByRole('button', { name: 'Next' }));

  it('starts from Applications', async () => {
    server(routes);
    mount(<App />, '/applications');
    await userEvent.click(await screen.findByRole('link', { name: 'Set up application' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Set up application' })).toBeInTheDocument();
    expect(screen.getByText('Step 1 of 6')).toBeInTheDocument();
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
    expect(await screen.findByRole('heading', { level: 2, name: 'Sign-in' })).toBeInTheDocument();
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
    await screen.findByRole('heading', { level: 2, name: 'Sign-in' });
    await next();
    expect(await screen.findByText('No environment signs in, so there is no login flow to set.')).toBeInTheDocument();
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
    await screen.findByRole('heading', { level: 2, name: 'Connection test' });
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
    await screen.findByRole('heading', { level: 2, name: 'Sign-in' });
    await next();
    await screen.findByRole('heading', { level: 2, name: 'Login flow' });
    await next();
    await userEvent.click(await screen.findByRole('button', { name: 'Save application' }));
    expect(await screen.findByText(/Request conflicts with existing data/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Review and save' })).toBeInTheDocument();
  });
});

describe('application setup: sign-in', () => {
  const check = (c: Call) => ({ baseUrl: (c.body as { baseUrl: string }).baseUrl, origin: '', executable: false });
  const routes = { ...signedIn(), 'GET /projects/p1/targets': [], 'GET /projects/p1/environments': [], 'GET /projects/p1/login-profiles': [], 'POST /projects/p1/base-url-check': check };
  const next = () => userEvent.click(screen.getByRole('button', { name: 'Next' }));
  /** Fills the login flow step with its required choices, a success page and for Email OTP the code path, and moves to the review. */
  async function toReview() {
    await screen.findByRole('heading', { level: 2, name: 'Login flow' });
    await userEvent.type(screen.getByLabelText(/^Page only a signed-in user can open/), '/account');
    const codePath = screen.queryByLabelText(/^Code form submit path/);
    if (codePath) await userEvent.type(codePath, '/otp');
    await next();
    await screen.findByRole('heading', { level: 2, name: 'Review and save' });
  }
  async function toSignIn() {
    await userEvent.type(await screen.findByLabelText(/Application name/), 'Shop');
    await next();
    await userEvent.type(await screen.findByLabelText(/Environment name/), 'Staging');
    await userEvent.type(screen.getByLabelText(/Base URL/), 'https://staging.shop.example/');
    await next();
    await screen.findByRole('heading', { level: 2, name: 'Sign-in' });
  }

  it('needs a username and password, and a complete mailbox for email OTP', async () => {
    server(routes);
    mount(<App />, '/applications/new');
    await toSignIn();
    await userEvent.click(screen.getByLabelText('Sign-in for Staging'));
    await userEvent.click(await screen.findByRole('option', { name: 'Username, password and email OTP' }));
    expect(screen.getByLabelText(/^Password/)).toHaveAttribute('type', 'password');
    expect(screen.getByLabelText(/^Mailbox password/)).toHaveAttribute('type', 'password');
    await next();
    for (const text of ['Enter the username.', 'Enter the password.', 'Enter the mailbox address.', 'Enter the mail server.', 'Enter the mailbox password.', 'Confirm the mailbox is dedicated to testing.']) {
      expect(await screen.findByText(text)).toBeInTheDocument();
    }
    expect(screen.getByRole('heading', { level: 2, name: 'Sign-in' })).toBeInTheDocument();
  });

  it('reviews a mask, never the password, and sends the sign-in once with the save', async () => {
    const calls = server({ ...routes, 'POST /projects/p1/targets': { id: 't9', name: 'Shop', type: 'WEB', description: '', active: true } });
    mount(<App />, '/applications/new');
    await toSignIn();
    await userEvent.click(screen.getByLabelText('Sign-in for Staging'));
    await userEvent.click(await screen.findByRole('option', { name: 'Username and password' }));
    await userEvent.type(screen.getByRole('textbox', { name: /^Username/ }), 'qa-bot@shop.example');
    await userEvent.type(screen.getByLabelText(/^Password/), 'pw-canary-1');
    await next();
    await toReview();
    const row = (await screen.findAllByTestId('review-environment'))[0];
    expect(row).toHaveTextContent('Username and password, q•••e');
    expect(document.body.textContent).not.toContain('pw-canary-1');
    expect(document.body.textContent).not.toContain('qa-bot@shop.example');
    await userEvent.click(screen.getByRole('button', { name: 'Save application' }));
    await screen.findByRole('heading', { level: 2, name: 'Connection test' });
    const saves = calls.filter((c) => c.method === 'POST' && c.path === '/projects/p1/targets');
    expect(saves).toHaveLength(1);
    expect((saves[0].body as { environments: unknown[] }).environments[0]).toMatchObject({
      authentication: { name: 'Default', method: 'PASSWORD', username: 'qa-bot@shop.example', password: 'pw-canary-1', enabled: true, mailbox: null,
        loginPath: '/login', submitPath: '/login', successPath: '/account', timeoutSeconds: 15 },
    });
    expect(calls.filter((c) => JSON.stringify(c.body ?? '').includes('pw-canary-1'))).toHaveLength(1);
  });

  it('sends the mailbox settings for email OTP', async () => {
    const calls = server({ ...routes, 'POST /projects/p1/targets': { id: 't9', name: 'Shop', type: 'WEB', description: '', active: true } });
    mount(<App />, '/applications/new');
    await toSignIn();
    await userEvent.click(screen.getByLabelText('Sign-in for Staging'));
    await userEvent.click(await screen.findByRole('option', { name: 'Username, password and email OTP' }));
    await userEvent.type(screen.getByRole('textbox', { name: /^Username/ }), 'qa-bot');
    await userEvent.type(screen.getByLabelText(/^Password/), 'pw');
    await userEvent.type(screen.getByLabelText(/^Mailbox address/), 'otp@mail.example');
    await userEvent.type(screen.getByLabelText(/^IMAP server/), 'imap.mail.example');
    await userEvent.type(screen.getByLabelText(/^Mailbox password/), 'mb');
    await userEvent.click(screen.getByLabelText('This is a dedicated test mailbox I am authorized to use'));
    await next();
    await toReview();
    await userEvent.click(await screen.findByRole('button', { name: 'Save application' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.path === '/projects/p1/targets')).toBe(true));
    const body = calls.find((c) => c.method === 'POST' && c.path === '/projects/p1/targets')!.body as { environments: { authentication: { mailbox: object } }[] };
    expect(body.environments[0].authentication.mailbox).toEqual({ address: 'otp@mail.example', host: 'imap.mail.example', security: 'IMAPS', password: 'mb', folder: 'INBOX',
      senderFilter: '', subjectFilter: '', codePattern: '\\b(\\d{6})\\b', timeoutSeconds: 120, pollSeconds: 5, dedicatedMailboxConfirmed: true });
  });

  it('does not ask an API application for a password', async () => {
    server(routes);
    mount(<App />, '/applications/new');
    await userEvent.type(await screen.findByLabelText(/Application name/), 'Shop API');
    await userEvent.click(screen.getByLabelText('Application type'));
    await userEvent.click(await screen.findByRole('option', { name: 'API' }));
    await next();
    await userEvent.type(await screen.findByLabelText(/Environment name/), 'Staging');
    await userEvent.type(screen.getByLabelText(/Base URL/), 'https://api.shop.example/');
    await next();
    expect(await screen.findByText(/API applications sign in with a token/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Password/)).toBeNull();
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
  it('explains why nothing can run when every environment is a locked real target', async () => {
    server({ ...routes, 'GET /projects/p1/environments': [env({ id: 'e2', name: 'Real', executable: false })] });
    mount(<App />, '/execution');
    const alert = await screen.findByTestId('no-runnable-environment');
    expect(alert).toHaveTextContent('No environment in this project can be run.');
    expect(alert).toHaveTextContent('Real targets stay locked until the pre-pilot security gate passes');
    expect(alert).not.toHaveTextContent('Activate');
    expect(screen.getByRole('button', { name: 'Start fixture run' })).toBeDisabled();
    await userEvent.click(screen.getByLabelText('Environment'));
    expect(await screen.findByRole('option', { name: /Real.*locked/ })).toHaveAttribute('aria-disabled', 'true');
  });
  it('points to activation when the only fixture environment is inactive', async () => {
    server({ ...routes, 'GET /projects/p1/environments': [env({ active: false })] });
    mount(<App />, '/execution');
    const alert = await screen.findByTestId('no-runnable-environment');
    expect(alert).toHaveTextContent('Activate an inactive fixture environment');
    expect(alert).not.toHaveTextContent('pre-pilot');
  });
  it('says nothing extra when an environment can run', async () => {
    server(routes);
    mount(<App />, '/execution');
    await userEvent.click(await screen.findByLabelText('Environment'));
    await screen.findByRole('option', { name: /Fixture/ });
    expect(screen.queryByTestId('no-runnable-environment')).toBeNull();
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
    server({ ...signedIn(), 'GET /projects/p1/environments': [], 'GET /projects/p1/credentials': [credential({})] });
    mount(<App />, '/credentials');
    expect(await screen.findByText('login')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled();
  });
  it('shows masks, binding and status, never values', async () => {
    server({ ...signedIn(), 'GET /projects/p1/environments': [], 'GET /projects/p1/credentials': [
      credential({}), credential({ id: 'c2', name: 'legacy', environmentId: null, environmentName: null, shared: true, enabled: false, usedBy: [] }),
      credential({ id: 'c3', name: 'spare', environmentId: null, environmentName: null, usedBy: [], usernameHint: '', storedFields: [] }),
    ] });
    mount(<App />, '/credentials');
    const rows = await screen.findAllByTestId('credential-row');
    expect(rows[0]).toHaveTextContent('Fixture');
    expect(rows[0]).toHaveTextContent('f•••r');
    expect(rows[0]).toHaveTextContent('Username, Password');
    expect(rows[1]).toHaveTextContent('Shared, legacy');
    expect(rows[1]).toHaveTextContent('Disabled');
    expect(rows[2]).toHaveTextContent('Not bound yet');
  });
  it('edits by sending only what was typed, so blank fields keep their values', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/environments': [], 'GET /projects/p1/credentials': [credential({})], 'PUT /projects/p1/credentials/c1': credential({}) });
    mount(<App />, '/credentials');
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit login' });
    expect(within(dialog).getByLabelText('Password')).toHaveValue('');
    expect(within(dialog).getAllByText('Leave blank to keep the current value.').length).toBeGreaterThan(0);
    await userEvent.type(within(dialog).getByLabelText('Password'), 'new-secret');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ name: 'login', username: null, password: 'new-secret', totpSeed: null, cookieName: null, cookieValue: null, lowPrivilegeToken: null }));
  });
  it('disables a credential only after confirming', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/environments': [], 'GET /projects/p1/credentials': [credential({})], 'POST /projects/p1/credentials/c1/disable': credential({ enabled: false }) });
    mount(<App />, '/credentials');
    await userEvent.click(await screen.findByRole('button', { name: 'Disable' }));
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Disable credential' })).getByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(calls.some((c) => c.path === '/projects/p1/credentials/c1/disable')).toBe(true));
  });
  it('sends what was typed and uses password fields', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/environments': [env({})], 'GET /projects/p1/credentials': [], 'POST /projects/p1/credentials': credential({}) });
    mount(<App />, '/credentials');
    await userEvent.click(await screen.findByRole('button', { name: 'New credential' }));
    await userEvent.type(await screen.findByLabelText(/Credential name/), 'login');
    const secret = screen.getByLabelText('Password');
    expect(secret).toHaveAttribute('type', 'password');
    await userEvent.type(secret, 's3cret');
    await userEvent.click(screen.getByRole('button', { name: 'Save credential' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ name: 'login', password: 's3cret', environmentId: null }));
  });
  it('can bind a new credential to an environment', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/environments': [env({})], 'GET /projects/p1/credentials': [], 'POST /projects/p1/credentials': credential({}) });
    mount(<App />, '/credentials');
    await userEvent.click(await screen.findByRole('button', { name: 'New credential' }));
    await userEvent.type(await screen.findByLabelText(/Credential name/), 'login');
    await userEvent.click(screen.getByLabelText('Environment'));
    await userEvent.click(await screen.findByRole('option', { name: 'Shop / Fixture' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save credential' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ environmentId: 'e1' }));
  });
});

describe('login profiles', () => {
  const row = { environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', method: 'PASSWORD', credentialId: 'c1', credentialName: 'login', loginPath: '/login', submitPath: '/session', successPath: null, lastTest: null };
  const profile = { id: 'ap1', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', name: 'Default', method: 'PASSWORD', credentialId: 'c1', credentialName: 'login',
    credentialEnabled: true, credentialShared: false, usernameHint: 'f•••r', enabled: true, isDefault: true, loginPath: '/login', submitPath: '/session', successPath: null, loginFlowComplete: false, mailbox: null, updatedAt: '2026-11-01T00:00:00Z',
    locators: {}, timeoutSeconds: 15, lastTest: null };
  const routes = { ...signedIn(), 'GET /projects/p1/login-profiles': [row], 'GET /projects/p1/auth-profiles': [profile], 'GET /projects/p1/environments': [env({})],
    'GET /projects/p1/credentials': [credential({}), credential({ id: 'c9', name: 'elsewhere', environmentId: 'e9', environmentName: 'Other' })] };

  it('says when a profile cannot run yet, and offers only this environment\'s credentials', async () => {
    server(routes);
    mount(<App />, '/login-profiles');
    const r = await screen.findByTestId('profile-row');
    expect(r).toHaveTextContent('login (f•••r)');
    expect(r).toHaveTextContent('Not finished');
    expect(r).toHaveTextContent('Not tested');
    expect(within(r).getByRole('button', { name: 'Test sign-in' })).toBeDisabled();
    await userEvent.click(within(r).getByRole('button', { name: 'Configure' }));
    await userEvent.click(await screen.findByRole('combobox', { name: /^Credential/ }));
    expect(await screen.findByRole('option', { name: /login/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /elsewhere/ })).toBeNull();
  });

  it('switches to email OTP with a mailbox, checked before anything is sent', async () => {
    const calls = server({ ...routes, 'PUT /projects/p1/auth-profiles/ap1': profile });
    mount(<App />, '/login-profiles');
    await userEvent.click(within(await screen.findByTestId('profile-row')).getByRole('button', { name: 'Configure' }));
    await userEvent.click(await screen.findByLabelText('Method'));
    await userEvent.click(await screen.findByRole('option', { name: 'Password and email OTP' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(/^Mailbox address/), 'otp@mail.example');
    await userEvent.type(within(dialog).getByLabelText(/^IMAP server/), 'imap.mail.example');
    await userEvent.type(within(dialog).getByLabelText(/^Mailbox password/), 'mb');
    await userEvent.type(within(dialog).getByLabelText(/^Code form submit path/), '/otp');
    // Required fields are filled; the dedicated-mailbox confirmation is not, and nothing is sent without it.
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save profile' }));
    expect(await within(dialog).findByText('Check the highlighted mailbox fields.')).toBeInTheDocument();
    expect(within(dialog).getByText('Confirm the mailbox is dedicated to testing.')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'PUT')).toBe(false);
    await userEvent.click(within(dialog).getByLabelText('This is a dedicated test mailbox I am authorized to use'));
    await userEvent.type(within(dialog).getByLabelText(/^Page only a signed-in user can open/), '/account');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save profile' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({ name: 'Default', method: 'PASSWORD_EMAIL_OTP', credentialId: 'c1', isDefault: true,
      mailbox: { address: 'otp@mail.example', host: 'imap.mail.example', password: 'mb', dedicatedMailboxConfirmed: true } }));
  });

  it('keeps a saved mailbox password when the field is left blank', async () => {
    const mailbox = { address: 'otp@mail.example', host: 'imap.mail.example', security: 'IMAPS', port: 993, folder: 'INBOX', senderFilter: '', subjectFilter: '', codePattern: '\b(\d{6})\b',
      timeoutSeconds: 120, pollSeconds: 5, dedicatedConfirmedBy: 'admin@qa.local', dedicatedConfirmedAt: '2026-11-01T00:00:00Z' };
    const otp = { ...profile, method: 'PASSWORD_EMAIL_OTP', mailbox, successPath: '/account', loginFlowComplete: true,
      otp: { locators: {}, submitPath: '/otp', resendPath: null, maxResends: 0, typedByPerson: false } };
    const calls = server({ ...routes, 'GET /projects/p1/login-profiles': [{ ...row, method: 'PASSWORD_EMAIL_OTP', successPath: '/account' }], 'GET /projects/p1/auth-profiles': [otp], 'PUT /projects/p1/auth-profiles/ap1': otp });
    mount(<App />, '/login-profiles');
    await userEvent.click(within(await screen.findByTestId('profile-row')).getByRole('button', { name: 'Configure' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText(/^Mailbox password/)).toHaveValue('');
    expect(within(dialog).getByText('Leave blank to keep the current password.')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save profile' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({ mailbox: { password: null, dedicatedMailboxConfirmed: false } }));
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

describe('connection tests', () => {
  const row = (lastTest: object | null, over: object = {}) => ({ environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', method: 'PASSWORD', credentialId: 'c1',
    credentialName: 'login', loginPath: '/app/login', submitPath: '/app/session', successPath: '/app/home', lastTest, ...over });
  const profile = (lastTest: object | null) => ({ id: 'ap1', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', name: 'Default', method: 'PASSWORD',
    credentialId: 'c1', credentialName: 'login', credentialEnabled: true, credentialShared: false, usernameHint: 'f•••r', enabled: true, isDefault: true, loginPath: '/app/login',
    submitPath: '/app/session', successPath: '/app/home', loginFlowComplete: true, mailbox: null, updatedAt: '2026-11-01T00:00:00Z', locators: {}, timeoutSeconds: 15, lastTest });
  const test = (status: string | null, failure: string | null = null, extra: object = {}) => ({ runId: 'r9', runStatus: status ? 'SUCCEEDED' : 'RUNNING', status, failure, stage: 'DONE',
    detail: failure ? 'The target refused the credentials (HTTP 401)' : null, at: '2026-11-01T00:00:00Z', suggestions: {}, ...extra });
  const routes = (t: object | null) => ({ ...signedIn(), 'GET /projects/p1/login-profiles': [row(t)], 'GET /projects/p1/auth-profiles': [profile(t)],
    'GET /projects/p1/environments': [env({})], 'GET /projects/p1/credentials': [credential({})] });

  it('starts a connection test as a run of its own kind', async () => {
    const calls = server({ ...routes(null), 'POST /projects/p1/runs': { id: 'r9' } });
    mount(<App />, '/login-profiles');
    await userEvent.click(within(await screen.findByTestId('profile-row')).getByRole('button', { name: 'Test sign-in' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ environmentId: 'e1', kind: 'AUTH_TEST' }));
  });

  it.each([
    [null, null, 'Testing', 'running'],
    ['AUTHENTICATED', null, 'Authentication successful', 'ok'],
    ['CONNECTED', null, 'Connected', 'ok'],
    ['FAILED', 'INVALID_CREDENTIALS', 'Authentication failed', 'Invalid username or password'],
    ['FAILED', 'VERIFICATION_FAILED', 'Authentication failed', 'Sign-in could not be verified'],
    ['FAILED', 'POLICY_REFUSED', 'Access blocked', 'Refused by the target policy'],
    ['FAILED', 'NETWORK_UNREACHABLE', 'Connection failed', 'Network or DNS failure'],
    ['FAILED', 'TLS_ERROR', 'Connection failed', 'TLS or certificate error'],
    ['FAILED', 'LOGIN_PAGE_UNAVAILABLE', 'Connection failed', 'Login page unavailable'],
  ])('shows %s %s as "%s"', async (status, failure, label, detail) => {
    server(routes(test(status, failure)));
    mount(<App />, '/login-profiles');
    const r = await screen.findByTestId('profile-row');
    expect(within(r).getByTestId('test-outcome')).toHaveTextContent(label);
    if (failure) expect(r).toHaveTextContent(detail);
    expect(within(r).getByRole('button', { name: 'Test sign-in' })).toHaveProperty('disabled', status === null);
  });

  it('cannot test a locked real target', async () => {
    server({ ...routes(null), 'GET /projects/p1/environments': [env({ executable: false })] });
    mount(<App />, '/login-profiles');
    expect(within(await screen.findByTestId('profile-row')).getByRole('button', { name: 'Test sign-in' })).toBeDisabled();
  });

  it('fills the form fields from what the last test found, and saves them as locators', async () => {
    const suggestions = { username: { strategy: 'LABEL', value: 'Email' }, password: { strategy: 'LABEL', value: 'Password' }, submit: { strategy: 'TEST_ID', value: 'sign-in' } };
    const t = test('FAILED', 'INVALID_CREDENTIALS', { suggestions });
    const calls = server({ ...routes(t), 'PUT /projects/p1/auth-profiles/ap1': profile(t) });
    mount(<App />, '/login-profiles');
    await userEvent.click(within(await screen.findByTestId('profile-row')).getByRole('button', { name: 'Configure' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Use the fields found by the last test' }));
    expect(within(dialog).getByRole('textbox', { name: 'Username field: label' })).toHaveValue('Email');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save profile' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({
      submitPath: '/app/session', successPath: '/app/home',
      locators: { username: { strategy: 'LABEL', value: 'Email', name: null }, password: { strategy: 'LABEL', value: 'Password', name: null }, submit: { strategy: 'TEST_ID', value: 'sign-in', name: null }, success: null, failure: null },
    }));
  });

  it('checks the login flow before sending it', async () => {
    const calls = server(routes(null));
    mount(<App />, '/login-profiles');
    await userEvent.click(within(await screen.findByTestId('profile-row')).getByRole('button', { name: 'Configure' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.clear(within(dialog).getByLabelText(/^Page only a signed-in user can open/));
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Password field: label' }), 'Password');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save profile' }));
    expect(await within(dialog).findByText('Set a page only a signed-in user can open, a signed-in marker, or both.')).toBeInTheDocument();
    expect(within(dialog).getByText('Set both the password field and the sign-in button, or leave all three fields empty.')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'PUT' || c.method === 'POST')).toBe(false);
  });

  it('shows what a run\'s sign-in established on the run page', async () => {
    const run = { id: 'r9', kind: 'AUTH_TEST', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', suiteId: null, suiteName: null, status: 'FAILED',
      createdAt: '2026-11-01T00:00:00Z', finishedAt: '2026-11-01T00:00:10Z', stopReason: 'INVALID_CREDENTIALS', startedBy: 'admin@qa.local', passed: 0, failed: 1, inconclusive: 0, blocked: 0 };
    server({ ...signedIn(), 'GET /projects/p1/runs/r9/progress': { run, attempt: 1, jobState: 'FAILED', currentOperation: '', steps: 1, defects: 0, durationSeconds: 10 },
      'GET /projects/p1/runs/r9/steps': [], 'GET /projects/p1/runs/r9/findings': [],
      'GET /projects/p1/runs/r9/auth': [{ attempt: 1, authProfileId: 'ap1', status: 'FAILED', failure: 'INVALID_CREDENTIALS', stage: 'SUBMIT', detail: 'The page showed its sign-in error', durationMs: 2300, at: '2026-11-01T00:00:10Z' }] });
    mount(<App />, '/execution/r9');
    const card = await screen.findByTestId('run-auth');
    expect(card).toHaveTextContent('Connection test');
    expect(card).toHaveTextContent('Authentication failed');
    expect(card).toHaveTextContent('Invalid username or password');
    expect(card).toHaveTextContent('2.3 s');
  });
});

describe('application setup: connection test', () => {
  it('saves, then tests each environment it can, and says which it cannot', async () => {
    let tested = false;
    const fixture = env({ id: 'e5', targetId: 't9', name: 'Fixture', loginMethod: null });
    const locked = env({ id: 'e6', targetId: 't9', name: 'Acceptance', baseUrl: 'https://uat.shop.example/', executable: false });
    const calls = server({ ...signedIn(), 'GET /projects/p1/targets': [], 'POST /projects/p1/base-url-check': (c: Call) => ({ baseUrl: (c.body as { baseUrl: string }).baseUrl, origin: '', executable: true }),
      'POST /projects/p1/targets': { id: 't9', name: 'Shop', type: 'WEB', description: '', active: true },
      'GET /projects/p1/environments': [fixture, locked],
      'GET /projects/p1/login-profiles': () => [{ environmentId: 'e5', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', method: null, credentialId: null, credentialName: null,
        loginPath: null, submitPath: null, successPath: null, lastTest: tested ? { runId: 'r1', runStatus: 'SUCCEEDED', status: 'CONNECTED', failure: null, stage: 'DONE', detail: null, at: '2026-11-01T00:00:00Z', suggestions: {} } : null }],
      'POST /projects/p1/runs': () => { tested = true; return { id: 'r1' }; } });
    mount(<App />, '/applications/new');
    const next = () => userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await userEvent.type(await screen.findByLabelText(/Application name/), 'Shop');
    await next();
    await userEvent.type(await screen.findByLabelText(/Environment name/), 'Fixture');
    await userEvent.click(screen.getByRole('button', { name: 'Use local fixture' }));
    await next();
    await screen.findByRole('heading', { level: 2, name: 'Sign-in' });
    await next();
    await screen.findByRole('heading', { level: 2, name: 'Login flow' });
    await next();
    await userEvent.click(await screen.findByRole('button', { name: 'Save application' }));

    await screen.findByRole('heading', { level: 2, name: 'Connection test' });
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
    const rows = await screen.findAllByTestId('connection-test');
    expect(rows[1]).toHaveTextContent('Real target, locked');
    expect(within(rows[1]).queryByRole('button')).toBeNull();
    expect(rows[0]).toHaveTextContent('Not tested');
    await userEvent.click(within(rows[0]).getByRole('button', { name: 'Run test' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'POST' && c.path === '/projects/p1/runs')?.body).toEqual({ environmentId: 'e5', kind: 'AUTH_TEST' }));
    expect(await within((await screen.findAllByTestId('connection-test'))[0]).findByText('Connected')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Finish' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Applications & Environments' })).toBeInTheDocument();
  });
});

describe('one-time codes', () => {
  const run = (over: object = {}) => ({ id: 'r9', kind: 'AUTH_TEST', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', suiteId: null, suiteName: null,
    status: 'RUNNING', createdAt: '2026-11-01T00:00:00Z', finishedAt: null, stopReason: null, startedBy: 'admin@qa.local', passed: 0, failed: 0, inconclusive: 0, blocked: 0, ...over });
  const waiting = { attempt: 1, authProfileId: 'ap1', status: 'WAITING_FOR_OTP', failure: null, stage: 'OTP', detail: 'Waiting for someone to enter the code', durationMs: 0, at: '2026-11-01T00:00:00Z' };
  const routes = (r: object, attempts: object[]) => ({ ...signedIn(), 'GET /projects/p1/runs/r9/progress': { run: r, attempt: 1, jobState: 'RUNNING', currentOperation: '', steps: 1, defects: 0, durationSeconds: 10 },
    'GET /projects/p1/runs/r9/steps': [], 'GET /projects/p1/runs/r9/findings': [], 'GET /projects/p1/runs/r9/auth': attempts });

  it('asks for the code on the run page while the run waits, sends it once, and clears it', async () => {
    const calls = server({ ...routes(run(), [waiting]), 'POST /projects/p1/runs/r9/otp': () => new Response(null, { status: 202 }) });
    mount(<App />, '/execution/r9');
    const prompt = await screen.findByTestId('otp-prompt');
    const field = within(prompt).getByLabelText(/^One-time code/);
    expect(field).toHaveAttribute('type', 'password');
    expect(field).toHaveAttribute('autocomplete', 'one-time-code');
    const send = within(prompt).getByRole('button', { name: 'Continue sign-in' });
    expect(send).toBeDisabled();
    await userEvent.type(field, '482913');
    await userEvent.click(send);
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1));
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ code: '482913' });
    expect(await within(prompt).findByText('Code sent. The run continues in the same browser session.')).toBeInTheDocument();
    expect(field).toHaveValue('');
  });

  it('shows the server refusal and does not keep the code', async () => {
    server({ ...routes(run(), [waiting]), 'POST /projects/p1/runs/r9/otp': () => new Response(JSON.stringify({ detail: 'Only whoever started the run, or a project admin, can enter its code' }), { status: 403 }) });
    mount(<App />, '/execution/r9');
    const prompt = await screen.findByTestId('otp-prompt');
    await userEvent.type(within(prompt).getByLabelText(/^One-time code/), '482913');
    await userEvent.click(within(prompt).getByRole('button', { name: 'Continue sign-in' }));
    expect(await within(prompt).findByText(/Only whoever started the run/)).toBeInTheDocument();
    expect(within(prompt).getByLabelText(/^One-time code/)).toHaveValue('');
  });

  it('does not ask once the run has finished or stopped waiting', async () => {
    server(routes(run({ status: 'FAILED', finishedAt: '2026-11-01T00:00:10Z', stopReason: 'OTP_NOT_RECEIVED' }), [{ ...waiting, status: 'FAILED', failure: 'OTP_NOT_RECEIVED', detail: 'No code was entered within 5 minutes' }]));
    mount(<App />, '/execution/r9');
    const card = await screen.findByTestId('run-auth');
    expect(card).toHaveTextContent('OTP email not received');
    expect(screen.queryByTestId('otp-prompt')).toBeNull();
  });

  it('shows a waiting test in Login Profiles with a way to the prompt', async () => {
    const t = { runId: 'r9', runStatus: 'RUNNING', status: 'WAITING_FOR_OTP', failure: null, stage: 'OTP', detail: 'Waiting for someone to enter the code', at: '2026-11-01T00:00:00Z', suggestions: {} };
    server({ ...signedIn(), 'GET /projects/p1/login-profiles': [{ environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', method: 'PASSWORD_EMAIL_OTP',
      credentialId: 'c1', credentialName: 'login', loginPath: '/app/login-otp', submitPath: '/app/session-otp', successPath: '/app/home', lastTest: t }],
      'GET /projects/p1/auth-profiles': [], 'GET /projects/p1/environments': [env({})], 'GET /projects/p1/credentials': [] });
    mount(<App />, '/login-profiles');
    const r = await screen.findByTestId('profile-row');
    expect(within(r).getByTestId('test-outcome')).toHaveTextContent('Waiting for one-time code');
    expect(within(r).getByRole('link', { name: 'Enter the code' })).toHaveAttribute('href', '/execution/r9');
    expect(within(r).getByRole('button', { name: 'Test connection' })).toBeDisabled();
  });

  it('lets a person type the code instead of reading a mailbox, and removes the mailbox', async () => {
    const mailbox = { address: 'otp@mail.example', host: 'imap.mail.example', security: 'IMAPS', port: 993, folder: 'INBOX', senderFilter: '', subjectFilter: '', codePattern: '\\b(\\d{6})\\b',
      timeoutSeconds: 120, pollSeconds: 5, dedicatedConfirmedBy: 'admin@qa.local', dedicatedConfirmedAt: '2026-11-01T00:00:00Z' };
    const profile = { id: 'ap1', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', name: 'Default', method: 'PASSWORD_EMAIL_OTP', credentialId: 'c1',
      credentialName: 'login', credentialEnabled: true, credentialShared: false, usernameHint: 'f•••r', enabled: true, isDefault: true, loginPath: '/app/login-otp', submitPath: '/app/session-otp',
      successPath: '/app/home', loginFlowComplete: true, mailbox, updatedAt: '2026-11-01T00:00:00Z', locators: {}, timeoutSeconds: 15, lastTest: null,
      otp: { locators: { field: { strategy: 'LABEL', value: 'One-time code' } }, submitPath: '/app/otp-verify', resendPath: null, maxResends: 0, typedByPerson: false } };
    const calls = server({ ...signedIn(), 'GET /projects/p1/login-profiles': [{ environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', method: 'PASSWORD_EMAIL_OTP',
      credentialId: 'c1', credentialName: 'login', loginPath: '/app/login-otp', submitPath: '/app/session-otp', successPath: '/app/home', lastTest: null }],
      'GET /projects/p1/auth-profiles': [profile], 'GET /projects/p1/environments': [env({})], 'GET /projects/p1/credentials': [credential({})], 'PUT /projects/p1/auth-profiles/ap1': profile });
    mount(<App />, '/login-profiles');
    await userEvent.click(within(await screen.findByTestId('profile-row')).getByRole('button', { name: 'Configure' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText(/^Mailbox address/)).toHaveValue('otp@mail.example');
    expect(within(dialog).getByLabelText(/^Code form submit path/)).toHaveValue('/app/otp-verify');
    await userEvent.click(within(dialog).getByLabelText('Where the code comes from'));
    await userEvent.click(await screen.findByRole('option', { name: 'A person types it in while the run waits' }));
    expect(within(dialog).queryByLabelText(/^Mailbox address/)).toBeNull();

    await userEvent.clear(within(dialog).getByLabelText(/^Resends allowed/));
    await userEvent.type(within(dialog).getByLabelText(/^Resends allowed/), '2');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save profile' }));
    expect(await within(dialog).findByText('Set the resend button and its path to allow resends.')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'PUT')).toBe(false);

    await userEvent.clear(within(dialog).getByLabelText(/^Resends allowed/));
    await userEvent.type(within(dialog).getByLabelText(/^Resends allowed/), '0');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save profile' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({
      method: 'PASSWORD_EMAIL_OTP', mailbox: null,
      otp: { submitPath: '/app/otp-verify', resendPath: null, maxResends: 0, typedByPerson: true, locators: { field: { strategy: 'LABEL', value: 'One-time code', name: null } } },
    }));
  });

  it('sets up a code typed by a person in the wizard, with no mailbox to fill', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/targets': [], 'GET /projects/p1/environments': [], 'GET /projects/p1/login-profiles': [],
      'POST /projects/p1/base-url-check': (c: Call) => ({ baseUrl: (c.body as { baseUrl: string }).baseUrl, origin: '', executable: true }),
      'POST /projects/p1/targets': { id: 't9', name: 'Shop', type: 'WEB', description: '', active: true } });
    mount(<App />, '/applications/new');
    const next = () => userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await userEvent.type(await screen.findByLabelText(/Application name/), 'Shop');
    await next();
    await userEvent.type(await screen.findByLabelText(/Environment name/), 'Fixture');
    await userEvent.click(screen.getByRole('button', { name: 'Use local fixture' }));
    await next();
    await userEvent.click(await screen.findByLabelText('Sign-in for Fixture'));
    await userEvent.click(await screen.findByRole('option', { name: 'Username, password and email OTP' }));
    await userEvent.click(screen.getByLabelText('Where the code comes from for Fixture'));
    await userEvent.click(await screen.findByRole('option', { name: 'A person types it in while the run waits' }));
    expect(screen.queryByLabelText(/^Mailbox address/)).toBeNull();
    await userEvent.type(screen.getByRole('textbox', { name: /^Username/ }), 'qa-bot');
    await userEvent.type(screen.getByLabelText(/^Password/), 'pw');
    await next();
    await screen.findByRole('heading', { level: 2, name: 'Login flow' });
    await userEvent.type(screen.getByLabelText(/^Page only a signed-in user can open/), '/app/home');
    await next();
    expect(await screen.findByText('Enter the path the code form posts to. The code is only let through to it.')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/^Code form submit path/), '/app/otp-verify');
    await next();
    await userEvent.click(await screen.findByRole('button', { name: 'Save application' }));
    await screen.findByRole('heading', { level: 2, name: 'Connection test' });
    const body = calls.find((c) => c.method === 'POST' && c.path === '/projects/p1/targets')!.body as { environments: { authentication: object }[] };
    expect(body.environments[0].authentication).toMatchObject({ method: 'PASSWORD_EMAIL_OTP', mailbox: null, otp: { submitPath: '/app/otp-verify', typedByPerson: true, maxResends: 0 } });
  });
});

describe('session lifecycle', () => {
  it('shows that a run signed in again after its session ended, and why it stopped', async () => {
    const run = { id: 'r7', kind: 'EXPLORATION', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', suiteId: null, suiteName: null, status: 'SUCCEEDED',
      createdAt: '2026-11-01T00:00:00Z', finishedAt: '2026-11-01T00:05:00Z', stopReason: 'SESSION_EXPIRED', startedBy: 'admin@qa.local', passed: 9, failed: 0, inconclusive: 2, blocked: 1 };
    const attempt = (signIn: number) => ({ attempt: 1, signIn, authProfileId: 'ap1', status: 'AUTHENTICATED', failure: null, stage: 'DONE', detail: 'Signed in, and the success checks passed', durationMs: 1800, at: '2026-11-01T00:00:00Z' });
    server({ ...signedIn(), 'GET /projects/p1/runs/r7/progress': { run, attempt: 1, jobState: 'SUCCEEDED', currentOperation: '', steps: 12, defects: 0, durationSeconds: 300 },
      'GET /projects/p1/runs/r7/steps': [], 'GET /projects/p1/runs/r7/findings': [], 'GET /projects/p1/runs/r7/auth': [attempt(1), attempt(2)] });
    mount(<App />, '/execution/r7');
    const card = await screen.findByTestId('run-auth');
    const list = within(card).getByRole('list', { name: 'Sign-ins in this run' });
    const items = within(list).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Sign-in: Authentication successful');
    expect(items[1]).toHaveTextContent('Signed in again after the session ended: Authentication successful');
    expect(screen.getByText(/Stopped because: Session expired/)).toBeInTheDocument();
  });
});

describe('discovery', () => {
  const run = { id: 'r8', kind: 'DISCOVERY', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', suiteId: null, suiteName: null, status: 'SUCCEEDED',
    createdAt: '2026-11-01T00:00:00Z', finishedAt: '2026-11-01T00:02:00Z', stopReason: 'EXHAUSTED', startedBy: 'admin@qa.local', passed: 6, failed: 0, inconclusive: 0, blocked: 4 };
  const counts = (r: number, v: number, s: number, d: number) => ({ READ_ONLY: r, REVERSIBLE: v, SENSITIVE: s, DESTRUCTIVE: d });
  const summary = { attempt: 1, pages: 2, forms: 1, fields: 2, requiredFields: 1, unlabelledFields: 1, tables: 1, dialogs: 1, actions: counts(5, 0, 1, 2), links: 1, pageRows: [
    { id: 'p1', url: 'http://fixture.qa.internal:8090/app/console/users', title: 'Users', depth: 1, forms: 1, fields: 2, requiredFields: 1, unlabelledFields: 1, tables: 1, dialogs: 1, actions: counts(4, 0, 1, 2) },
    { id: 'p2', url: 'http://fixture.qa.internal:8090/app/console/billing', title: 'Billing', depth: 1, forms: 0, fields: 0, requiredFields: 0, unlabelledFields: 0, tables: 0, dialogs: 0, actions: counts(1, 0, 0, 0) }] };
  const users = { id: 'p1', url: 'http://fixture.qa.internal:8090/app/console/users', title: 'Users', depth: 1, headings: ['Users'], at: '2026-11-01T00:00:00Z', screenshot: 'ev1',
    structure: { navigation: [{ name: 'Console', links: ['Users', 'Billing'] }],
      forms: [{ name: 'Invite a teammate', method: 'post', action: '/app/console/invitations', submitLabel: 'Invite user', fields: [
        { label: 'Email', name: 'email', type: 'email', required: true, labelled: true }, { label: '', name: 'nickname', type: 'text', required: false, labelled: false }] }],
      tables: [{ caption: 'Team members', headers: ['Name', 'Email'], rows: 3 }], dialogs: [{ name: 'Remove user', open: false }] },
    actions: [
      { kind: 'LINK', role: 'link', name: 'Billing', href: 'http://fixture.qa.internal:8090/app/console/billing', locator: { strategy: 'ROLE', value: 'link', name: 'Billing' }, safety: 'READ_ONLY', reason: 'navigates to another page' },
      { kind: 'SUBMIT', role: 'button', name: 'Invite user', href: null, locator: { strategy: 'ROLE', value: 'button', name: 'Invite user' }, safety: 'SENSITIVE', reason: 'named "invite"' },
      { kind: 'BUTTON', role: 'button', name: 'Delete user', href: null, locator: { strategy: 'TEST_ID', value: 'delete-user' }, safety: 'DESTRUCTIVE', reason: 'named "delete"' }],
    linksTo: [{ pageId: 'p2', url: 'http://fixture.qa.internal:8090/app/console/billing', via: 'Billing' }] };
  const billing = { ...users, id: 'p2', url: 'http://fixture.qa.internal:8090/app/console/billing', title: 'Billing', screenshot: null, structure: { navigation: [], forms: [], tables: [], dialogs: [] }, actions: [], linksTo: [] };
  const routes = { ...signedIn(), 'GET /projects/p1/runs/r8/progress': { run, attempt: 1, jobState: 'SUCCEEDED', currentOperation: '', steps: 10, defects: 0, durationSeconds: 120 },
    'GET /projects/p1/runs/r8/steps': [], 'GET /projects/p1/runs/r8/findings': [], 'GET /projects/p1/runs/r8/auth': [], 'GET /projects/p1/runs/r8/discovery': summary,
    'GET /projects/p1/runs/r8/discovery/pages/p1': users, 'GET /projects/p1/runs/r8/discovery/pages/p2': billing };

  it('is started from the scope list, for web environments only', async () => {
    const calls = server({ ...signedIn(), 'GET /projects/p1/runs': empty, 'GET /projects/p1/suites': [], 'GET /projects/p1/environments': [env({}), env({ id: 'e2', name: 'API', channel: 'API' })],
      'POST /projects/p1/runs': { id: 'r8' }, 'GET /projects/p1/runs/r8/progress': () => new Response('{}', { status: 404 }) });
    mount(<App />, '/execution');
    await userEvent.click(await screen.findByLabelText('Environment'));
    await userEvent.click(await screen.findByRole('option', { name: /^Shop \/ API/ }));
    await userEvent.click(screen.getByLabelText('Scope'));
    expect(screen.queryByRole('option', { name: /Discovery/ })).toBeNull();
    await userEvent.keyboard('{Escape}');
    await userEvent.click(screen.getByLabelText('Environment'));
    await userEvent.click(await screen.findByRole('option', { name: /^Shop \/ Fixture/ }));
    await userEvent.click(screen.getByLabelText('Scope'));
    await userEvent.click(await screen.findByRole('option', { name: 'Discovery: record every page and action' }));
    await userEvent.click(screen.getByRole('button', { name: 'Start fixture run' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ environmentId: 'e1', suiteId: null, maxPages: null, kind: 'DISCOVERY' }));
  });

  it('opens on what it found, and shows each page with its forms, fields and what every action would do', async () => {
    server(routes);
    mount(<App />, '/execution/r8');
    const panel = await screen.findByTestId('discovery');
    expect(within(panel).getByText(/Discovery only reads/)).toBeInTheDocument();
    expect(within(panel).getByText('Fields with no name').parentElement).toHaveTextContent('1');
    const rows = within(panel).getAllByTestId('discovered-page');
    expect(rows[0]).toHaveTextContent('/app/console/users');
    expect(rows[0]).toHaveTextContent('4 read-only, 1 sensitive, 2 destructive');
    await userEvent.click(within(rows[0]).getByRole('button', { name: 'Details' }));

    const page = await screen.findByTestId('discovery-page');
    expect(within(page).getByText(/POST \/app\/console\/invitations, sent by "Invite user". Never submitted./)).toBeInTheDocument();
    const fields = within(page).getAllByTestId('discovered-field');
    expect(fields[0]).toHaveTextContent('Required');
    expect(fields[1]).toHaveTextContent('No accessible name');
    const actions = within(page).getAllByTestId('discovered-action');
    expect(actions[1]).toHaveTextContent('Sensitive');
    expect(actions[2]).toHaveTextContent('Destructive');
    expect(actions[2]).toHaveTextContent('test_id delete-user');
    expect(within(page).getByRole('link', { name: 'Open the masked screenshot' })).toHaveAttribute('href', '/api/v1/projects/p1/evidence/ev1/content');

    await userEvent.click(within(page).getByRole('button', { name: 'Billing' }));
    await waitFor(() => expect(screen.getByTestId('discovery-page')).not.toHaveTextContent('Invite a teammate'));
    expect(screen.getByTestId('discovery-page')).toHaveTextContent('/app/console/billing');
  });

  it('says so while nothing has been recorded yet', async () => {
    server({ ...routes, 'GET /projects/p1/runs/r8/discovery': { ...summary, attempt: 0, pages: 0, pageRows: [] } });
    mount(<App />, '/execution/r8');
    expect(await screen.findByText('No pages recorded yet')).toBeInTheDocument();
  });
});

describe('generated test cases', () => {
  const generated = { id: 'g1', title: 'Send Invite a teammate with valid values', description: 'Positive case. Sending this form was classified sensitive: run it only where sending is authorized.',
    suiteId: null, suiteName: null, source: 'GENERATED', priority: 'HIGH', status: 'PENDING_REVIEW', tags: ['generated', 'positive', 'needs-expected-result'], module: 'users',
    latestVersion: 1, lastVerdict: null, archivedAt: null };
  const steps = [
    { action: 'Sign in with the environment\'s testing account', expected: 'You are signed in', basis: 'OBSERVED' },
    { action: 'Go to /app/console/users', expected: 'The page "Users" opens', basis: 'OBSERVED' },
    { action: 'Press "Invite user"', expected: 'Not observed: discovery never sends forms. State what should happen, such as a message, the next page, or the saved record.', basis: 'UNKNOWN' },
  ];
  const review = { provenance: { generator: 'rules', rule: 'VALID_SUBMISSION', runId: 'r8', attempt: 1, observed: ['The form sends POST to /app/console/invitations'], inferred: [],
    unknown: ['What the application does after the form is sent'] }, generatedFromRun: 'r8', reviewedBy: null, reviewedAt: null, note: '' };
  const detail = (over: object = {}, stepList = steps) => ({ testCase: { ...generated, ...over }, steps: JSON.stringify(stepList), versions: [{ version: 1, steps: JSON.stringify(stepList), contentHash: 'a'.repeat(64) }],
    history: [], defects: [], review });
  const base = { ...signedIn(), 'GET /projects/p1/suites': [] };

  it('are suggested from a finished discovery run and lead to the review queue', async () => {
    const run = { id: 'r8', kind: 'DISCOVERY', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', suiteId: null, suiteName: null, status: 'SUCCEEDED',
      createdAt: '2026-11-01T00:00:00Z', finishedAt: '2026-11-01T00:02:00Z', stopReason: 'EXHAUSTED', startedBy: 'admin@qa.local', passed: 1, failed: 0, inconclusive: 0, blocked: 0 };
    const counts = { READ_ONLY: 1, REVERSIBLE: 0, SENSITIVE: 0, DESTRUCTIVE: 0 };
    const calls = server({ ...base, 'GET /projects/p1/runs/r8/progress': { run, attempt: 1, jobState: 'SUCCEEDED', currentOperation: '', steps: 1, defects: 0, durationSeconds: 5 },
      'GET /projects/p1/runs/r8/steps': [], 'GET /projects/p1/runs/r8/findings': [], 'GET /projects/p1/runs/r8/auth': [],
      'GET /projects/p1/runs/r8/discovery': { attempt: 1, pages: 1, forms: 0, fields: 0, requiredFields: 0, unlabelledFields: 0, tables: 0, dialogs: 0, actions: counts, links: 0,
        pageRows: [{ id: 'p1', url: 'http://fixture.qa.internal:8090/app/console', title: 'Console', depth: 0, forms: 0, fields: 0, requiredFields: 0, unlabelledFields: 0, tables: 0, dialogs: 0, actions: counts }] },
      'POST /projects/p1/runs/r8/discovery/test-cases': { generator: 'rules', suggested: 14, created: 12, alreadyKnown: 2 },
      'GET /projects/p1/test-cases': { items: [generated], total: 1 } });
    mount(<App />, '/execution/r8');
    await userEvent.click(await screen.findByRole('button', { name: 'Suggest test cases' }));
    const done = await screen.findByTestId('generated');
    expect(done).toHaveTextContent('12 new test cases waiting for review. 2 had been suggested before');
    await userEvent.click(within(done).getByRole('link', { name: 'Open the review queue' }));
    expect(await screen.findByRole('heading', { name: 'Test Cases' })).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.path.startsWith('/projects/p1/test-cases?') && c.path.includes('source=GENERATED') && c.path.includes('status=PENDING_REVIEW'))).toBe(true));
    expect(await screen.findByTestId('case-row')).toHaveTextContent('Generated');
    expect(screen.getByTestId('case-row')).toHaveTextContent('Pending review');
  });

  it('show what each step rests on, and cannot be approved while an expected result is unknown', async () => {
    const calls = server({ ...base, 'GET /projects/p1/test-cases/g1': detail(), 'PUT /projects/p1/test-cases/g1/steps': detail({ latestVersion: 2 }), 'POST /projects/p1/test-cases/g1/reject': generated });
    mount(<App />, '/cases/g1');
    const card = await screen.findByTestId('case-review');
    expect(within(card).getByText('Fixed rules (no AI)')).toBeInTheDocument();
    expect(within(card).getByText('What the application does after the form is sent')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Open the discovery run' })).toHaveAttribute('href', '/execution/r8');
    expect(within(card).getByRole('button', { name: 'Approve' })).toBeDisabled();
    expect(within(card).getByText(/1 step has no known expected result/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record result' })).toBeNull();
    expect(screen.getByRole('table', { name: 'Steps' })).toHaveTextContent('Unknown');

    await userEvent.click(screen.getByRole('button', { name: 'Edit steps' }));
    const expected = screen.getByLabelText('Step 3 expected result');
    await userEvent.clear(expected);
    await userEvent.type(expected, 'A confirmation says the invitation was sent');
    await userEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')?.body).toEqual([steps[0], steps[1],
      { action: 'Press "Invite user"', expected: 'A confirmation says the invitation was sent', basis: 'CONFIRMED' }]));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await userEvent.click(within(await screen.findByTestId('case-review')).getByRole('button', { name: 'Reject' }));
    await userEvent.type(screen.getByLabelText('Reason (optional)'), 'Covered elsewhere');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(calls.find((c) => c.path.endsWith('/reject'))?.body).toEqual({ note: 'Covered elsewhere' }));
  });

  it('are approved once every expected result is stated, then recorded by a person', async () => {
    const stated = steps.map((s) => (s.basis === 'UNKNOWN' ? { ...s, expected: 'A confirmation shows', basis: 'CONFIRMED' } : s));
    let approved = false;
    const calls = server({ ...base, 'GET /projects/p1/test-cases/g1': () => (approved ? { ...detail({ status: 'READY' }, stated), review: { ...review, reviewedBy: 'admin@qa.local', reviewedAt: '2026-11-02T00:00:00Z', note: 'Checked' } } : detail({}, stated)),
      'POST /projects/p1/test-cases/g1/approve': () => { approved = true; return { ...generated, status: 'READY' }; } });
    mount(<App />, '/cases/g1');
    const card = await screen.findByTestId('case-review');
    await userEvent.click(within(card).getByRole('button', { name: 'Approve' }));
    await userEvent.type(screen.getByLabelText('Note (optional)'), 'Checked');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(calls.find((c) => c.path.endsWith('/approve'))?.body).toEqual({ note: 'Checked' }));
    expect(await screen.findByRole('button', { name: 'Record result' })).toBeInTheDocument();
    expect(screen.getByTestId('case-review')).toHaveTextContent('admin@qa.local');
    expect(screen.getByTestId('case-review')).not.toHaveTextContent('not executed until');
  });

  it('show their executable steps, and say plainly that approving lets a suite run them', async () => {
    const script = [{ op: 'OPEN', path: '/clinic/patients' }, { op: 'ACTIVATE', target: { strategy: 'ROLE', value: 'link', name: 'Next page' } },
      { op: 'EXPECT_URL', value: '/clinic/patients?page=2' }, { op: 'FILL', target: { strategy: 'LABEL', value: 'Email' }, value: '' }];
    const observed = [{ action: 'Go to /clinic/patients', expected: 'The page "Patients" opens', basis: 'OBSERVED' }];
    const scripted = { ...detail({ executable: true }, observed), script: JSON.stringify(script), executionMode: 'VALIDATION_ONLY',
      review: { ...review, provenance: { ...review.provenance, generator: 'ai:anthropic/claude-opus-5-5', rule: 'AI_PAGINATION', risk: 'LOW', confidence: 'MEDIUM', executable: true } } };
    server({ ...base, 'GET /projects/p1/test-cases/g1': scripted });
    mount(<App />, '/cases/g1');
    const steps = await screen.findByTestId('case-script');
    expect(steps).toHaveTextContent('Validation only: also types into fields, never submits');
    expect(steps).toHaveTextContent('Open /clinic/patients');
    expect(steps).toHaveTextContent('Use link "Next page"');
    expect(steps).toHaveTextContent('Expect to be at /clinic/patients?page=2');
    expect(steps).toHaveTextContent('Type nothing (empty the field) into field "Email"');
    const card = screen.getByTestId('case-review');
    expect(card).toHaveTextContent('Suggested from discovery by a model (ai:anthropic/claude-opus-5-5). Check every expected result.');
    expect(card).toHaveTextContent('RiskLOW');
    expect(card).toHaveTextContent('ConfidenceMEDIUM');
    await userEvent.click(within(card).getByRole('button', { name: 'Approve' }));
    expect(within(screen.getByRole('dialog')).getByText(/Its executable steps then run in any suite it belongs to/)).toBeInTheDocument();
  });

  it('say how many suggestions can run and whether a model was used', async () => {
    const run = { id: 'r8', kind: 'DISCOVERY', environmentId: 'e1', environmentName: 'Fixture', applicationName: 'Shop', channel: 'WEB', suiteId: null, suiteName: null, status: 'SUCCEEDED',
      createdAt: '2026-11-01T00:00:00Z', finishedAt: '2026-11-01T00:02:00Z', stopReason: 'EXHAUSTED', startedBy: 'admin@qa.local', passed: 1, failed: 0, inconclusive: 0, blocked: 0 };
    const counts = { READ_ONLY: 1, REVERSIBLE: 0, SENSITIVE: 0, DESTRUCTIVE: 0 };
    server({ ...base, 'GET /projects/p1/runs/r8/progress': { run, attempt: 1, jobState: 'SUCCEEDED', currentOperation: '', steps: 1, defects: 0, durationSeconds: 5 },
      'GET /projects/p1/runs/r8/steps': [], 'GET /projects/p1/runs/r8/findings': [], 'GET /projects/p1/runs/r8/auth': [],
      'GET /projects/p1/runs/r8/discovery': { attempt: 1, pages: 1, forms: 0, fields: 0, requiredFields: 0, unlabelledFields: 0, tables: 0, dialogs: 0, actions: counts, links: 0,
        pageRows: [{ id: 'p1', url: 'http://fixture.qa.internal:8090/clinic/', title: 'Dashboard', depth: 0, forms: 0, fields: 0, requiredFields: 0, unlabelledFields: 0, tables: 0, dialogs: 0, actions: counts }] },
      'POST /projects/p1/runs/r8/discovery/test-cases': { generator: 'rules', suggested: 9, created: 9, alreadyKnown: 0, executable: 6, ai: 'DISABLED: No AI provider is configured.' } });
    mount(<App />, '/execution/r8');
    await userEvent.click(await screen.findByRole('button', { name: 'Suggest test cases' }));
    const done = await screen.findByTestId('generated');
    expect(done).toHaveTextContent('6 can run once approved.');
    expect(done).toHaveTextContent('Suggested by fixed rules; no AI is configured.');
  });

  it('keep their review status when edited: only approving or rejecting changes it', async () => {
    const calls = server({ ...base, 'GET /projects/p1/test-cases/g1': detail(), 'PATCH /projects/p1/test-cases/g1': generated });
    mount(<App />, '/cases/g1');
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    expect(screen.getByLabelText('Status')).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect((calls.find((c) => c.method === 'PATCH')?.body as { status: string }).status).toBe('PENDING_REVIEW'));
  });
});
