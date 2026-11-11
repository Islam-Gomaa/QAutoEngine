import { useEffect, useMemo, useState } from 'react';
import { Alert, Box, Button, Link, MenuItem, Stack, Tab, Tabs, TextField, Tooltip, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, query, type ApiMetric, type AuthAttempt, type Environment, type EvidenceItem, type Page, type Progress, type Run, type RunFinding, type Step, type Suite } from '../api';
import { SegmentBar } from '../design/charts';
import { Async, Card, Confirm, DataTable, dateTime, duration, Empty, Failure, KeyValue, Mono, PageHeader, ScrollRegion, Stat, StatusChip, Tag, words } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { EvidenceViewer } from './EvidenceViewer';
import { Choice, NeedsProject } from './shared';
import { DiscoveryPanel } from './Discovery';
import { FAILURE_LABELS, TestResult, testOutcome } from './signin';

/** Chosen in the scope list; discovery is a kind of run rather than a suite. */
const DISCOVERY = '__discovery';

function Starter({ project }: { project: string }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [params] = useSearchParams();
  const envs = useQuery({ queryKey: ['environments', project], queryFn: () => api<Environment[]>(`/projects/${project}/environments`) });
  const suites = useQuery({ queryKey: ['suites', project, 0], queryFn: () => api<Suite[]>(`/projects/${project}/suites`) });
  const [environmentId, setEnvironment] = useState('');
  const [suiteId, setSuite] = useState(params.get('suite') ?? '');
  const [maxPages, setMaxPages] = useState('');
  const env = envs.data?.find((e) => e.id === environmentId);
  const runnable = useMemo(() => (suites.data ?? []).filter((s) => s.automatedCases > 0 && (!env || s.channel === env.channel)), [suites.data, env]);
  const start = useMutation({
    mutationFn: () => api<Run>(`/projects/${project}/runs`, 'POST', { environmentId, suiteId: suiteId && suiteId !== DISCOVERY ? suiteId : null, maxPages: maxPages ? Number(maxPages) : null,
      ...(suiteId === DISCOVERY ? { kind: 'DISCOVERY' } : {}) }, { 'Idempotency-Key': crypto.randomUUID() }),
    onSuccess: (r) => { void client.invalidateQueries({ queryKey: ['runs', project] }); navigate(`/execution/${r.id}`); },
  });
  if (envs.data?.length === 0) return <Empty title="No environments to run" action={<Button component={RouterLink} to="/applications" variant="contained">Add an application</Button>}>Add an application and a fixture environment first.</Empty>;
  return (
    <Stack spacing={1.5}>
      {start.isError && <Failure error={start.error} />}
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}>
        <TextField select label="Environment" value={environmentId} onChange={(e) => { setEnvironment(e.target.value); setSuite(''); }} sx={{ minWidth: 300 }}>
          {envs.data?.map((e) => { const inactive = !e.active || !e.applicationActive; return <MenuItem key={e.id} value={e.id} disabled={!e.executable || inactive}>{e.targetName} / {e.name} ({e.channel === 'WEB' ? 'Web' : 'API'}){e.executable ? '' : ', locked'}{inactive ? ', inactive' : ''}</MenuItem>; })}
        </TextField>
        <TextField select label="Scope" value={suiteId} onChange={(e) => setSuite(e.target.value)} sx={{ minWidth: 240 }}>
          <MenuItem value="">{env?.channel === 'API' ? 'Full API contract' : 'Full exploration'}</MenuItem>
          {env?.channel !== 'API' && <MenuItem value={DISCOVERY}>Discovery: record every page and action</MenuItem>}
          {runnable.map((s) => <MenuItem key={s.id} value={s.id}>{s.name} ({s.automatedCases} automated)</MenuItem>)}
        </TextField>
        {env?.channel !== 'API' && <TextField label="Page limit (optional)" type="number" value={maxPages} onChange={(e) => setMaxPages(e.target.value)} sx={{ width: 170 }} slotProps={{ htmlInput: { min: 1 } }} />}
        <Button variant="contained" disabled={!environmentId || start.isPending} onClick={() => start.mutate()} sx={{ flexShrink: 0 }}>Start fixture run</Button>
      </Stack>
      <Typography variant="caption">Runs are read-only, and queued for a worker. Only the local fixture is available until the pre-pilot security gate passes.</Typography>
    </Stack>
  );
}

