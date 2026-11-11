import { useState } from 'react';
import { Alert, Box, Button, FormControlLabel, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type AuthProfile, type Credential, type Environment, type LoginProfile, type User } from '../api';
import { Async, Card, Confirm, DataTable, dateTime, Empty, Failure, FormDialog, KeyValue, PageHeader, Tag, words } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { Choice, NeedsProject, useForm } from './shared';
import { blankMailbox, flowBody, flowDraft, flowProblems, LoginFlowFields, MailboxFields, mailboxBody, mailboxDraft, mailboxProblems, METHOD_LABELS, otpBody, TestResult, type FlowDraft, type MailboxDraft } from './signin';

/** Mirrors Access.Level and the checks in each controller. */
const MATRIX: [string, boolean, boolean, boolean, boolean][] = [
  ['View projects, runs, defects, evidence, reports', true, true, true, true],
  ['Write test cases, record results, report defects, start runs', false, true, true, true],
  ['Manage suites, triage defects', false, false, true, true],
  ['Manage applications, members, credentials, login profiles', false, false, false, true],
];

export function UsersPage() {
  const { me } = useWorkspace();
  const client = useQueryClient();
  const q = useQuery({ queryKey: ['users'], queryFn: () => api<User[]>('/users') });
  const [v, set] = useState({ email: '', password: '', organizationAdmin: false });
  const add = useForm<void>(() => api('/users', 'POST', v), [['users']]);
  const [resetting, setResetting] = useState<User | null>(null);
  const [password, setPassword] = useState('');
  const reset = useForm<void>(async () => { await api(`/users/${resetting!.id}/password`, 'POST', { password }); setResetting(null); setPassword(''); }, []);
  const refresh = () => client.invalidateQueries({ queryKey: ['users'] });
  const role = useMutation({ mutationFn: ([id, r]: [string, string]) => api(`/users/${id}/role`, 'PATCH', { role: r }), onSuccess: refresh });
  return (
    <>
      <PageHeader title="Users & Roles" description="Changes apply to open sessions at once. Project roles are set on each project's Members tab." actions={<Button variant="contained" onClick={add.show}>New user</Button>} />
      <Stack spacing={2}>
        {role.isError && <Failure error={role.error} />}
        <Card pad={false} title="Users">
          <Async q={q}>{(rows) => (
            <DataTable label="Users" testId="user-row" rows={rows} rowKey={(r) => r.id} columns={[
              { key: 'e', label: 'Email', render: (r) => r.email },
              { key: 'r', label: 'Organization role', render: (r) => (r.id === me.userId ? words(r.role) : <Choice label="Organization role" width={190} value={r.role} onChange={(x) => role.mutate([r.id, x])} options={[['MEMBER', 'Member'], ['ORGANIZATION_ADMIN', 'Organization admin']]} />) },
              { key: 's', label: 'Status', render: (r) => <Tag>{r.disabled ? 'Disabled' : 'Active'}</Tag> },
              { key: 'x', label: '', align: 'right', render: (r) => (r.id === me.userId ? null : (
                <Stack direction="row" justifyContent="flex-end">
                  <Button onClick={() => { reset.show(); setResetting(r); }}>Reset password</Button>
                  {r.disabled
                    ? <Confirm label="Enable" title="Enable user" body={`Let ${r.email} sign in again?`} onConfirm={async () => { await api(`/users/${r.id}/enable`, 'POST'); await refresh(); }} />
                    : <Confirm label="Disable" color="error" title="Disable user" body={`Sign ${r.email} out and block access?`} onConfirm={async () => { await api(`/users/${r.id}/disable`, 'POST'); await refresh(); }} />}
                </Stack>)) },
            ]} />)}</Async>
        </Card>
        <Card pad={false} title="Project role permissions">
          <Table size="small" aria-label="Project role permissions">
            <TableHead><TableRow><TableCell>Permission</TableCell>{['Viewer', 'Tester', 'QA Lead', 'Project Admin'].map((r) => <TableCell key={r} align="center">{r}</TableCell>)}</TableRow></TableHead>
            <TableBody>{MATRIX.map(([label, ...allowed]) => <TableRow key={label}><TableCell>{label}</TableCell>{allowed.map((a, i) => <TableCell key={i} align="center">{a ? 'Yes' : 'No'}</TableCell>)}</TableRow>)}</TableBody>
          </Table>
          <Typography variant="caption" component="div" sx={{ p: 1.5 }}>Organization admins have Project Admin rights on every project, and alone can create and archive projects or manage users.</Typography>
        </Card>
      </Stack>
      <FormDialog open={add.open} title="New user" submit="Create user" busy={add.busy} error={add.error} onClose={add.close} onSubmit={() => add.submit()}>
        <TextField label="Email" type="email" required value={v.email} onChange={(e) => set({ ...v, email: e.target.value })} />
        <TextField label="Initial password" type="password" required autoComplete="new-password" helperText="At least 12 characters." value={v.password} onChange={(e) => set({ ...v, password: e.target.value })} />
        <FormControlLabel control={<Switch checked={v.organizationAdmin} onChange={(e) => set({ ...v, organizationAdmin: e.target.checked })} />} label="Organization admin" />
      </FormDialog>
      <FormDialog open={!!resetting} title={`Reset password for ${resetting?.email}`} submit="Reset password" busy={reset.busy} error={reset.error} onClose={() => setResetting(null)} onSubmit={() => reset.submit()}>
        <TextField label="New password" type="password" required autoComplete="new-password" helperText="At least 12 characters. Open sessions for this user end." value={password} onChange={(e) => setPassword(e.target.value)} />
      </FormDialog>
    </>
  );
}

