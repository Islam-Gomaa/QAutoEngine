import { useState } from 'react';
import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Environment, type Target } from '../api';
import { ago, Async, Card, Confirm, DataTable, Empty, FormDialog, Mono, PageHeader, StatusChip, Tag } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { Link as RouterLink } from 'react-router-dom';
import { Choice, ENVIRONMENT_CLASSES, FIXTURE_URL, LOCK_REASONS, NeedsProject, useForm } from './shared';
const lines = (s: string) => s.split('\n').map((x) => x.trim()).filter(Boolean);

/** What the backend decided about running this environment, and why, in its own terms. */
function ExecutionMode({ e }: { e: Environment }) {
  if (e.executable) return e.lockReason === null && !e.baseUrl.startsWith(FIXTURE_URL.replace(/\/$/, '')) && e.authorized
    ? <Tag title="Approved host, authorization in date.">Authorized</Tag>
    : <Tag title="The local test fixture. Runs are allowed.">Local fixture</Tag>;
  const reason = e.lockReason ? LOCK_REASONS[e.lockReason] : undefined;
  return <Tag title={reason ? `${reason.detail} ${reason.fix}` : 'Execution stays locked until the pre-pilot security gate passes.'}>{reason ? `Locked: ${reason.label.toLowerCase()}` : 'Real target, locked'}</Tag>;
}

interface Authorization { authorized: boolean; expired: boolean; reference: string | null; authorizedBy: string | null; authorizedAt: string | null; expiresAt: string | null }

/** Records, renews or revokes the written authorization to test a real environment. Organization Admin only; the server enforces it. */
function AuthorizationDialog({ project, e, onClose }: { project: string; e: Environment; onClose: () => void }) {
  const client = useQueryClient();
  const path = `/projects/${project}/environments/${e.id}/authorization`;
  const q = useQuery({ queryKey: ['authorization', e.id], queryFn: () => api<Authorization>(path) });
  const [reference, setReference] = useState('');
  const [until, setUntil] = useState('');
  const done = async () => { await client.invalidateQueries({ queryKey: ['authorization', e.id] }); await client.invalidateQueries({ queryKey: ['environments', project] }); };
  const record = useForm<void>(async () => { await api(path, 'PUT', { reference, expiresAt: until ? new Date(`${until}T23:59:59Z`).toISOString() : null }); await done(); onClose(); }, [['environments', project]]);
  const a = q.data;
  return (
    <FormDialog open title={`Authorization for ${e.name}`} submit="Record authorization" busy={record.busy} error={record.error} onClose={onClose} onSubmit={() => record.submit()}>
      <Typography variant="body2">The reference of the written authorization to test {e.baseUrl}, such as a document number, and the date it ends. Recording it does not approve the host: an operator does that in the deployment.</Typography>
      {a && (a.reference
        ? <Alert severity={a.authorized ? 'success' : 'warning'}>{a.authorized ? 'In date' : 'Expired'}: {a.reference}, recorded by {a.authorizedBy}, ends {a.expiresAt?.slice(0, 10)}.</Alert>
        : <Alert severity="info">No authorization is recorded.</Alert>)}
      <TextField label="Authorization reference" required value={reference} onChange={(x) => setReference(x.target.value)} helperText="3 to 120 letters, digits and . _ : / # ( ) -" />
      <TextField label="Valid until" type="date" required value={until} onChange={(x) => setUntil(x.target.value)} slotProps={{ inputLabel: { shrink: true } }} helperText="At most a year from today." />
      {a?.reference && <Box><Confirm label="Revoke authorization" color="error" title="Revoke authorization" body={`Revoke the authorization for ${e.name}? Runs on it are refused, and its mode returns to read-only.`}
        onConfirm={async () => { await api(path, 'DELETE'); await done(); onClose(); }} /></Box>}
    </FormDialog>
  );
}

