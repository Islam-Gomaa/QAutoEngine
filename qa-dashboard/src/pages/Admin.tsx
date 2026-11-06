import { useState } from 'react';
import { Alert, Box, Button, FormControlLabel, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Credential, type LoginProfile, type User } from '../api';
import { Async, Card, Confirm, DataTable, dateTime, Empty, Failure, FormDialog, KeyValue, PageHeader, Tag, words } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { Choice, NeedsProject, useForm } from './shared';

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

const blank = { name: '', username: '', password: '', totpSeed: '', cookieName: '', cookieValue: '', lowPrivilegeToken: '' };
const clean = (v: typeof blank) => Object.fromEntries(Object.entries(v).map(([k, x]) => [k, x || null]));

function SecretFields({ v, set, kind }: { v: typeof blank; set: (x: typeof blank) => void; kind: string }) {
  return (
    <>
      {kind === 'PASSWORD' && <>
        <TextField label="Username" autoComplete="off" value={v.username} onChange={(e) => set({ ...v, username: e.target.value })} />
        <TextField label="Password" type="password" autoComplete="new-password" value={v.password} onChange={(e) => set({ ...v, password: e.target.value })} />
        <TextField label="TOTP seed (optional)" type="password" autoComplete="off" value={v.totpSeed} onChange={(e) => set({ ...v, totpSeed: e.target.value })} />
      </>}
      {kind === 'API_TOKEN' && <>
        <TextField label="API token" type="password" autoComplete="off" value={v.password} onChange={(e) => set({ ...v, password: e.target.value })} />
        <TextField label="Low-privilege token (optional)" type="password" autoComplete="off" value={v.lowPrivilegeToken} onChange={(e) => set({ ...v, lowPrivilegeToken: e.target.value })} helperText="Lets a run check that lower roles are refused." />
      </>}
      {kind === 'SESSION_IMPORT' && <>
        <TextField label="Cookie name" autoComplete="off" value={v.cookieName} onChange={(e) => set({ ...v, cookieName: e.target.value })} />
        <TextField label="Cookie value" type="password" autoComplete="off" value={v.cookieValue} onChange={(e) => set({ ...v, cookieValue: e.target.value })} />
      </>}
      <Alert severity="info">Values are sealed on save and cannot be read back, here or through the API.</Alert>
    </>
  );
}

const kinds: [string, string][] = [['PASSWORD', 'Username and password'], ['API_TOKEN', 'API token'], ['SESSION_IMPORT', 'Session cookie']];

function Credentials({ project }: { project: string }) {
  const client = useQueryClient();
  const q = useQuery({ queryKey: ['credentials', project], queryFn: () => api<Credential[]>(`/projects/${project}/credentials`) });
  const [v, set] = useState(blank);
  const [kind, setKind] = useState('PASSWORD');
  const add = useForm<void>(async () => { await api(`/projects/${project}/credentials`, 'POST', clean(v)); set(blank); }, [['credentials', project]]);
  const [rotating, setRotating] = useState<Credential | null>(null);
  const rotate = useForm<void>(async () => { await api(`/projects/${project}/credentials/${rotating!.id}`, 'PUT', clean(v)); set(blank); setRotating(null); }, [['credentials', project]]);
  return (
    <>
      <PageHeader title="Credentials" description="Secrets this project's runs can use. Only names are ever shown." actions={<Button variant="contained" onClick={() => { set(blank); add.show(); }}>New credential</Button>} />
      <Card pad={false}>
        <Async q={q}>{(rows) => (rows.length === 0 ? <Empty title="No credentials">Add one to test a login or a secured API.</Empty> : (
          <DataTable label="Credentials" testId="credential-row" rows={rows} rowKey={(r) => r.id} columns={[
            { key: 'n', label: 'Name', render: (r) => r.name }, { key: 'u', label: 'Used by', render: (r) => r.usedBy.join(', ') || 'Not used' }, { key: 'c', label: 'Created', render: (r) => dateTime(r.createdAt) },
            { key: 'x', label: '', align: 'right', render: (r) => (
              <Stack direction="row" justifyContent="flex-end">
                <Button onClick={() => { set(blank); rotate.show(); setRotating(r); }}>Rotate</Button>
                <Confirm label="Delete" color="error" title="Delete credential" disabled={r.usedBy.length > 0} body={`Delete ${r.name}? This cannot be undone.`} onConfirm={async () => { await api(`/projects/${project}/credentials/${r.id}`, 'DELETE'); await client.invalidateQueries({ queryKey: ['credentials', project] }); }} />
              </Stack>) },
          ]} />))}</Async>
      </Card>
      <FormDialog open={add.open} title="New credential" submit="Save credential" busy={add.busy} error={add.error} onClose={add.close} onSubmit={() => add.submit()}>
        <TextField label="Credential name" required autoComplete="off" value={v.name} onChange={(e) => set({ ...v, name: e.target.value })} />
        <Choice label="Kind" value={kind} onChange={setKind} options={kinds} />
        <SecretFields v={v} set={set} kind={kind} />
      </FormDialog>
      <FormDialog open={!!rotating} title={`Rotate ${rotating?.name}`} submit="Replace values" busy={rotate.busy} error={rotate.error} onClose={() => setRotating(null)} onSubmit={() => rotate.submit()}>
        <Choice label="Kind" value={kind} onChange={setKind} options={kinds} />
        <SecretFields v={v} set={set} kind={kind} />
      </FormDialog>
    </>
  );
}

