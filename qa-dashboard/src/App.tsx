import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import {
  Alert, AppBar, Box, Button, Card, CardContent, Chip, Container, Link, MenuItem, Stack, Tab, Table, TableBody, TableCell,
  TableHead, TableRow, Tabs, TextField, Toolbar, Typography,
} from '@mui/material';
import { api, ApiError, type ApiMetric, type Defect, type Environment, type Me, type Project, type Run, type Step, type Summary, type Target, type TestCase, type Trace } from './api';

const severityColor: Record<string, 'error' | 'warning' | 'info' | 'default'> = { CRITICAL: 'error', HIGH: 'error', MEDIUM: 'warning', LOW: 'info' };

export function App() {
  const client = useQueryClient();
  const me = useQuery({ queryKey: ['me'], queryFn: () => api<Me>('/auth/me') });
  if (me.isLoading) return null;
  if (me.isError) return <Login />;
  const logout = async () => {
    await api('/auth/logout', 'POST');
    client.clear();
    window.location.reload();
  };
  return (
    <>
      <AppBar position="static" color="default" elevation={1}>
        <Toolbar>
          <Link component={RouterLink} to="/" underline="none" color="inherit" sx={{ flexGrow: 1 }}>
            <Typography variant="h6">QA Engine</Typography>
          </Link>
          <Typography variant="body2" sx={{ mr: 2 }}>{me.data!.email}</Typography>
          <Button onClick={logout}>Sign out</Button>
        </Toolbar>
      </AppBar>
      <Container sx={{ py: 3 }}>
        <Routes>
          <Route path="/" element={<Projects me={me.data!} />} />
          <Route path="/projects/:projectId" element={<ProjectPage />} />
          <Route path="/projects/:projectId/runs/:runId" element={<RunPage />} />
          <Route path="/projects/:projectId/defects/:defectId" element={<DefectPage />} />
        </Routes>
      </Container>
    </>
  );
}

function Login() {
  const client = useQueryClient();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totp, setTotp] = useState('');
  const [error, setError] = useState('');
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      await api('/auth/csrf');
      await api('/auth/login', 'POST', { email, password, totp: totp || null });
      await api('/auth/csrf');
      await client.invalidateQueries({ queryKey: ['me'] });
    } catch (err) {
      setError(err instanceof ApiError && err.status === 429 ? 'Too many attempts. Try again later.' : 'Sign-in failed.');
    }
  };
  return (
    <Container maxWidth="xs" sx={{ mt: 10 }}>
      <Typography variant="h5" gutterBottom>QA Engine</Typography>
      <Stack component="form" spacing={2} onSubmit={submit}>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="username" />
        <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoComplete="current-password" />
        <TextField label="Authenticator code (if enrolled)" value={totp} onChange={(e) => setTotp(e.target.value)} autoComplete="one-time-code" />
        <Button type="submit" variant="contained">Sign in</Button>
      </Stack>
    </Container>
  );
}

function Projects({ me }: { me: Me }) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api<Project[]>('/projects') });
  const create = useMutation({
    mutationFn: () => api<Project>('/projects', 'POST', { name }),
    onSuccess: (p) => { client.invalidateQueries({ queryKey: ['projects'] }); navigate(`/projects/${p.id}`); },
  });
  return (
    <Stack spacing={2}>
      <Typography variant="h5">Projects</Typography>
      {projects.data?.map((p) => (
        <Card key={p.id} variant="outlined">
          <CardContent><Link component={RouterLink} to={`/projects/${p.id}`}>{p.name}</Link></CardContent>
        </Card>
      ))}
      {projects.data?.length === 0 && <Typography color="text.secondary">No projects yet.</Typography>}
      {me.organizationAdmin && (
        <Stack direction="row" spacing={1}>
          <TextField size="small" label="New project name" value={name} onChange={(e) => setName(e.target.value)} />
          <Button variant="contained" disabled={!name} onClick={() => create.mutate()}>Create project</Button>
        </Stack>
      )}
      {create.isError && <Alert severity="error">The project could not be created.</Alert>}
    </Stack>
  );
}

function ProjectPage() {
  const { projectId } = useParams();
  const [tab, setTab] = useState('overview');
  return (
    <Stack spacing={2}>
      <Tabs value={tab} onChange={(_, v) => setTab(v)}>
        <Tab value="overview" label="Overview" />
        <Tab value="targets" label="Targets" />
        <Tab value="runs" label="Runs" />
        <Tab value="defects" label="Defects" />
        <Tab value="cases" label="Test cases" />
      </Tabs>
      {tab === 'overview' && <Overview projectId={projectId!} />}
      {tab === 'targets' && <Targets projectId={projectId!} />}
      {tab === 'runs' && <Runs projectId={projectId!} />}
      {tab === 'defects' && <Defects projectId={projectId!} />}
      {tab === 'cases' && <Cases projectId={projectId!} />}
    </Stack>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <Card variant="outlined" sx={{ minWidth: 140 }}>
      <CardContent>
        <Typography variant="h4">{value}</Typography>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
      </CardContent>
    </Card>
  );
}