/** What runs may do here. Organization Admin only; production stays read-only and a real target needs an authorization in date. */
function ModeDialog({ project, e, onClose }: { project: string; e: Environment; onClose: () => void }) {
  const [mode, setMode] = useState(e.mode);
  const f = useForm<void>(async () => { await api(`/projects/${project}/environments/${e.id}/execution-mode`, 'PUT', { mode }); onClose(); }, [['environments', project]]);
  return (
    <FormDialog open title={`Execution mode for ${e.name}`} submit="Save mode" busy={f.busy} error={f.error} onClose={onClose} onSubmit={() => f.submit()}>
      <Choice label="Mode" value={mode} onChange={setMode} options={[['READ_ONLY', 'Read-only: open pages and read them'], ['VALIDATION_ONLY', 'Validation only: also type into fields, never submit']]} />
      <Typography variant="caption">The gate in the worker enforces the mode on every action. It never changes where a run may go.</Typography>
    </FormDialog>
  );
}

function EnvironmentForm({ project, target, onClose }: { project: string; target: Target; onClose: () => void }) {
  const [v, set] = useState({ name: '', classification: 'LOCAL', baseUrl: '', description: '', apiSpecPath: target.type === 'API' ? '/openapi.json' : '' });
  const f = useForm<void>(async () => {
    await api(`/projects/${project}/targets/${target.id}/environments`, 'POST', { name: v.name, classification: v.classification, baseUrl: v.baseUrl, description: v.description, channel: target.type, apiSpecPath: v.apiSpecPath || null });
    onClose();
  }, [['environments', project]]);
  return (
    <FormDialog open title={`Add environment to ${target.name}`} submit="Add environment" busy={f.busy} error={f.error} onClose={onClose} onSubmit={() => f.submit()}>
      <TextField label="Environment name" required value={v.name} onChange={(e) => set({ ...v, name: e.target.value })} />
      <Choice label="Environment type" value={v.classification} onChange={(x) => set({ ...v, classification: x })} options={ENVIRONMENT_CLASSES} />
      <Stack direction="row" spacing={1} alignItems="flex-start">
        <TextField label="Base URL" required value={v.baseUrl} onChange={(e) => set({ ...v, baseUrl: e.target.value })} helperText="Cannot be changed later." />
        <Button sx={{ flexShrink: 0, mt: 0.25 }} variant="outlined" onClick={() => set({ ...v, baseUrl: FIXTURE_URL })}>Use local fixture</Button>
      </Stack>
      {target.type === 'API' && <TextField label="OpenAPI path" value={v.apiSpecPath} onChange={(e) => set({ ...v, apiSpecPath: e.target.value })} />}
      <TextField label="Description" value={v.description} onChange={(e) => set({ ...v, description: e.target.value })} />
      <Alert severity="info">Only the local fixture can be executed. Any other URL can be saved, and stays locked until the pre-pilot security gate passes.</Alert>
    </FormDialog>
  );
}

function EditEnvironment({ project, e, onClose }: { project: string; e: Environment; onClose: () => void }) {
  const [name, setName] = useState(e.name);
  const [description, setDescription] = useState(e.description);
  const [include, setInclude] = useState(e.includePaths.join('\n'));
  const [exclude, setExclude] = useState(e.excludePaths.join('\n'));
  const f = useForm<void>(async () => {
    await api(`/projects/${project}/environments/${e.id}`, 'PATCH', { name, description, includePaths: lines(include), excludePaths: lines(exclude), apiSpecPath: e.apiSpecPath, apiSlowMillis: e.apiSlowMillis });
    onClose();
  }, [['environments', project]]);
  return (
    <FormDialog open title={`Edit ${e.name}`} submit="Save changes" busy={f.busy} error={f.error} onClose={onClose} onSubmit={() => f.submit()}>
      <TextField label="Environment name" required value={name} onChange={(x) => setName(x.target.value)} />
      <TextField label="Description" value={description} onChange={(x) => setDescription(x.target.value)} />
      <TextField label="Included paths, one per line" multiline minRows={2} value={include} onChange={(x) => setInclude(x.target.value)} helperText="Leave empty to include the whole site." />
      <TextField label="Excluded paths, one per line" multiline minRows={2} value={exclude} onChange={(x) => setExclude(x.target.value)} />
    </FormDialog>
  );
}