function Body({ project }: { project: string }) {
  const { allowed } = useWorkspace();
  const navigate = useNavigate();
  const [status, setStatus] = useState('');
  const [channel, setChannel] = useState('');
  const [page, setPage] = useState(0);
  const q = useQuery({ queryKey: ['runs', project, status, channel, page], placeholderData: keepPreviousData, refetchInterval: 4000, queryFn: () => api<Page<Run>>(`/projects/${project}/runs${query({ status, channel, page, size: 25 })}`) });
  return (
    <>
      <PageHeader title="Test Execution" description="Start runs and follow them as workers report back." />
      <Stack spacing={2}>
        {allowed('TESTER') ? <Card title="Start a run"><Starter project={project} /></Card> : <Alert severity="info">Your role can view runs but not start them.</Alert>}
        <Card pad={false} title="Executions" action={
          <Stack direction="row" spacing={1}>
            <Choice label="Status" width={130} value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[['', 'All'], ['QUEUED', 'Queued'], ['RUNNING', 'Running'], ['SUCCEEDED', 'Completed'], ['FAILED', 'Failed'], ['CANCELLED', 'Cancelled']]} />
            <Choice label="Type" width={110} value={channel} onChange={(v) => { setChannel(v); setPage(0); }} options={[['', 'All'], ['WEB', 'Web'], ['API', 'API']]} />
          </Stack>}>
          <Async q={q}>{(p) => (p.items.length === 0 ? <Empty title="No executions" /> : (
            <DataTable label="Executions" testId="run-row" rows={p.items} rowKey={(r) => r.id} onRowClick={(r) => navigate(`/execution/${r.id}`)} paging={{ page, size: 25, total: p.total, onPage: setPage }} columns={[
              { key: 'app', label: 'Application', render: (r) => <><Typography variant="body1">{r.applicationName} / {r.environmentName}</Typography><Typography variant="caption">{r.suiteName ?? 'Full scope'}</Typography></> },
              { key: 'type', label: 'Type', render: (r) => <Tag>{r.channel === 'WEB' ? 'Web' : 'API'}</Tag> },
              { key: 'status', label: 'Status', render: (r) => <StatusChip value={r.status} testId="run-status" /> },
              { key: 'p', label: 'Passed', align: 'right', render: (r) => r.passed }, { key: 'f', label: 'Failed', align: 'right', render: (r) => r.failed }, { key: 'b', label: 'Blocked', align: 'right', render: (r) => r.blocked },
              { key: 'by', label: 'Started by', render: (r) => r.startedBy }, { key: 'at', label: 'Started', render: (r) => dateTime(r.createdAt) },
            ]} />))}</Async>
        </Card>
      </Stack>
    </>
  );
}

const CONSOLE = ['SCRIPT_ERROR', 'CONSOLE_ERROR'];
const NETWORK = ['FAILED_REQUEST', 'BROKEN_RESOURCE', 'SERVER_ERROR', 'BROKEN_PAGE', 'NAVIGATION_FAILURE'];

function Findings({ rows, none }: { rows: RunFinding[]; none: string }) {
  return rows.length === 0 ? <Empty title={none} /> : (
    <DataTable label="Findings" rows={rows} rowKey={(r) => r.defectId} columns={[
      { key: 'id', label: 'Defect', render: (r) => <Link component={RouterLink} to={`/defects/${r.defectId}`}>DEF-{r.number}</Link> },
      { key: 't', label: 'Summary', render: (r) => <><Typography variant="body1">{r.title}</Typography><Typography variant="caption" component="div" sx={{ overflowWrap: 'anywhere' }}>{r.url}</Typography></> },
      { key: 'd', label: 'Detail', render: (r) => <Typography variant="body2" sx={{ overflowWrap: 'anywhere', maxWidth: 360 }}>{r.detail}</Typography> },
      { key: 's', label: 'Severity', render: (r) => <StatusChip value={r.severity} /> }, { key: 'n', label: 'Step', align: 'right', render: (r) => r.step },
    ]} />
  );
}

