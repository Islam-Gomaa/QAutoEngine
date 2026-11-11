import { Box, Button, Checkbox, FormControlLabel, Stack, TextField, Typography } from '@mui/material';
import type { Locators, LocatorView, Mailbox } from '../api';
import { Choice } from './shared';

/**
 * Pieces shared by every form that sets up how a run signs in. Secret values typed here live only in
 * the form's own state, are sent once, and are never shown again: an edit leaves them blank to keep them.
 */

export const METHOD_LABELS: Record<string, string> = {
  PASSWORD: 'Username and password', PASSWORD_EMAIL_OTP: 'Username, password and email OTP', TOTP: 'Password and TOTP', SESSION_IMPORT: 'Session cookie', API_TOKEN: 'API token',
};

export interface MailboxDraft {
  address: string; host: string; security: 'IMAPS' | 'STARTTLS'; password: string; folder: string; senderFilter: string; subjectFilter: string;
  codePattern: string; timeoutSeconds: string; pollSeconds: string; confirmed: boolean;
}

export const blankMailbox = (): MailboxDraft => ({
  address: '', host: '', security: 'IMAPS', password: '', folder: 'INBOX', senderFilter: '', subjectFilter: '', codePattern: '\\b(\\d{6})\\b',
  timeoutSeconds: '120', pollSeconds: '5', confirmed: false,
});

/** An existing mailbox, ready to edit. The password stays blank, which keeps the sealed one. */
export const mailboxDraft = (m: Mailbox): MailboxDraft => ({
  address: m.address, host: m.host, security: m.security, password: '', folder: m.folder, senderFilter: m.senderFilter, subjectFilter: m.subjectFilter,
  codePattern: m.codePattern, timeoutSeconds: String(m.timeoutSeconds), pollSeconds: String(m.pollSeconds), confirmed: false,
});

/**
 * Problems the form can see before asking the server, keyed by field. The server checks everything again.
 * @param existing the mailbox as saved, when editing one: its password may stay blank, and confirmation is only needed if the address or server changes
 */
export function mailboxProblems(m: MailboxDraft, existing?: Mailbox | null): Record<string, string> {
  const found: Record<string, string> = {};
  if (!/^[^@\s]+@[^@\s]+$/.test(m.address.trim())) found.address = 'Enter the mailbox address.';
  if (!m.host.trim()) found.host = 'Enter the mail server.';
  if (!existing && !m.password) found.password = 'Enter the mailbox password.';
  const timeout = Number(m.timeoutSeconds), poll = Number(m.pollSeconds);
  if (!Number.isInteger(timeout) || timeout < 10 || timeout > 600) found.timeoutSeconds = 'Between 10 and 600 seconds.';
  if (!Number.isInteger(poll) || poll < 2 || poll > 60 || poll >= timeout) found.pollSeconds = 'Between 2 and 60 seconds, and shorter than the timeout.';
  const moved = !existing || existing.address.toLowerCase() !== m.address.trim().toLowerCase() || existing.host !== m.host.trim().toLowerCase();
  if (moved && !m.confirmed) found.confirmed = 'Confirm the mailbox is dedicated to testing.';
  return found;
}

export const mailboxBody = (m: MailboxDraft) => ({
  address: m.address.trim(), host: m.host.trim(), security: m.security, password: m.password || null, folder: m.folder.trim(), senderFilter: m.senderFilter.trim(),
  subjectFilter: m.subjectFilter.trim(), codePattern: m.codePattern.trim(), timeoutSeconds: Number(m.timeoutSeconds), pollSeconds: Number(m.pollSeconds), dedicatedMailboxConfirmed: m.confirmed,
});

