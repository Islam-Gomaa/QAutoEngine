import { Checkbox, FormControlLabel, Stack, TextField, Typography } from '@mui/material';
import type { Mailbox } from '../api';
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