function Detail({ project, id }: { project: string; id: string }) {
  const { tokens } = useTheme();
  const { allowed } = useWorkspace();
  const client = useQueryClient();
  const [chosen, setTab] = useState<string | null>(null);
  const [verdict, setVerdict] = useState('');
  const progress = useQuery({ queryKey: ['progress', id], queryFn: () => api<Progress>(`/projects/${project}/runs/${id}/progress`), refetchInterval: (s) => (s.state.data?.run.finishedAt ? false : 2000) });
  const finished = !!progress.data?.run.finishedAt;
  const tab = chosen ?? (progress.data?.run.kind === 'DISCOVERY' ? 'discovery' : 'steps');
  const live = finished ? false as const : 3000;
  const steps = useQuery({ queryKey: ['steps', id], queryFn: () => api<Step[]>(`/projects/${project}/runs/${id}/steps`), refetchInterval: live });
  const findings = useQuery({ queryKey: ['findings', id], queryFn: () => api<RunFinding[]>(`/projects/${project}/runs/${id}/findings`), refetchInterval: live });
  const evidence = useQuery({ queryKey: ['run-evidence', id], queryFn: () => api<Page<EvidenceItem>>(`/projects/${project}/evidence${query({ runId: id, size: 100 })}`), enabled: tab === 'evidence', refetchInterval: live });
  const auth = useQuery({ queryKey: ['run-auth', id], queryFn: () => api<AuthAttempt[]>(`/projects/${project}/runs/${id}/auth`), refetchInterval: live });
  const metrics = useQuery({ queryKey: ['metrics', id], queryFn: () => api<ApiMetric[]>(`/projects/${project}/runs/${id}/api-metrics`), enabled: tab === 'api', refetchInterval: live });
  // Polling stops when the run ends, so read everything once more to catch the last results.
  useEffect(() => {
    if (finished) ['steps', 'findings', 'run-evidence', 'metrics', 'run-auth'].forEach((k) => void client.invalidateQueries({ queryKey: [k, id] }));
  }, [finished, client, id]);
  return (
    <Async q={progress}>{(p) => {
      const r = p.run;
      const s = tokens.status;
      const list = (steps.data ?? []).filter((x) => !verdict || x.verdict === verdict);
      const f = findings.data ?? [];
      return (
        <>
          <PageHeader title={`${r.applicationName} / ${r.environmentName}`} description={r.kind === 'AUTH_TEST' ? 'Connection test: opens the environment and signs in if a sign-in is set up' : r.kind === 'DISCOVERY' ? 'Discovery: records every page, form and action it can reach, and uses none that change anything' : r.suiteName ? `Suite: ${r.suiteName}` : r.channel === 'API' ? 'Full API contract run' : 'Full exploration run'} actions={<>
            <StatusChip value={r.status} testId="run-status" />
            {finished && <Button variant="outlined" component="a" href={`/api/v1/projects/${project}/runs/${id}/report.html`} target="_blank" rel="noopener noreferrer">HTML report</Button>}
            {finished && <Button variant="outlined" component="a" href={`/api/v1/projects/${project}/runs/${id}/report.xlsx`} download>Excel report</Button>}
            {!finished && allowed('TESTER') && <Confirm label="Cancel run" color="error" title="Cancel run" body="Stop this run? Results so far are kept." onConfirm={async () => { await api(`/projects/${project}/runs/${id}/cancel`, 'POST'); await client.invalidateQueries({ queryKey: ['progress', id] }); }} />}
          </>} />
          <Stack spacing={2}>
            <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2,1fr)', md: 'repeat(5,1fr)' } }}>
              <Stat label="Passed" value={r.passed} /><Stat label="Failed" value={r.failed} tone={r.failed ? 'critical' : undefined} /><Stat label="Blocked" value={r.blocked} hint="Refused by the safety gate" />
              <Stat label="Defects" value={p.defects} /><Stat label="Duration" value={duration(p.durationSeconds)} hint={finished ? 'Total' : 'So far'} />
            </Box>
            <Card title={finished ? 'Outcome' : 'Progress'} testId="run-progress">
              {!finished && <Typography variant="body2" sx={{ mb: 1 }} aria-live="polite">{p.jobState === 'QUEUED' ? 'Waiting for a worker' : <>Current operation: <Mono>{p.currentOperation || 'starting'}</Mono></>}</Typography>}
              {finished && <Typography variant="body2" sx={{ mb: 1 }}>Stopped because: {r.stopReason ? words(r.stopReason) : 'not recorded'}</Typography>}
              <SegmentBar parts={[{ label: 'Passed', value: r.passed, color: s.good.mark }, { label: 'Failed', value: r.failed, color: s.critical.mark }, { label: 'Blocked', value: r.blocked, color: s.warning.mark }, { label: 'Inconclusive', value: r.inconclusive, color: s.neutral.mark }]} />
              {!finished && <Typography variant="caption" component="div" sx={{ mt: 1 }}>There is no percentage: the size of an exploration is not known until it ends.</Typography>}
            </Card>
            {(auth.data ?? []).length > 0 && (() => {
              const a = auth.data![auth.data!.length - 1];
              return (
                <Card title={r.kind === 'AUTH_TEST' ? 'Connection test' : 'Sign-in'} testId="run-auth">
                  <Stack direction={{ xs: 'column', md: 'row' }} spacing={3}>
                    <TestResult test={{ status: a.status, failure: a.failure, detail: a.detail }} />
                    <KeyValue items={[['Stage reached', STAGES[a.stage] ?? words(a.stage)], ['Took', `${(a.durationMs / 1000).toFixed(1)} s`], ['Attempt', String(a.attempt)]]} />
                  </Stack>
                  {auth.data!.length > 1 && (
                    <Box component="ul" sx={{ m: 0, mt: 2, pl: 2.5 }} aria-label="Sign-ins in this run">
                      {auth.data!.map((x) => (
                        <Typography component="li" variant="body2" key={`${x.attempt}-${x.signIn ?? 1}`}>
                          {(x.signIn ?? 1) === 2 ? 'Signed in again after the session ended' : 'Sign-in'}{auth.data!.some((y) => y.attempt !== x.attempt) ? ` (attempt ${x.attempt})` : ''}: {testOutcome(x).label}
                          {x.failure ? `, ${FAILURE_LABELS[x.failure] ?? x.failure}` : ''}
                        </Typography>
                      ))}
                    </Box>
                  )}
                  {a.status === 'WAITING_FOR_OTP' && !finished && <OtpPrompt project={project} run={id} />}
                </Card>
              );
            })()}
            <Box>
              <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }} variant="scrollable">
                {r.kind === 'DISCOVERY' && <Tab value="discovery" label="Discovery" />}
                <Tab value="steps" label={`Steps (${steps.data?.length ?? 0})`} /><Tab value="defects" label={`Defects (${f.length})`} />
                {r.channel === 'WEB' && <Tab value="console" label="Console errors" />}{r.channel === 'WEB' && <Tab value="network" label="Network failures" />}
                <Tab value="evidence" label="Evidence" />{r.channel === 'API' && <Tab value="api" label="API metrics" />}<Tab value="meta" label="Metadata" />
              </Tabs>
              {tab === 'steps' && (
                <Card pad={false} title="Step log" action={<Choice label="Result" width={140} value={verdict} onChange={setVerdict} options={[['', 'All'], ['PASS', 'Passed'], ['FAIL', 'Failed'], ['BLOCKED', 'Blocked'], ['INCONCLUSIVE', 'Inconclusive']]} />}>
                  <Async q={steps}>{() => (list.length === 0 ? <Empty title="No steps yet" /> : (
                    <ScrollRegion label="Step log" maxHeight={520}><DataTable label="Steps" testId="step-row" rows={list} rowKey={(x) => `${x.attempt}-${x.sequence}`} columns={[
                      { key: 'n', label: '#', render: (x) => x.sequence }, { key: 'k', label: 'Action', render: (x) => words(x.kind) },
                      { key: 'u', label: 'Target', render: (x) => <Tooltip title={x.url}><Typography variant="body2" noWrap sx={{ maxWidth: 420 }}>{x.label || x.url}</Typography></Tooltip> },
                      { key: 'v', label: 'Result', render: (x) => <StatusChip value={x.verdict} /> }, { key: 's', label: 'HTTP', align: 'right', render: (x) => x.status || '' }, { key: 'd', label: 'Time', align: 'right', render: (x) => (x.durationMs ? `${x.durationMs} ms` : '') },
                      { key: 'note', label: 'Note', render: (x) => <Typography variant="body2" noWrap sx={{ maxWidth: 260 }}>{x.note}</Typography> },
                    ]} /></ScrollRegion>))}</Async>
                </Card>
              )}
              {tab === 'discovery' && <DiscoveryPanel project={project} run={id} live={live} />}
              {tab === 'defects' && <Card pad={false}><Findings rows={f} none="No defects in this run" /></Card>}
              {tab === 'console' && <Card pad={false}><Findings rows={f.filter((x) => CONSOLE.includes(x.type))} none="No browser console errors" /></Card>}
              {tab === 'network' && <Card pad={false}><Findings rows={f.filter((x) => NETWORK.includes(x.type))} none="No network failures" /></Card>}
              {tab === 'evidence' && <Async q={evidence}>{(e) => (e.items.length === 0 ? <Card><Empty title="No evidence stored for this run" /></Card> : (
                <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>{e.items.map((x) => <Card key={x.id} title={`${words(x.type)}, step ${x.step}`}><EvidenceViewer project={project} item={x} /></Card>)}</Box>
              ))}</Async>}
              {tab === 'api' && <Card pad={false}><Async q={metrics}>{(m) => (m.length === 0 ? <Empty title="No API calls recorded" /> : (
                <DataTable label="API metrics" rows={m} rowKey={(x) => x.operation} columns={[
                  { key: 'o', label: 'Operation', render: (x) => <Mono>{x.operation}</Mono> }, { key: 'c', label: 'Calls', align: 'right', render: (x) => x.calls }, { key: 'p', label: 'Passed', align: 'right', render: (x) => x.passed },
                  { key: 'f', label: 'Failed', align: 'right', render: (x) => x.failed }, { key: 'a', label: 'Avg ms', align: 'right', render: (x) => x.averageMs }, { key: 'p95', label: 'P95 ms', align: 'right', render: (x) => x.p95Ms }, { key: 'm', label: 'Max ms', align: 'right', render: (x) => x.maxMs },
                ]} />))}</Async></Card>}
              {tab === 'meta' && <Card><KeyValue items={[['Run ID', <Mono key="i">{r.id}</Mono>], ['Type', r.channel], ['Started by', r.startedBy], ['Started', dateTime(r.createdAt)], ['Finished', r.finishedAt ? dateTime(r.finishedAt) : 'Still running'], ['Attempt', p.attempt], ['Job state', words(p.jobState)], ['Scope', r.suiteName ?? 'Full']]} /></Card>}
            </Box>
          </Stack>
        </>
      );
    }}</Async>
  );
}