/** @param prefix keeps field ids and error keys apart when several mailboxes are on one page */
export function MailboxFields({ value, set, errors, prefix, existing }: { value: MailboxDraft; set: (m: MailboxDraft) => void; errors: Record<string, string>; prefix: string; existing?: Mailbox | null }) {
  const e = (k: string) => errors[`${prefix}${k}`];
  return (
    <Stack spacing={2} component="fieldset" sx={{ border: 1, borderColor: 'divider', borderRadius: 1.5, p: 2, m: 0 }}>
      <Typography component="legend" variant="body2" sx={{ fontWeight: 600, px: 0.5 }}>OTP mailbox</Typography>
      <Typography variant="caption" color="text.secondary">
        Use a mailbox that exists only for testing. Codes are read from new messages that match the filters; nothing connects to the mailbox until email OTP sign-in is available.
      </Typography>
      <TextField label="Mailbox address" required autoComplete="off" value={value.address} onChange={(x) => set({ ...value, address: x.target.value })} error={!!e('address')} helperText={e('address')} />
      <Stack direction="row" spacing={1}>
        <TextField label="IMAP server" required fullWidth autoComplete="off" value={value.host} onChange={(x) => set({ ...value, host: x.target.value })}
          error={!!e('host')} helperText={e('host') ?? 'A host name, such as imap.example.com.'} />
        <Choice label="Security" value={value.security} onChange={(x) => set({ ...value, security: x as MailboxDraft['security'] })} options={[['IMAPS', 'IMAPS (port 993)'], ['STARTTLS', 'STARTTLS (port 143)']]} width={200} />
      </Stack>
      <TextField label="Mailbox password" type="password" required={!existing} autoComplete="new-password" value={value.password} onChange={(x) => set({ ...value, password: x.target.value })}
        error={!!e('password')} helperText={e('password') ?? (existing ? 'Leave blank to keep the current password.' : 'An app password if the provider offers one.')} />
      <TextField label="Folder" value={value.folder} onChange={(x) => set({ ...value, folder: x.target.value })} />
      <Stack direction="row" spacing={1}>
        <TextField label="Sender filter (optional)" fullWidth value={value.senderFilter} onChange={(x) => set({ ...value, senderFilter: x.target.value })} />
        <TextField label="Subject filter (optional)" fullWidth value={value.subjectFilter} onChange={(x) => set({ ...value, subjectFilter: x.target.value })} />
      </Stack>
      <TextField label="Code pattern" value={value.codePattern} onChange={(x) => set({ ...value, codePattern: x.target.value })} helperText="A regular expression. Its one group, or the whole match, is the code." />
      <Stack direction="row" spacing={1}>
        <TextField label="Timeout in seconds" inputMode="numeric" value={value.timeoutSeconds} onChange={(x) => set({ ...value, timeoutSeconds: x.target.value })} error={!!e('timeoutSeconds')} helperText={e('timeoutSeconds')} />
        <TextField label="Check every (seconds)" inputMode="numeric" value={value.pollSeconds} onChange={(x) => set({ ...value, pollSeconds: x.target.value })} error={!!e('pollSeconds')} helperText={e('pollSeconds')} />
      </Stack>
      <FormControlLabel control={<Checkbox checked={value.confirmed} onChange={(x) => set({ ...value, confirmed: x.target.checked })} />}
        label={existing ? 'This is still a dedicated test mailbox I am authorized to use' : 'This is a dedicated test mailbox I am authorized to use'} />
      {e('confirmed') && <Typography variant="caption" color="error" role="alert">{e('confirmed')}</Typography>}
    </Stack>
  );
}

// ---- login flow

export type Strategy = LocatorView['strategy'];
export interface LocatorDraft { strategy: Strategy; value: string; name: string }
export interface FlowDraft {
  loginPath: string; submitPath: string; successPath: string; timeoutSeconds: string;
  username: LocatorDraft; password: LocatorDraft; submit: LocatorDraft; success: LocatorDraft; failure: LocatorDraft;
}

const STRATEGIES: [Strategy, string][] = [['LABEL', 'Label'], ['ROLE', 'Role and name'], ['TEST_ID', 'Test id'], ['PLACEHOLDER', 'Placeholder'], ['CSS', 'CSS selector']];
const blankLocator = (strategy: Strategy = 'LABEL'): LocatorDraft => ({ strategy, value: '', name: '' });
const fromView = (v: LocatorView | null | undefined, fallback: Strategy): LocatorDraft => (v ? { strategy: v.strategy, value: v.value, name: v.name ?? '' } : blankLocator(fallback));

export const blankFlow = (): FlowDraft => ({
  loginPath: '/login', submitPath: '/login', successPath: '', timeoutSeconds: '15',
  username: blankLocator(), password: blankLocator(), submit: blankLocator('ROLE'), success: blankLocator('TEST_ID'), failure: blankLocator('ROLE'),
});

export const flowDraft = (p: { loginPath: string | null; submitPath: string | null; successPath: string | null; locators?: Locators; timeoutSeconds?: number }): FlowDraft => ({
  loginPath: p.loginPath ?? '/login', submitPath: p.submitPath ?? '/login', successPath: p.successPath ?? '', timeoutSeconds: String(p.timeoutSeconds ?? 15),
  username: fromView(p.locators?.username, 'LABEL'), password: fromView(p.locators?.password, 'LABEL'), submit: fromView(p.locators?.submit, 'ROLE'),
  success: fromView(p.locators?.success, 'TEST_ID'), failure: fromView(p.locators?.failure, 'ROLE'),
});