function Profiles({ project }: { project: string }) {
  const client = useQueryClient();
  const q = useQuery({ queryKey: ['login-profiles', project], queryFn: () => api<LoginProfile[]>(`/projects/${project}/login-profiles`) });
  const credentials = useQuery({ queryKey: ['credentials', project], queryFn: () => api<Credential[]>(`/projects/${project}/credentials`) });
  const [editing, setEditing] = useState<LoginProfile | null>(null);
  const [v, set] = useState({ method: 'PASSWORD', credentialId: '', loginPath: '/login', submitPath: '/login', successPath: '/' });
  const save = useForm<void>(async () => {
    await api(`/projects/${project}/environments/${editing!.environmentId}/login`, 'PUT', v);
    setEditing(null);
  }, [['login-profiles', project], ['environments', project], ['credentials', project]]);
  const api2 = editing?.channel === 'API';
  return (
    <>
      <PageHeader title="Login Profiles" description="How a run signs in to each environment. MFA is never bypassed: a profile supplies a credential, and the run signs in like a user." />
      <Card pad={false}>
        <Async q={q}>{(rows) => (rows.length === 0 ? <Empty title="No environments yet">Add an environment under Applications & Environments first.</Empty> : (
          <DataTable label="Login profiles" testId="profile-row" rows={rows} rowKey={(r) => r.environmentId} columns={[
            { key: 'e', label: 'Environment', render: (r) => `${r.applicationName} / ${r.environmentName}` }, { key: 't', label: 'Type', render: (r) => <Tag>{r.channel === 'WEB' ? 'Web' : 'API'}</Tag> },
            { key: 'm', label: 'Method', render: (r) => (r.method ? words(r.method) : 'None') }, { key: 'c', label: 'Credential', render: (r) => r.credentialName ?? 'None' }, { key: 'p', label: 'Login path', render: (r) => r.loginPath ?? '' },
            { key: 'x', label: '', align: 'right', render: (r) => (
              <Stack direction="row" justifyContent="flex-end">
                <Button onClick={() => { set({ method: r.method ?? (r.channel === 'API' ? 'API_TOKEN' : 'PASSWORD'), credentialId: r.credentialId ?? '', loginPath: r.loginPath ?? '/login', submitPath: r.submitPath ?? '/login', successPath: r.successPath ?? '/' }); save.show(); setEditing(r); }}>Configure</Button>
                {r.method && <Confirm label="Clear" color="error" title="Clear login profile" body="Runs of this environment will no longer sign in." onConfirm={async () => { await api(`/projects/${project}/environments/${r.environmentId}/login`, 'DELETE'); await client.invalidateQueries({ queryKey: ['login-profiles', project] }); }} />}
              </Stack>) },
          ]} />))}</Async>
      </Card>
      <FormDialog open={!!editing} title={`Login for ${editing?.environmentName}`} submit="Save profile" busy={save.busy} error={save.error} onClose={() => setEditing(null)} onSubmit={() => save.submit()}>
        <Choice label="Method" value={v.method} onChange={(x) => set({ ...v, method: x })} options={api2 ? [['API_TOKEN', 'API token']] : [['PASSWORD', 'Password'], ['TOTP', 'Password and TOTP'], ['SESSION_IMPORT', 'Session cookie']]} />
        <Choice label="Credential" required value={v.credentialId} onChange={(x) => set({ ...v, credentialId: x })} options={(credentials.data ?? []).map((c) => [c.id, c.name] as [string, string])} />
        {!api2 && <>
          <TextField label="Login page path" value={v.loginPath} onChange={(e) => set({ ...v, loginPath: e.target.value })} />
          <TextField label="Form submit path" value={v.submitPath} onChange={(e) => set({ ...v, submitPath: e.target.value })} />
          <TextField label="Path after signing in" required value={v.successPath} onChange={(e) => set({ ...v, successPath: e.target.value })} />
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