/** Inactive stops new runs and can be undone; archive is permanent. Both directions confirm, so neither happens by a stray click. */
function ActiveSwitch({ kind, name, active, path, onDone }: { kind: 'application' | 'environment'; name: string; active: boolean; path: string; onDone: () => Promise<unknown> }) {
  const label = active ? 'Deactivate' : 'Activate';
  const body = active
    ? `Deactivate ${name}? New runs are refused until it is activated again. History is kept${kind === 'application' ? ', and its environments keep their own settings' : ''}.`
    : `Activate ${name}? It can be run again, within the same safety limits.`;
  return <Confirm label={label} title={`${label} ${kind}`} body={body} onConfirm={async () => { await api(`${path}/${active ? 'deactivate' : 'activate'}`, 'POST'); await onDone(); }} />;
}

function Application({ project, target, environments, manage, orgAdmin }: { project: string; target: Target; environments: Environment[]; manage: boolean; orgAdmin: boolean }) {
  const client = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [authorizing, setAuthorizing] = useState<Environment | null>(null);
  const [moding, setModing] = useState<Environment | null>(null);
  const [editing, setEditing] = useState<Environment | null>(null);
  const refresh = () => Promise.all([client.invalidateQueries({ queryKey: ['targets', project] }), client.invalidateQueries({ queryKey: ['environments', project] })]);
  return (
    <Card pad={false} testId="application-card" title={<Stack direction="row" spacing={1} alignItems="center"><span>{target.name}</span><Tag>{target.type === 'WEB' ? 'Web' : 'API'}</Tag>{!target.active && <Tag title="No environment of this application can be run until it is activated again.">Inactive</Tag>}</Stack>} action={manage && (
      <Stack direction="row" spacing={0.5}>
        <Button onClick={() => setAdding(true)}>Add environment</Button>
        <ActiveSwitch kind="application" name={target.name} active={target.active} path={`/projects/${project}/targets/${target.id}`} onDone={refresh} />
        <Confirm label="Archive" color="error" title="Archive application" body={`Archive ${target.name} and its environments? Past runs and defects are kept.`} onConfirm={async () => { await api(`/projects/${project}/targets/${target.id}/archive`, 'POST'); await refresh(); }} />
      </Stack>)}>
      {target.description && <Typography variant="body2" color="text.secondary" sx={{ px: 2, pb: 1 }}>{target.description}</Typography>}
      {environments.length === 0 ? <Empty title="No environments">Add an environment to give this application somewhere to run.</Empty> : (
        <DataTable label={`${target.name} environments`} testId="environment-row" rows={environments} rowKey={(e) => e.id} columns={[
          { key: 'name', label: 'Environment', render: (e) => <><Typography variant="body1" sx={{ fontWeight: 560 }}>{e.name}</Typography><Typography variant="caption">{e.classification}</Typography></> },
          { key: 'url', label: 'Base URL', render: (e) => <Mono>{e.baseUrl}</Mono> },
          { key: 'mode', label: 'Execution', render: (e) => <ExecutionMode e={e} /> },
          { key: 'active', label: 'Status', render: (e) => (e.active ? 'Active' : <Tag title="This environment cannot be run until it is activated again.">Inactive</Tag>) },
          { key: 'policy', label: 'Policy', render: (e) => <Tag title={e.mode === 'READ_ONLY' ? 'Reads only. Mutation is disabled.' : 'Also types into fields to check validation. Never submits.'}>{e.mode === 'READ_ONLY' ? 'Read-only' : e.mode === 'VALIDATION_ONLY' ? 'Validation only' : e.mode}</Tag> },
          { key: 'auth', label: 'Authentication', render: (e) => (e.credentialName ? `${e.credentialName} (${e.loginMethod})` : 'None') },
          { key: 'status', label: 'Last run', render: (e) => (e.lastRunStatus ? <Stack direction="row" spacing={1} alignItems="center"><StatusChip value={e.lastRunStatus} /><Typography variant="caption">{ago(e.lastRunAt)}</Typography></Stack> : <Typography variant="caption">Not run yet</Typography>) },
          ...(manage ? [{ key: 'x', label: '', align: 'right' as const, render: (e: Environment) => (
            <Stack direction="row" justifyContent="flex-end">
              <Button onClick={() => setEditing(e)}>Edit</Button>
              {orgAdmin && <Button onClick={() => setModing(e)}>Mode</Button>}
              {orgAdmin && !e.baseUrl.startsWith(FIXTURE_URL.replace(/\/$/, '')) && <Button onClick={() => setAuthorizing(e)}>Authorization</Button>}
              <ActiveSwitch kind="environment" name={e.name} active={e.active} path={`/projects/${project}/environments/${e.id}`} onDone={refresh} />
              <Confirm label="Archive" color="error" title="Archive environment" body={`Archive ${e.name}? It can no longer be run.`} onConfirm={async () => { await api(`/projects/${project}/environments/${e.id}/archive`, 'POST'); await refresh(); }} />
            </Stack>) }] : []),
        ]} />
      )}
      {adding && <EnvironmentForm project={project} target={target} onClose={() => setAdding(false)} />}
      {editing && <EditEnvironment project={project} e={editing} onClose={() => setEditing(null)} />}
      {authorizing && <AuthorizationDialog project={project} e={authorizing} onClose={() => setAuthorizing(null)} />}
      {moding && <ModeDialog project={project} e={moding} onClose={() => setModing(null)} />}
    </Card>
  );
}