const toView = (l: LocatorDraft): LocatorView | null => (l.value.trim() ? { strategy: l.strategy, value: l.value.trim(), name: l.strategy === 'ROLE' && l.name.trim() ? l.name.trim() : null } : null);

export const flowBody = (f: FlowDraft) => ({
  loginPath: f.loginPath.trim() || null, submitPath: f.submitPath.trim() || null, successPath: f.successPath.trim() || null, timeoutSeconds: Number(f.timeoutSeconds),
  locators: { username: toView(f.username), password: toView(f.password), submit: toView(f.submit), success: toView(f.success), failure: toView(f.failure) },
});

/** The checks the server makes too, shown before asking it. A session cookie submits no form, so it needs no submit path. */
export function flowProblems(f: FlowDraft, method: string): Record<string, string> {
  const found: Record<string, string> = {};
  for (const k of ['loginPath', 'submitPath', 'successPath'] as const) {
    const v = f[k].trim();
    if (v && !/^\/[A-Za-z0-9._~/-]*$/.test(v)) found[k] = 'A path on the site, starting with /.';
  }
  if (method !== 'SESSION_IMPORT' && !f.submitPath.trim()) found.submitPath = found.submitPath ?? 'Enter the path the form posts to. Sign-in is only let through to it.';
  if (!f.successPath.trim() && !f.success.value.trim()) found.successPath = 'Set a page only a signed-in user can open, a signed-in marker, or both.';
  const fields = [f.username, f.password, f.submit].filter((l) => l.value.trim()).length;
  if (fields > 0 && (!f.password.value.trim() || !f.submit.value.trim())) found.password = 'Set both the password field and the sign-in button, or leave all three fields empty.';
  for (const k of ['username', 'password', 'submit', 'success', 'failure'] as const) {
    if (f[k].strategy === 'ROLE' && f[k].value.trim() && !f[k].name.trim() && k !== 'failure') found[`${k}.name`] = 'Give the accessible name too.';
  }
  const t = Number(f.timeoutSeconds);
  if (!Number.isInteger(t) || t < 5 || t > 60) found.timeoutSeconds = 'Between 5 and 60 seconds.';
  return found;
}

function LocatorField({ label, value, set, error, nameError, help }: { label: string; value: LocatorDraft; set: (l: LocatorDraft) => void; error?: string; nameError?: string; help?: string }) {
  return (
    <Stack direction="row" spacing={1} alignItems="flex-start">
      <Choice label={`${label}: find by`} value={value.strategy} onChange={(x) => set({ ...value, strategy: x as Strategy })} options={STRATEGIES} width={170} />
      <TextField label={value.strategy === 'ROLE' ? `${label}: role` : `${label}: ${STRATEGIES.find(([s]) => s === value.strategy)?.[1].toLowerCase()}`} fullWidth value={value.value}
        onChange={(x) => set({ ...value, value: x.target.value })} error={!!error} helperText={error ?? help} placeholder={value.strategy === 'ROLE' ? 'button' : undefined} />
      {value.strategy === 'ROLE' && <TextField label={`${label}: name`} fullWidth value={value.name} onChange={(x) => set({ ...value, name: x.target.value })} error={!!nameError} helperText={nameError} />}
    </Stack>
  );
}

/**
 * Where to sign in and how to tell it worked. Field locators are optional: without them the first login form
 * on the page is used. Success is never assumed: a success page, a signed-in marker, or both, is required.
 * @param suggestions locators the last connection test read from the login page, offered with one click
 */