export const ExecutionPage = () => <NeedsProject>{(p) => <Body project={p} />}</NeedsProject>;
/**
 * Asks for the one-time code while the run's browser session waits for it. The value goes to the server
 * once and is cleared from the page at once; the run continues in the same session.
 */
const STAGES: Record<string, string> = { LOGIN_PAGE: 'Login page', CREDENTIALS: 'Filling the form', SUBMIT: 'Submitting', OTP: 'One-time code', VERIFY: 'Checking success', DONE: 'Done' };

function OtpPrompt({ project, run }: { project: string; run: string }) {
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    setError(undefined);
    try {
      await api(`/projects/${project}/runs/${run}/otp`, 'POST', { code });
      setSent(true);
    } catch (e) {
      setError(e);
    } finally {
      setCode('');
      setBusy(false);
    }
  };
  return (
    <Box component="form" onSubmit={(e) => { e.preventDefault(); void submit(); }} sx={{ mt: 2, pt: 2, borderTop: 1, borderColor: 'divider' }} aria-labelledby="otp-prompt-title" data-testid="otp-prompt">
      <Typography id="otp-prompt-title" variant="body1" sx={{ fontWeight: 600 }}>Enter the one-time code</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>The run is holding its browser session open and waits for up to 5 minutes. The code is used once and never shown or stored in the clear.</Typography>
      {error ? <Box sx={{ mb: 1.5 }}><Failure error={error} /></Box> : null}
      {sent && <Alert severity="success" sx={{ mb: 1.5 }} role="status">Code sent. The run continues in the same browser session.</Alert>}
      <Stack direction="row" spacing={1} alignItems="flex-start">
        <TextField label="One-time code" type="password" autoComplete="one-time-code" inputMode="numeric" value={code} onChange={(e) => setCode(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 12, spellCheck: false } }} />
        <Button type="submit" variant="contained" disabled={busy || code.trim().length < 4} sx={{ mt: 0.25 }}>Continue sign-in</Button>
      </Stack>
    </Box>
  );
}

export function RunDetailPage() {
  const { id = '' } = useParams();
  return <NeedsProject>{(p) => <Detail project={p} id={id} />}</NeedsProject>;
}