function Overview({ projectId }: { projectId: string }) {
  const s = useQuery({ queryKey: ['summary', projectId], queryFn: () => api<Summary>(`/projects/${projectId}/summary`) });
  if (!s.data) return null;
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
        <Stat label="Open defects" value={s.data.openDefects} />
        <Stat label="Runs" value={s.data.runs} />
        <Stat label="Test cases" value={s.data.testCases} />
        <Stat label="States covered (latest run)" value={s.data.pagesCovered} />
        <Stat label="Blocked actions (latest run)" value={s.data.blockedActions} />
      </Stack>
      <Typography variant="subtitle1">Open defects by severity</Typography>
      <Stack direction="row" spacing={1}>
        {Object.entries(s.data.defectsBySeverity).map(([k, v]) => <Chip key={k} label={`${k}: ${v}`} color={severityColor[k] ?? 'default'} />)}
        {Object.keys(s.data.defectsBySeverity).length === 0 && <Typography color="text.secondary">None.</Typography>}
      </Stack>
      {s.data.latestRun && (
        <Stack direction="row" spacing={2}>
          <Link href={`/api/v1/projects/${projectId}/runs/${s.data.latestRun}/report.html`} target="_blank" rel="noreferrer">Latest report (HTML, printable)</Link>
          <Link href={`/api/v1/projects/${projectId}/runs/${s.data.latestRun}/report.xlsx`}>Latest report (Excel)</Link>
        </Stack>
      )}
    </Stack>
  );
}