export function LoginFlowFields({ value, set, errors, prefix, suggestions }: {
  value: FlowDraft; set: (f: FlowDraft) => void; errors: Record<string, string>; prefix: string; suggestions?: Record<string, LocatorView> | null;
}) {
  const e = (k: string) => errors[`${prefix}${k}`];
  const offered = suggestions && Object.keys(suggestions).length > 0 ? suggestions : null;
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1}>
        <TextField label="Login page path" fullWidth value={value.loginPath} onChange={(x) => set({ ...value, loginPath: x.target.value })} error={!!e('loginPath')} helperText={e('loginPath')} />
        <TextField label="Form submit path" required fullWidth value={value.submitPath} onChange={(x) => set({ ...value, submitPath: x.target.value })} error={!!e('submitPath')}
          helperText={e('submitPath') ?? 'Where the form posts. Only this is let through while signing in.'} />
      </Stack>
      <Typography component="h4" variant="body2" sx={{ fontWeight: 600 }}>How to tell sign-in worked</Typography>
      <TextField label="Page only a signed-in user can open" value={value.successPath} onChange={(x) => set({ ...value, successPath: x.target.value })} error={!!e('successPath')}
        helperText={e('successPath') ?? 'For example /account. Leave empty if you set a signed-in marker instead.'} />
      <LocatorField label="Signed-in marker" value={value.success} set={(l) => set({ ...value, success: l })} nameError={e('success.name')} help="Something only a signed-in page shows, such as an account menu." />
      <LocatorField label="Sign-in error (optional)" value={value.failure} set={(l) => set({ ...value, failure: l })} help="The page's own error for a wrong password, so a refusal is certain rather than inferred." />
      <Typography component="h4" variant="body2" sx={{ fontWeight: 600 }}>Form fields (optional)</Typography>
      <Typography variant="caption" color="text.secondary">Leave these empty to use the first login form on the page. A connection test reads the page and can fill them for you.</Typography>
      {offered && (
        <Box>
          <Button variant="outlined" onClick={() => set({ ...value, username: fromView(offered.username, 'LABEL'), password: fromView(offered.password, 'LABEL'), submit: fromView(offered.submit, 'ROLE') })}>
            Use the fields found by the last test
          </Button>
        </Box>
      )}
      <LocatorField label="Username field" value={value.username} set={(l) => set({ ...value, username: l })} nameError={e('username.name')} />
      <LocatorField label="Password field" value={value.password} set={(l) => set({ ...value, password: l })} error={e('password')} nameError={e('password.name')} />
      <LocatorField label="Sign-in button" value={value.submit} set={(l) => set({ ...value, submit: l })} nameError={e('submit.name')} />
      <TextField label="Wait up to (seconds)" inputMode="numeric" value={value.timeoutSeconds} onChange={(x) => set({ ...value, timeoutSeconds: x.target.value })}
        error={!!e('timeoutSeconds')} helperText={e('timeoutSeconds')} sx={{ maxWidth: 220 }} />
    </Stack>
  );
}

// ---- connection test results

export const FAILURE_LABELS: Record<string, string> = {
  NETWORK_UNREACHABLE: 'Network or DNS failure', TLS_ERROR: 'TLS or certificate error', POLICY_REFUSED: 'Refused by the target policy', LOGIN_PAGE_UNAVAILABLE: 'Login page unavailable',
  INVALID_CREDENTIALS: 'Invalid username or password', VERIFICATION_FAILED: 'Sign-in could not be verified', SESSION_REJECTED: 'Session not accepted',
  ATTEMPT_LIMIT: 'Sign-in attempts used up', OTP_NOT_RECEIVED: 'OTP email not received', OTP_EXTRACTION_FAILED: 'OTP could not be read', OTP_INVALID: 'Invalid or expired OTP',
};

const CONNECTION = ['NETWORK_UNREACHABLE', 'TLS_ERROR', 'LOGIN_PAGE_UNAVAILABLE'];

/** What a connection test established, in the words of the brief. A run that ended without a report says so rather than guessing. */
export function testOutcome(t: { runStatus?: string; status: string | null; failure: string | null } | null | undefined): { label: string; tone: 'ok' | 'bad' | 'neutral' } {
  if (!t) return { label: 'Not tested', tone: 'neutral' };
  if (!t.status) return ['QUEUED', 'RUNNING'].includes(t.runStatus ?? '') ? { label: 'Testing', tone: 'neutral' } : { label: 'Test did not complete', tone: 'bad' };
  if (t.status === 'AUTHENTICATED') return { label: 'Authentication successful', tone: 'ok' };
  if (t.status === 'CONNECTED') return { label: 'Connected', tone: 'ok' };
  if (t.failure === 'POLICY_REFUSED' || t.failure === 'ATTEMPT_LIMIT') return { label: 'Access blocked', tone: 'bad' };
  if (t.failure && CONNECTION.includes(t.failure)) return { label: 'Connection failed', tone: 'bad' };
  return { label: 'Authentication failed', tone: 'bad' };
}

export function TestResult({ test }: { test: { runStatus?: string; status: string | null; failure: string | null; detail: string | null } | null | undefined }) {
  const o = testOutcome(test);
  return (
    <Stack spacing={0.25}>
      <Typography variant="body2" sx={{ fontWeight: 560, color: o.tone === 'ok' ? 'success.main' : o.tone === 'bad' ? 'error.main' : 'text.secondary' }} data-testid="test-outcome">{o.label}</Typography>
      {test?.failure && <Typography variant="caption">{FAILURE_LABELS[test.failure] ?? test.failure}</Typography>}
      {test?.detail && test.status !== 'AUTHENTICATED' && test.status !== 'CONNECTED' && <Typography variant="caption" color="text.secondary">{test.detail}</Typography>}
    </Stack>
  );
}