const blank = { name: '', environmentId: '', username: '', password: '', totpSeed: '', cookieName: '', cookieValue: '', lowPrivilegeToken: '' };
type Values = typeof blank;
const clean = (v: Values) => Object.fromEntries(Object.entries(v).map(([k, x]) => [k, x || null]));
const FIELDS: Record<string, string> = { username: 'Username', password: 'Password', totpSeed: 'TOTP seed', cookieName: 'Cookie name', cookieValue: 'Cookie value', lowPrivilegeToken: 'Low-privilege token' };

/** @param keeping true when editing: every field may stay blank, which keeps the sealed value */
function SecretFields({ v, set, kind, keeping }: { v: Values; set: (x: Values) => void; kind: string; keeping?: boolean }) {
  const keep = keeping ? 'Leave blank to keep the current value.' : undefined;
  return (
    <>
      {kind === 'PASSWORD' && <>
        <TextField label="Username" autoComplete="off" value={v.username} onChange={(e) => set({ ...v, username: e.target.value })} helperText={keep} />
        <TextField label="Password" type="password" autoComplete="new-password" value={v.password} onChange={(e) => set({ ...v, password: e.target.value })} helperText={keep} />
        <TextField label="TOTP seed (optional)" type="password" autoComplete="off" value={v.totpSeed} onChange={(e) => set({ ...v, totpSeed: e.target.value })} helperText={keep} />
      </>}
      {kind === 'API_TOKEN' && <>
        <TextField label="API token" type="password" autoComplete="off" value={v.password} onChange={(e) => set({ ...v, password: e.target.value })} helperText={keep} />
        <TextField label="Low-privilege token (optional)" type="password" autoComplete="off" value={v.lowPrivilegeToken} onChange={(e) => set({ ...v, lowPrivilegeToken: e.target.value })} helperText={keep ?? 'Lets a run check that lower roles are refused.'} />
      </>}
      {kind === 'SESSION_IMPORT' && <>
        <TextField label="Cookie name" autoComplete="off" value={v.cookieName} onChange={(e) => set({ ...v, cookieName: e.target.value })} helperText={keep} />
        <TextField label="Cookie value" type="password" autoComplete="off" value={v.cookieValue} onChange={(e) => set({ ...v, cookieValue: e.target.value })} helperText={keep} />
      </>}
      <Alert severity="info">{keeping ? 'Only the values you type are replaced. ' : ''}Values are sealed on save and cannot be read back, here or through the API.</Alert>
    </>
  );
}