function Body({ project }: { project: string }) {
  const { allowed, me } = useWorkspace();
  const manage = allowed('PROJECT_ADMIN');
  const targets = useQuery({ queryKey: ['targets', project], queryFn: () => api<Target[]>(`/projects/${project}/targets`) });
  const envs = useQuery({ queryKey: ['environments', project], queryFn: () => api<Environment[]>(`/projects/${project}/environments`) });
  const [v, set] = useState({ name: '', type: 'WEB', description: '' });
  const create = useForm<void>(() => api(`/projects/${project}/targets`, 'POST', v), [['targets', project]]);
  return (
    <>
      <PageHeader title="Applications & Environments" description="What this project tests and where. Connection status comes from the last run: the platform never contacts a target itself."
        actions={manage && <Stack direction="row" spacing={1}><Button variant="contained" component={RouterLink} to="/applications/new">Set up application</Button><Button variant="outlined" onClick={create.show}>New application</Button></Stack>} />
      <Alert severity="warning" sx={{ mb: 2 }}>A real target runs only with both keys: its host approved by an operator in the deployment, and a written authorization in date recorded here by an Organization Admin. Each environment says which key it is missing.</Alert>
      <Async q={targets}>{(rows) => (rows.length === 0 ? <Card><Empty title="No applications yet" action={manage ? <Button variant="contained" onClick={create.show}>New application</Button> : undefined}>Add a web or API application to this project.</Empty></Card> : (
        <Stack spacing={2}>{rows.map((t) => <Application key={t.id} project={project} target={t} manage={manage} orgAdmin={manage && me.organizationAdmin} environments={(envs.data ?? []).filter((e) => e.targetId === t.id)} />)}</Stack>
      ))}</Async>
      <FormDialog open={create.open} title="New application" submit="Create application" busy={create.busy} error={create.error} onClose={create.close} onSubmit={() => create.submit()}>
        <TextField label="Application name" required value={v.name} onChange={(e) => set({ ...v, name: e.target.value })} />
        <Choice label="Application type" value={v.type} onChange={(x) => set({ ...v, type: x })} options={[['WEB', 'Web'], ['API', 'API']]} />
        <Box><Typography variant="caption">Mobile applications are not available yet.</Typography></Box>
        <TextField label="Description" value={v.description} onChange={(e) => set({ ...v, description: e.target.value })} />
      </FormDialog>
    </>
  );
}

export const ApplicationsPage = () => <NeedsProject>{(p) => <Body project={p} />}</NeedsProject>;