function Targets({ projectId }: { projectId: string }) {
  const client = useQueryClient();
  const [targetName, setTargetName] = useState('');
  const [envName, setEnvName] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [classification, setClassification] = useState('LOCAL');
  const [channel, setChannel] = useState('WEB');
  const [specPath, setSpecPath] = useState('');
  const [target, setTarget] = useState('');
  const targets = useQuery({ queryKey: ['targets', projectId], queryFn: () => api<Target[]>(`/projects/${projectId}/targets`) });
  const envs = useQuery({ queryKey: ['envs', projectId], queryFn: () => api<Environment[]>(`/projects/${projectId}/environments`) });
  const addTarget = useMutation({
    mutationFn: () => api(`/projects/${projectId}/targets`, 'POST', { name: targetName }),
    onSuccess: () => { setTargetName(''); client.invalidateQueries({ queryKey: ['targets', projectId] }); },
  });
  const addEnv = useMutation({
    mutationFn: () => api(`/projects/${projectId}/targets/${target}/environments`, 'POST', { name: envName, classification, baseUrl, channel, apiSpecPath: specPath || null }),
    onSuccess: () => { setEnvName(''); setBaseUrl(''); client.invalidateQueries({ queryKey: ['envs', projectId] }); },
  });
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1}>
        <TextField size="small" label="Target name" value={targetName} onChange={(e) => setTargetName(e.target.value)} />
        <Button variant="outlined" disabled={!targetName} onClick={() => addTarget.mutate()}>Add target</Button>
      </Stack>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        <TextField select size="small" label="Target" value={target} onChange={(e) => setTarget(e.target.value)} sx={{ minWidth: 160 }}>
          {targets.data?.map((t) => <MenuItem key={t.id} value={t.id}>{t.name}</MenuItem>)}
        </TextField>
        <TextField size="small" label="Environment name" value={envName} onChange={(e) => setEnvName(e.target.value)} />
        <TextField select size="small" label="Class" value={classification} onChange={(e) => setClassification(e.target.value)} sx={{ minWidth: 130 }}>
          {['LOCAL', 'DEV', 'STAGING', 'PRODUCTION'].map((c) => <MenuItem key={c} value={c}>{c}</MenuItem>)}
        </TextField>
        <TextField size="small" label="Base URL" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} sx={{ minWidth: 280 }} />
        <TextField select size="small" label="Channel" value={channel} onChange={(e) => setChannel(e.target.value)} sx={{ minWidth: 100 }}>
          {['WEB', 'API'].map((c) => <MenuItem key={c} value={c}>{c}</MenuItem>)}
        </TextField>
        {channel === 'API' && <TextField size="small" label="OpenAPI path (optional)" value={specPath} onChange={(e) => setSpecPath(e.target.value)} />}
        <Button variant="outlined" disabled={!target || !envName || !baseUrl} onClick={() => addEnv.mutate()}>Add environment</Button>
      </Stack>
      {addEnv.isError && <Alert severity="error">The environment could not be added. Production needs explicit include paths.</Alert>}
      <Table size="small">
        <TableHead><TableRow><TableCell>Environment</TableCell><TableCell>Class</TableCell><TableCell>Channel</TableCell><TableCell>Base URL</TableCell><TableCell>Mode</TableCell></TableRow></TableHead>
        <TableBody>
          {envs.data?.map((e) => (
            <TableRow key={e.id}><TableCell>{e.name}</TableCell><TableCell>{e.classification}</TableCell><TableCell>{e.channel}</TableCell><TableCell>{e.baseUrl}</TableCell><TableCell><Chip size="small" label={e.mode} /></TableCell></TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
}

function Runs({ projectId }: { projectId: string }) {
  const client = useQueryClient();
  const [env, setEnv] = useState('');
  const envs = useQuery({ queryKey: ['envs', projectId], queryFn: () => api<Environment[]>(`/projects/${projectId}/environments`) });
  const runs = useQuery({
    queryKey: ['runs', projectId],
    queryFn: () => api<Run[]>(`/projects/${projectId}/runs`),
    refetchInterval: (q) => (q.state.data?.some((r) => ['QUEUED', 'RUNNING'].includes(r.status)) ? 3000 : false),
  });
  const start = useMutation({
    mutationFn: () => api(`/projects/${projectId}/runs`, 'POST', { environmentId: env }, { 'Idempotency-Key': crypto.randomUUID() }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['runs', projectId] }),
  });
  const cancel = useMutation({
    mutationFn: (id: string) => api(`/projects/${projectId}/runs/${id}/cancel`, 'POST'),
    onSuccess: () => client.invalidateQueries({ queryKey: ['runs', projectId] }),
  });
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1}>
        <TextField select size="small" label="Environment" value={env} onChange={(e) => setEnv(e.target.value)} sx={{ minWidth: 220 }}>
          {envs.data?.map((e) => <MenuItem key={e.id} value={e.id}>{e.name} ({e.classification})</MenuItem>)}
        </TextField>
        <Button variant="contained" disabled={!env || start.isPending} onClick={() => start.mutate()}>Start read-only run</Button>
      </Stack>
      <Table size="small">
        <TableHead><TableRow><TableCell>Started</TableCell><TableCell>Status</TableCell><TableCell>Stop reason</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {runs.data?.map((r) => (
            <TableRow key={r.id}>
              <TableCell><Link component={RouterLink} to={`/projects/${projectId}/runs/${r.id}`}>{new Date(r.createdAt).toLocaleString()}</Link></TableCell>
              <TableCell data-testid="run-status">{r.status}</TableCell>
              <TableCell>{r.stopReason}</TableCell>
              <TableCell>{['QUEUED', 'RUNNING'].includes(r.status) && <Button size="small" onClick={() => cancel.mutate(r.id)}>Cancel</Button>}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
}

function RunPage() {
  const { projectId, runId } = useParams();
  const steps = useQuery({ queryKey: ['steps', runId], queryFn: () => api<Step[]>(`/projects/${projectId}/runs/${runId}/steps`), refetchInterval: 4000 });
  const metrics = useQuery({ queryKey: ['metrics', runId], queryFn: () => api<ApiMetric[]>(`/projects/${projectId}/runs/${runId}/api-metrics`), refetchInterval: 4000 });
  return (
    <Stack spacing={2}>
      <Typography variant="h5">Run steps</Typography>
      <Stack direction="row" spacing={2}>
        <Link href={`/api/v1/projects/${projectId}/runs/${runId}/report.html`} target="_blank" rel="noreferrer">Report (HTML, printable)</Link>
        <Link href={`/api/v1/projects/${projectId}/runs/${runId}/report.xlsx`}>Report (Excel)</Link>
      </Stack>
      {(metrics.data?.length ?? 0) > 0 && (
        <>
          <Typography variant="h6">API operations</Typography>
          <Table size="small" data-testid="api-metrics">
            <TableHead><TableRow><TableCell>Operation</TableCell><TableCell>Calls</TableCell><TableCell>Passed</TableCell><TableCell>Failed</TableCell><TableCell>Blocked</TableCell><TableCell>Average ms</TableCell><TableCell>p95 ms</TableCell><TableCell>Max ms</TableCell></TableRow></TableHead>
            <TableBody>
              {metrics.data?.map((m) => (
                <TableRow key={m.operation}>
                  <TableCell>{m.operation}</TableCell><TableCell>{m.calls}</TableCell><TableCell>{m.passed}</TableCell><TableCell>{m.failed}</TableCell><TableCell>{m.blocked}</TableCell><TableCell>{m.averageMs}</TableCell><TableCell>{m.p95Ms}</TableCell><TableCell>{m.maxMs}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </>
      )}
      <Table size="small">
        <TableHead><TableRow><TableCell>#</TableCell><TableCell>Action</TableCell><TableCell>URL</TableCell><TableCell>Label</TableCell><TableCell>Verdict</TableCell><TableCell>Note</TableCell></TableRow></TableHead>
        <TableBody>
          {steps.data?.map((s) => (
            <TableRow key={`${s.attempt}-${s.sequence}`}>
              <TableCell>{s.sequence}</TableCell><TableCell>{s.kind}</TableCell><TableCell>{s.url}</TableCell><TableCell>{s.label}</TableCell><TableCell>{s.verdict}</TableCell><TableCell>{s.note}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
}

function Defects({ projectId }: { projectId: string }) {
  const defects = useQuery({ queryKey: ['defects', projectId], queryFn: () => api<Defect[]>(`/projects/${projectId}/defects`) });
  return (
    <Table size="small">
      <TableHead><TableRow><TableCell>Severity</TableCell><TableCell>Title</TableCell><TableCell>URL</TableCell><TableCell>Status</TableCell><TableCell>Seen</TableCell></TableRow></TableHead>
      <TableBody>
        {defects.data?.map((d) => (
          <TableRow key={d.id} data-testid="defect-row">
            <TableCell><Chip size="small" label={d.severity} color={severityColor[d.severity] ?? 'default'} /></TableCell>
            <TableCell><Link component={RouterLink} to={`/projects/${projectId}/defects/${d.id}`}>{d.title}</Link></TableCell>
            <TableCell>{d.url}</TableCell><TableCell>{d.status}</TableCell><TableCell>{d.occurrences}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function DefectPage() {
  const { projectId, defectId } = useParams();
  const client = useQueryClient();
  const trace = useQuery({ queryKey: ['defect', defectId], queryFn: () => api<Trace>(`/projects/${projectId}/defects/${defectId}`) });
  const setStatus = useMutation({
    mutationFn: (status: string) => api(`/projects/${projectId}/defects/${defectId}`, 'PATCH', { status }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['defect', defectId] }),
  });
  if (!trace.data) return null;
  const { defect, findings, testCases } = trace.data;
  return (
    <Stack spacing={2}>
      <Typography variant="h5">{defect.title}</Typography>
      <Stack direction="row" spacing={1} alignItems="center">
        <Chip label={defect.severity} color={severityColor[defect.severity] ?? 'default'} />
        <Chip label={defect.status} variant="outlined" />
        <Typography variant="body2">{defect.url}</Typography>
      </Stack>
      <Typography variant="body2">{defect.detail}</Typography>
      <Stack direction="row" spacing={1}>
        {['TRIAGED', 'FIXED', 'WONT_FIX'].map((s) => <Button key={s} size="small" variant="outlined" onClick={() => setStatus.mutate(s)}>{s}</Button>)}
      </Stack>
      <Typography variant="body2" data-testid="linked-cases">Linked test cases: {testCases.length}</Typography>
      {findings.map((f) => (
        <Card key={`${f.runId}-${f.attempt}`} variant="outlined">
          <CardContent>
            <Typography variant="subtitle1">Steps to reproduce</Typography>
            <ol>{f.reproduction.map((r) => <li key={r.sequence}>Open {r.url}{r.label ? ` (via “${r.label}”)` : ''}</li>)}</ol>
            <Typography variant="subtitle1">Evidence</Typography>
            <Stack spacing={1}>
              {f.evidence.map((e) => e.type === 'SCREENSHOT'
                ? <Box key={e.id} component="img" alt="Screenshot evidence" data-testid="evidence-image" src={`/api/v1/projects/${projectId}/evidence/${e.id}/content`} sx={{ maxWidth: '100%', border: 1, borderColor: 'divider' }} />
                : <Link key={e.id} href={`/api/v1/projects/${projectId}/evidence/${e.id}/content`}>{e.type} ({e.size} bytes)</Link>)}
            </Stack>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}

function Cases({ projectId }: { projectId: string }) {
  const cases = useQuery({ queryKey: ['cases', projectId], queryFn: () => api<TestCase[]>(`/projects/${projectId}/test-cases`) });
  return (
    <Table size="small">
      <TableHead><TableRow><TableCell>Test case</TableCell><TableCell>Version</TableCell><TableCell>Last result</TableCell></TableRow></TableHead>
      <TableBody>
        {cases.data?.map((c) => <TableRow key={c.id}><TableCell>{c.title}</TableCell><TableCell>{c.latestVersion}</TableCell><TableCell>{c.lastVerdict}</TableCell></TableRow>)}
      </TableBody>
    </Table>
  );
}