const kinds: [string, string][] = [['PASSWORD', 'Username and password'], ['API_TOKEN', 'API token'], ['SESSION_IMPORT', 'Session cookie']];

function bindingOf(c: Credential) {
  if (c.shared) return <Tag title="Several environments used this credential before credentials were bound to one environment. It keeps working until it is migrated.">Shared, legacy</Tag>;
  return c.environmentName ?? 'Not bound yet';
}

function Credentials({ project }: { project: string }) {
  const client = useQueryClient();
  const q = useQuery({ queryKey: ['credentials', project], queryFn: () => api<Credential[]>(`/projects/${project}/credentials`) });
  const envs = useQuery({ queryKey: ['environments', project], queryFn: () => api<Environment[]>(`/projects/${project}/environments`) });
  const [v, set] = useState(blank);
  const [kind, setKind] = useState('PASSWORD');
  const add = useForm<void>(async () => { await api(`/projects/${project}/credentials`, 'POST', clean(v)); set(blank); }, [['credentials', project]]);
  const [editing, setEditing] = useState<Credential | null>(null);
  const edit = useForm<void>(async () => {
    const { environmentId: _bound, ...values } = clean(v);
    await api(`/projects/${project}/credentials/${editing!.id}`, 'PUT', values);
    set(blank);
    setEditing(null);
  }, [['credentials', project]]);
  const refresh = () => client.invalidateQueries({ queryKey: ['credentials', project] });
  return (
    <>
      <PageHeader title="Credentials" description="Secrets this project's runs can use. Each belongs to one environment. Only names and masks are ever shown." actions={<Button variant="contained" onClick={() => { set(blank); add.show(); }}>New credential</Button>} />
      <Card pad={false}>
        <Async q={q}>{(rows) => (rows.length === 0 ? <Empty title="No credentials">Add one to test a login or a secured API.</Empty> : (
          <DataTable label="Credentials" testId="credential-row" rows={rows} rowKey={(r) => r.id} columns={[
            { key: 'n', label: 'Name', render: (r) => r.name },
            { key: 'e', label: 'Environment', render: (r) => bindingOf(r) },
            { key: 'h', label: 'Username', render: (r) => r.usernameHint || 'None' },
            { key: 's', label: 'Stored values', render: (r) => (r.storedFields.length ? r.storedFields.map((f) => FIELDS[f] ?? f).join(', ') : 'Not recorded') },
            { key: 'u', label: 'Used by', render: (r) => r.usedBy.join(', ') || 'Not used' },
            { key: 'st', label: 'Status', render: (r) => (r.enabled ? 'Enabled' : <Tag title="Runs that would sign in with it are refused.">Disabled</Tag>) },
            { key: 'c', label: 'Updated', render: (r) => dateTime(r.updatedAt ?? r.createdAt) },
            { key: 'x', label: '', align: 'right', render: (r) => (
              <Stack direction="row" justifyContent="flex-end">
                <Button onClick={() => { set({ ...blank, name: r.name }); setKind(r.storedFields.includes('cookieValue') ? 'SESSION_IMPORT' : r.storedFields.includes('lowPrivilegeToken') || (r.storedFields.includes('password') && !r.storedFields.includes('username')) ? 'API_TOKEN' : 'PASSWORD'); edit.show(); setEditing(r); }}>Edit</Button>
                <Confirm label={r.enabled ? 'Disable' : 'Enable'} title={`${r.enabled ? 'Disable' : 'Enable'} credential`}
                  body={r.enabled ? `Disable ${r.name}? Runs that would sign in with it are refused until it is enabled again.` : `Enable ${r.name}? Runs can sign in with it again.`}
                  onConfirm={async () => { await api(`/projects/${project}/credentials/${r.id}/${r.enabled ? 'disable' : 'enable'}`, 'POST'); await refresh(); }} />
                <Confirm label="Delete" color="error" title="Delete credential" disabled={r.usedBy.length > 0} body={`Delete ${r.name}? This cannot be undone.`} onConfirm={async () => { await api(`/projects/${project}/credentials/${r.id}`, 'DELETE'); await refresh(); }} />
              </Stack>) },
          ]} />))}</Async>
      </Card>
      <FormDialog open={add.open} title="New credential" submit="Save credential" busy={add.busy} error={add.error} onClose={add.close} onSubmit={() => add.submit()}>
        <TextField label="Credential name" required autoComplete="off" value={v.name} onChange={(e) => set({ ...v, name: e.target.value })} />
        <Choice label="Environment" value={v.environmentId} onChange={(x) => set({ ...v, environmentId: x })}
          options={[['', 'The first environment that uses it'], ...(envs.data ?? []).map((e) => [e.id, `${e.targetName} / ${e.name}`] as [string, string])]} />
        <Choice label="Kind" value={kind} onChange={setKind} options={kinds} />
        <SecretFields v={v} set={set} kind={kind} />
      </FormDialog>
      <FormDialog open={!!editing} title={`Edit ${editing?.name}`} submit="Save changes" busy={edit.busy} error={edit.error} onClose={() => setEditing(null)} onSubmit={() => edit.submit()}>
        <TextField label="Credential name" required autoComplete="off" value={v.name} onChange={(e) => set({ ...v, name: e.target.value })} />
        <Choice label="Kind" value={kind} onChange={setKind} options={kinds} />
        <SecretFields v={v} set={set} kind={kind} keeping />
      </FormDialog>
    </>
  );
}

interface ProfileForm { method: string; credentialId: string }
/** Still in progress: queued, running, or holding its browser session while it waits for a one-time code. */
const testing = (r: LoginProfile) => !!r.lastTest && (r.lastTest.status === 'WAITING_FOR_OTP' || (!r.lastTest.status && ['QUEUED', 'RUNNING'].includes(r.lastTest.runStatus)));

function Profiles({ project }: { project: string }) {
  const client = useQueryClient();
  // Polls only while a connection test is queued or running, so a result shows up without a reload.
  const q = useQuery({ queryKey: ['login-profiles', project], queryFn: () => api<LoginProfile[]>(`/projects/${project}/login-profiles`),
    refetchInterval: (query) => (query.state.data?.some(testing) ? 2000 : false) });
  const details = useQuery({ queryKey: ['auth-profiles', project], queryFn: () => api<AuthProfile[]>(`/projects/${project}/auth-profiles`) });
  const envs = useQuery({ queryKey: ['environments', project], queryFn: () => api<Environment[]>(`/projects/${project}/environments`) });
  const [testError, setTestError] = useState<unknown>();
  const executable = (environmentId: string) => envs.data?.find((e) => e.id === environmentId)?.executable ?? false;
  const startTest = async (r: LoginProfile) => {
    setTestError(undefined);
    try {
      await api(`/projects/${project}/runs`, 'POST', { environmentId: r.environmentId, kind: 'AUTH_TEST' }, { 'Idempotency-Key': crypto.randomUUID() });
      await Promise.all([client.invalidateQueries({ queryKey: ['login-profiles', project] }), client.invalidateQueries({ queryKey: ['auth-profiles', project] })]);
    } catch (e) {
      setTestError(e);
    }
  };
  const credentials = useQuery({ queryKey: ['credentials', project], queryFn: () => api<Credential[]>(`/projects/${project}/credentials`) });
  const [editing, setEditing] = useState<LoginProfile | null>(null);
  const [v, set] = useState<ProfileForm>({ method: 'PASSWORD', credentialId: '' });
  const [flow, setFlow] = useState<FlowDraft>(flowDraft({ loginPath: null, submitPath: null, successPath: null }));
  const [mailbox, setMailbox] = useState<MailboxDraft>(blankMailbox());
  const [codeFrom, setCodeFrom] = useState<'MAILBOX' | 'PERSON'>('MAILBOX');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const profileOf = (environmentId: string) => details.data?.find((d) => d.environmentId === environmentId && d.isDefault);
  const current = editing ? profileOf(editing.environmentId) : undefined;
  const otp = v.method === 'PASSWORD_EMAIL_OTP';
  const save = useForm<void>(async () => {
    const web = editing?.channel !== 'API';
    const fromMailbox = otp && codeFrom === 'MAILBOX';
    const found = { ...(fromMailbox ? mailboxProblems(mailbox, current?.mailbox) : {}), ...(web ? Object.fromEntries(Object.entries(flowProblems(flow, v.method)).map(([k, x]) => [`flow.${k}`, x])) : {}) };
    setErrors(found);
    if (Object.keys(found).length) throw new Error(fromMailbox && Object.keys(found).some((k) => !k.startsWith('flow.')) ? 'Check the highlighted mailbox fields.' : 'Check the highlighted fields.');
    const body = { name: current?.name ?? 'Default', method: v.method, credentialId: v.credentialId || null, ...(web ? flowBody(flow) : {}),
      isDefault: true, mailbox: fromMailbox ? mailboxBody(mailbox) : null, otp: otp ? { ...otpBody(flow), typedByPerson: codeFrom === 'PERSON' } : null };
    if (current) await api(`/projects/${project}/auth-profiles/${current.id}`, 'PUT', body);
    else await api(`/projects/${project}/environments/${editing!.environmentId}/auth-profiles`, 'POST', body);
    setMailbox(blankMailbox());
    setEditing(null);
  }, [['login-profiles', project], ['auth-profiles', project], ['environments', project], ['credentials', project]]);
  const refresh = () => Promise.all([client.invalidateQueries({ queryKey: ['login-profiles', project] }), client.invalidateQueries({ queryKey: ['auth-profiles', project] })]);
  const open = (r: LoginProfile) => {
    const p = profileOf(r.environmentId);
    set({ method: r.method ?? (r.channel === 'API' ? 'API_TOKEN' : 'PASSWORD'), credentialId: r.credentialId ?? '' });
    setFlow(p ? flowDraft(p) : flowDraft({ loginPath: r.loginPath, submitPath: r.submitPath, successPath: r.successPath }));
    setMailbox(p?.mailbox ? mailboxDraft(p.mailbox) : blankMailbox());
    setCodeFrom(p && p.method === 'PASSWORD_EMAIL_OTP' && !p.mailbox ? 'PERSON' : 'MAILBOX');
    setErrors({});
    save.show();
    setEditing(r);
  };
  const api2 = editing?.channel === 'API';
  const usable = (credentials.data ?? []).filter((c) => !editing || c.shared || c.environmentId === null || c.environmentId === editing.environmentId);
  return (
    <>
      <PageHeader title="Login Profiles" description="How a run signs in to each environment. MFA is never bypassed: a profile supplies a credential, and the run signs in like a user." />
      {testError ? <Box sx={{ mb: 2 }}><Failure error={testError} /></Box> : null}
      <Card pad={false}>
        <Async q={q}>{(rows) => (rows.length === 0 ? <Empty title="No environments yet">Add an environment under Applications & Environments first.</Empty> : (
          <DataTable label="Login profiles" testId="profile-row" rows={rows} rowKey={(r) => r.environmentId} columns={[
            { key: 'e', label: 'Environment', render: (r) => `${r.applicationName} / ${r.environmentName}` }, { key: 't', label: 'Type', render: (r) => <Tag>{r.channel === 'WEB' ? 'Web' : 'API'}</Tag> },
            { key: 'm', label: 'Method', render: (r) => (r.method ? METHOD_LABELS[r.method] ?? words(r.method) : 'None') },
            { key: 'c', label: 'Credential', render: (r) => { const p = profileOf(r.environmentId); return r.credentialName ? `${r.credentialName}${p?.usernameHint ? ` (${p.usernameHint})` : ''}` : 'None'; } },
            { key: 'f', label: 'Login flow', render: (r) => { const p = profileOf(r.environmentId); if (!p) return ''; return p.loginFlowComplete ? 'Ready' : <Tag title="Runs are refused until it has a success check and a form submit path.">Not finished</Tag>; } },
            { key: 'ct', label: 'Connection test', render: (r) => <TestResult test={r.lastTest} /> },
            { key: 's', label: 'Status', render: (r) => { const p = profileOf(r.environmentId); if (!p) return ''; if (!p.enabled) return <Tag>Disabled</Tag>; return p.credentialEnabled ? 'Enabled' : <Tag title="Its credential is disabled, so runs are refused.">Credential disabled</Tag>; } },
            { key: 'x', label: '', align: 'right', render: (r) => { const p = profileOf(r.environmentId); return (
              <Stack direction="row" justifyContent="flex-end">
                <Button onClick={() => open(r)}>Configure</Button>
                <Button disabled={testing(r) || !executable(r.environmentId) || (!!p && !p.loginFlowComplete)} onClick={() => void startTest(r)}
                  title={!executable(r.environmentId) ? 'Real targets are locked until the pre-pilot security gate passes.' : p && !p.loginFlowComplete ? 'Finish the login flow first.' : undefined}>
                  {p ? 'Test sign-in' : 'Test connection'}
                </Button>
                {p && <Confirm label={p.enabled ? 'Disable' : 'Enable'} title={`${p.enabled ? 'Disable' : 'Enable'} login profile`}
                  body={p.enabled ? `Disable sign-in for ${r.environmentName}? Its runs are refused until it is enabled again.` : `Enable sign-in for ${r.environmentName}?`}
                  onConfirm={async () => { await api(`/projects/${project}/auth-profiles/${p.id}/${p.enabled ? 'disable' : 'enable'}`, 'POST'); await refresh(); }} />}
                {r.method && <Confirm label="Clear" color="error" title="Clear login profile" body="Runs of this environment will no longer sign in." onConfirm={async () => { await api(`/projects/${project}/environments/${r.environmentId}/login`, 'DELETE'); await refresh(); }} />}
              </Stack>); } },
          ]} />))}</Async>
      </Card>
      <FormDialog open={!!editing} title={`Login for ${editing?.environmentName}`} submit="Save profile" busy={save.busy} error={save.error} onClose={() => setEditing(null)} onSubmit={() => save.submit()} width={api2 ? 'sm' : 'md'}>
        <Choice label="Method" value={v.method} onChange={(x) => set({ ...v, method: x })} options={api2 ? [['API_TOKEN', 'API token']] : [['PASSWORD', 'Password'], ['PASSWORD_EMAIL_OTP', 'Password and email OTP'], ['TOTP', 'Password and TOTP'], ['SESSION_IMPORT', 'Session cookie']]} />
        <Choice label="Credential" required value={v.credentialId} onChange={(x) => set({ ...v, credentialId: x })}
          options={usable.map((c) => [c.id, `${c.name}${c.usernameHint ? ` (${c.usernameHint})` : ''}${c.enabled ? '' : ', disabled'}${c.shared ? ', shared' : ''}`] as [string, string])} />
        <Typography variant="caption" color="text.secondary">Only credentials for this environment, or not yet bound to one, are offered. Choosing an unbound one binds it here.</Typography>
        {!api2 && <LoginFlowFields value={flow} set={setFlow} errors={errors} prefix="flow." suggestions={editing?.lastTest?.suggestions ?? current?.lastTest?.suggestions} otp={otp} />}
        {otp && <>
          <Choice label="Where the code comes from" value={codeFrom} onChange={(x) => setCodeFrom(x as 'MAILBOX' | 'PERSON')}
            options={[['MAILBOX', 'A dedicated test mailbox'], ['PERSON', 'A person types it in while the run waits']]} />
          {codeFrom === 'MAILBOX'
            ? <MailboxFields value={mailbox} set={setMailbox} errors={errors} prefix="" existing={current?.mailbox} />
            : <Alert severity="info">The run keeps its browser session open and asks for the code on its page, for up to 5 minutes. Only whoever started the run, or a project admin, can enter it.</Alert>}
        </>}
      </FormDialog>
    </>
  );
}

export const CredentialsPage = () => <NeedsProject>{(p) => <Credentials project={p} />}</NeedsProject>;
export const LoginProfilesPage = () => <NeedsProject>{(p) => <Profiles project={p} />}</NeedsProject>;

export function SettingsPage() {
  const { me } = useWorkspace();
  const client = useQueryClient();
  const org = useQuery({ queryKey: ['organization'], queryFn: () => api<{ name: string }>('/organization') });
  const [name, setName] = useState<string | null>(null);
  const rename = useMutation({ mutationFn: () => api('/organization', 'PATCH', { name }), onSuccess: () => client.invalidateQueries({ queryKey: ['organization'] }) });
  const [p, setP] = useState({ current: '', password: '' });
  const change = useMutation({ mutationFn: () => api('/auth/password', 'POST', p), onSuccess: () => { window.location.reload(); } });
  const [seed, setSeed] = useState('');
  const totp = useMutation({ mutationFn: () => api<{ seedBase64: string }>('/auth/totp', 'POST'), onSuccess: (r) => setSeed(r.seedBase64) });
  return (
    <>
      <PageHeader title="Settings" />
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' }, alignItems: 'start' }}>
        <Card title="Your account">
          <KeyValue items={[['Email', me.email], ['Role', me.organizationAdmin ? 'Organization admin' : 'Member']]} />
          <Stack component="form" spacing={1.5} sx={{ mt: 2 }} onSubmit={(e) => { e.preventDefault(); change.mutate(); }}>
            <Typography variant="h3">Change password</Typography>
            {change.isError && <Failure error={change.error} />}
            <TextField label="Current password" type="password" required autoComplete="current-password" value={p.current} onChange={(e) => setP({ ...p, current: e.target.value })} />
            <TextField label="New password" type="password" required autoComplete="new-password" helperText="At least 12 characters. You will sign in again." value={p.password} onChange={(e) => setP({ ...p, password: e.target.value })} />
            <Box><Button type="submit" variant="outlined">Change password</Button></Box>
          </Stack>
        </Card>
        <Stack spacing={2}>
          <Card title="Organization">
            <Async q={org}>{(o) => (me.organizationAdmin ? (
              <Stack component="form" spacing={1.5} onSubmit={(e) => { e.preventDefault(); rename.mutate(); }}>
                {rename.isError && <Failure error={rename.error} />}
                <TextField label="Organization name" required value={name ?? o.name} onChange={(e) => setName(e.target.value)} />
                <Box><Button type="submit" variant="outlined" disabled={name === null}>Save</Button></Box>
              </Stack>) : <KeyValue items={[['Name', o.name]]} />)}</Async>
          </Card>
          <Card title="Two-factor sign-in">
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Add an authenticator app. Enrolling again replaces the current seed.</Typography>
            {totp.isError && <Failure error={totp.error} />}
            {seed ? <Alert severity="warning">Add this seed to your app now. It is not shown again: <Box component="code" sx={{ overflowWrap: 'anywhere' }}>{seed}</Box></Alert> : <Button variant="outlined" onClick={() => totp.mutate()}>Set up authenticator</Button>}
          </Card>
          <Card title="Execution safety">
            <KeyValue items={[['Targets', 'Local fixture only'], ['Real targets', 'Locked until the pre-pilot security gate passes'], ['Run mode', 'Read-only. Mutation is disabled'], ['Theme', 'Light']]} />
          </Card>
        </Stack>
      </Box>
    </>
  );
}
