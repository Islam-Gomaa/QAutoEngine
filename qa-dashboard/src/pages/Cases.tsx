import { useState } from 'react';
import { Alert, Box, Button, IconButton, Link, MenuItem, Stack, Tab, Tabs, TextField, Typography } from '@mui/material';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import Delete from '@mui/icons-material/DeleteOutlined';
import { api, query, type CaseDetail, type Defect, type Page, type Suite, type TestCase } from '../api';
import { Async, Card, Confirm, DataTable, dateTime, Empty, FormDialog, KeyValue, Mono, PageHeader, StatusChip, Tag } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { Choice, levelOptions, NeedsProject, useForm } from './shared';

interface ManualStep { action: string; expected: string }
const statuses: [string, string][] = [['DRAFT', 'Draft'], ['READY', 'Ready'], ['DEPRECATED', 'Deprecated']];

function StepsEditor({ steps, onChange }: { steps: ManualStep[]; onChange: (s: ManualStep[]) => void }) {
  return (
    <Stack spacing={1}>
      <Typography variant="h3">Steps</Typography>
      {steps.map((s, i) => (
        <Stack key={i} direction="row" spacing={1} alignItems="center">
          <Typography variant="body2" color="text.secondary" sx={{ width: 18 }}>{i + 1}</Typography>
          <TextField label={`Step ${i + 1} action`} required value={s.action} onChange={(e) => onChange(steps.map((x, j) => (j === i ? { ...x, action: e.target.value } : x)))} />
          <TextField label={`Step ${i + 1} expected result`} value={s.expected} onChange={(e) => onChange(steps.map((x, j) => (j === i ? { ...x, expected: e.target.value } : x)))} />
          <IconButton aria-label={`Remove step ${i + 1}`} onClick={() => onChange(steps.filter((_, j) => j !== i))}><Delete fontSize="small" /></IconButton>
        </Stack>
      ))}
      <Box><Button onClick={() => onChange([...steps, { action: '', expected: '' }])}>Add step</Button></Box>
    </Stack>
  );
}

function CaseForm({ project, suites, current, onClose }: { project: string; suites: Suite[]; current?: TestCase; onClose: () => void }) {
  const [v, set] = useState({ title: current?.title ?? '', description: current?.description ?? '', suiteId: current?.suiteId ?? '', priority: current?.priority ?? 'MEDIUM', status: current?.status ?? 'DRAFT', module: current?.module ?? '', tags: current?.tags.join(', ') ?? '' });
  const [steps, setSteps] = useState<ManualStep[]>([{ action: '', expected: '' }]);
  const f = useForm<void>(async () => {
    const body = { ...v, suiteId: v.suiteId || null, tags: v.tags.split(',').map((t) => t.trim()).filter(Boolean) };
    await (current ? api(`/projects/${project}/test-cases/${current.id}`, 'PATCH', body) : api(`/projects/${project}/test-cases`, 'POST', { ...body, steps }));
    onClose();
  }, [['cases', project], ['case'], ['suites', project]]);
  return (
    <FormDialog open width="md" title={current ? 'Edit test case' : 'New manual test case'} submit={current ? 'Save changes' : 'Create test case'} busy={f.busy} error={f.error} onClose={onClose} onSubmit={() => f.submit()}>
      <TextField label="Title" required value={v.title} onChange={(e) => set({ ...v, title: e.target.value })} />
      <TextField label="Description" multiline minRows={2} value={v.description} onChange={(e) => set({ ...v, description: e.target.value })} />
      <Stack direction="row" spacing={1.5}>
        <Choice label="Suite" value={v.suiteId} onChange={(x) => set({ ...v, suiteId: x })} options={[['', 'None'], ...suites.map((s) => [s.id, s.name] as [string, string])]} />
        <Choice label="Priority" value={v.priority} onChange={(x) => set({ ...v, priority: x })} options={levelOptions} />
        <Choice label="Status" value={v.status} onChange={(x) => set({ ...v, status: x })} options={statuses} />
      </Stack>
      <Stack direction="row" spacing={1.5}>
        <TextField label="Module" value={v.module} onChange={(e) => set({ ...v, module: e.target.value })} />
        <TextField label="Tags, comma separated" value={v.tags} onChange={(e) => set({ ...v, tags: e.target.value })} />
      </Stack>
      {!current && <StepsEditor steps={steps} onChange={setSteps} />}
      {!current && <Alert severity="info">Manual cases are performed by a person, who records the result here. Only automated cases discovered by runs can be executed.</Alert>}
    </FormDialog>
  );
}

function Body({ project }: { project: string }) {
  const { allowed } = useWorkspace();
  const navigate = useNavigate();
  const tester = allowed('TESTER');
  const [f, set] = useState({ q: '', suiteId: '', source: '', priority: '', status: '' });
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const suites = useQuery({ queryKey: ['suites', project, 0], queryFn: () => api<Suite[]>(`/projects/${project}/suites`) });
  const q = useQuery({ queryKey: ['cases', project, f, page], placeholderData: keepPreviousData, queryFn: () => api<Page<TestCase>>(`/projects/${project}/test-cases${query({ ...f, page, size: 25 })}`) });
  const change = (k: keyof typeof f, v: string) => { set({ ...f, [k]: v }); setPage(0); };
  return (
    <>
      <PageHeader title="Test Cases" description="Automated cases are recorded by runs and can be repeated through a suite. Manual cases hold steps a person performs."
        actions={tester && <Button variant="contained" onClick={() => setCreating(true)}>New test case</Button>} />
      <Card pad={false}>
        <Stack direction="row" spacing={1} sx={{ p: 1.5 }} flexWrap="wrap" useFlexGap>
          <TextField label="Search title, module or tag" value={f.q} onChange={(e) => change('q', e.target.value)} sx={{ width: 240 }} />
          <TextField select label="Suite" value={f.suiteId} onChange={(e) => change('suiteId', e.target.value)} sx={{ width: 170 }}><MenuItem value="">All</MenuItem>{suites.data?.map((s) => <MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>)}</TextField>
          <Choice label="Source" width={130} value={f.source} onChange={(v) => change('source', v)} options={[['', 'All'], ['AUTOMATED', 'Automated'], ['MANUAL', 'Manual']]} />
          <Choice label="Priority" width={130} value={f.priority} onChange={(v) => change('priority', v)} options={[['', 'All'], ...levelOptions]} />
          <Choice label="Status" width={130} value={f.status} onChange={(v) => change('status', v)} options={[['', 'All'], ...statuses]} />
        </Stack>
        <Async q={q}>{(p) => (p.items.length === 0 ? <Empty title="No test cases match" >Runs add automated cases. You can also write a manual one.</Empty> : (
          <DataTable label="Test cases" testId="case-row" rows={p.items} rowKey={(r) => r.id} onRowClick={(r) => navigate(`/cases/${r.id}`)} paging={{ page, size: 25, total: p.total, onPage: setPage }} columns={[
            { key: 'title', label: 'Title', render: (r) => <><Typography variant="body1" noWrap sx={{ maxWidth: 420 }}>{r.title}</Typography>{r.tags.length > 0 && <Typography variant="caption">{r.tags.join(', ')}</Typography>}</> },
            { key: 'source', label: 'Source', render: (r) => <Tag>{r.source === 'MANUAL' ? 'Manual' : 'Automated'}</Tag> },
            { key: 'suite', label: 'Suite', render: (r) => r.suiteName ?? 'None' },
            { key: 'priority', label: 'Priority', render: (r) => <StatusChip value={r.priority} /> },
            { key: 'status', label: 'Status', render: (r) => <StatusChip value={r.status} /> },
            { key: 'version', label: 'Version', align: 'right', render: (r) => r.latestVersion },
            { key: 'last', label: 'Last result', render: (r) => <StatusChip value={r.lastVerdict} /> },
          ]} />))}</Async>
      </Card>
      {creating && <CaseForm project={project} suites={suites.data ?? []} onClose={() => setCreating(false)} />}
    </>
  );
}

function parseSteps(text: string): { action: string; expected: string }[] {
  const raw = JSON.parse(text) as Record<string, string>[];
  return raw.map((s) => ({ action: s.action ?? s.label ?? `${s.kind ?? 'Step'} ${s.url ?? ''}`, expected: s.expected ?? '' }));
}

function Detail({ project, id }: { project: string; id: string }) {
  const { allowed } = useWorkspace();
  const client = useQueryClient();
  const navigate = useNavigate();
  const tester = allowed('TESTER');
  const [tab, setTab] = useState('steps');
  const [editing, setEditing] = useState(false);
  const q = useQuery({ queryKey: ['case', id], queryFn: () => api<CaseDetail>(`/projects/${project}/test-cases/${id}`) });
  const suites = useQuery({ queryKey: ['suites', project, 0], queryFn: () => api<Suite[]>(`/projects/${project}/suites`) });
  const defects = useQuery({ queryKey: ['defects-all', project], queryFn: () => api<Page<Defect>>(`/projects/${project}/defects${query({ size: 200 })}`), enabled: tab === 'defects' && tester });
  const [result, setResult] = useState({ verdict: 'PASS', notes: '' });
  const record = useForm<void>(() => api(`/projects/${project}/test-cases/${id}/results`, 'POST', result), [['case', id], ['cases', project]]);
  const [steps, setSteps] = useState<ManualStep[]>([]);
  const stepsForm = useForm<void>(() => api(`/projects/${project}/test-cases/${id}/steps`, 'PUT', steps), [['case', id]]);
  const [defectId, setDefectId] = useState('');
  const link = useForm<void>(() => api(`/projects/${project}/test-cases/${id}/defects`, 'POST', { defectId }), [['case', id]]);
  const refresh = () => client.invalidateQueries({ queryKey: ['case', id] });
  return (
    <Async q={q}>{(d) => {
      const c = d.testCase;
      const manual = c.source === 'MANUAL';
      const shown = parseSteps(d.steps);
      return (
        <>
          <PageHeader title={c.title} description={c.description || undefined} actions={tester && <>
            {manual && <Button variant="contained" onClick={record.show}>Record result</Button>}
            <Button variant="outlined" onClick={() => setEditing(true)}>Edit</Button>
            {c.archivedAt
              ? <Confirm label="Restore" title="Restore test case" body="Restore this test case?" onConfirm={async () => { await api(`/projects/${project}/test-cases/${id}/restore`, 'POST'); await refresh(); }} />
              : <Confirm label="Archive" color="error" title="Archive test case" body="Archive this test case? Its history is kept." onConfirm={async () => { await api(`/projects/${project}/test-cases/${id}/archive`, 'POST'); await client.invalidateQueries({ queryKey: ['cases', project] }); navigate('/cases'); }} />}
          </>} />
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '3fr 1fr' } }}>
            <Box sx={{ minWidth: 0 }}>
              <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}><Tab value="steps" label="Steps" /><Tab value="history" label="Execution history" /><Tab value="defects" label={`Defects (${d.defects.length})`} /><Tab value="versions" label="Versions" /></Tabs>
              {tab === 'steps' && (
                <Card pad={false} title="Steps" action={manual && tester ? <Button onClick={() => { setSteps(shown.length ? shown : [{ action: '', expected: '' }]); stepsForm.show(); }}>Edit steps</Button> : undefined}>
                  {shown.length === 0 ? <Empty title="No steps yet" /> : <DataTable label="Steps" rows={shown.map((s, i) => ({ ...s, i }))} rowKey={(s) => String(s.i)} columns={[
                    { key: 'i', label: '#', width: 40, render: (s) => s.i + 1 }, { key: 'a', label: 'Action', render: (s) => s.action }, { key: 'e', label: 'Expected result', render: (s) => s.expected || (manual ? 'Not stated' : 'Verdict rules apply') },
                  ]} />}
                </Card>
              )}
              {tab === 'history' && <Card pad={false}>{d.history.length === 0 ? <Empty title="Not executed yet" /> : (
                <DataTable label="History" rows={d.history.map((h, i) => ({ ...h, i }))} rowKey={(h) => String(h.i)} columns={[
                  { key: 'v', label: 'Result', render: (h) => <StatusChip value={h.verdict} /> }, { key: 'k', label: 'Kind', render: (h) => (h.kind === 'MANUAL' ? 'Manual' : 'Automated') },
                  { key: 'at', label: 'When', render: (h) => dateTime(h.at) }, { key: 'by', label: 'Recorded by', render: (h) => (h.runId ? <Link component={RouterLink} to={`/execution/${h.runId}`}>Open run</Link> : h.by) }, { key: 'n', label: 'Notes', render: (h) => h.notes },
                ]} />)}</Card>}
              {tab === 'defects' && (
                <Card pad={false} action={tester ? <Button onClick={link.show}>Link defect</Button> : undefined} title="Linked defects">
                  {d.defects.length === 0 ? <Empty title="No linked defects" /> : <DataTable label="Linked defects" rows={d.defects} rowKey={(x) => x.id} columns={[
                    { key: 'n', label: 'ID', render: (x) => <Link component={RouterLink} to={`/defects/${x.id}`}>DEF-{x.number}</Link> }, { key: 't', label: 'Summary', render: (x) => x.title },
                    { key: 's', label: 'Severity', render: (x) => <StatusChip value={x.severity} /> }, { key: 'st', label: 'Status', render: (x) => <StatusChip value={x.status} /> },
                    ...(tester ? [{ key: 'u', label: '', align: 'right' as const, render: (x: { id: string }) => <Confirm label="Unlink" title="Unlink defect" body="Remove this link?" onConfirm={async () => { await api(`/projects/${project}/test-cases/${id}/defects/${x.id}`, 'DELETE'); await refresh(); }} /> }] : []),
                  ]} />}
                </Card>
              )}
              {tab === 'versions' && <Card pad={false}><DataTable label="Versions" rows={d.versions} rowKey={(v) => String(v.version)} columns={[
                { key: 'v', label: 'Version', render: (v) => v.version }, { key: 'h', label: 'Content hash', render: (v) => <Mono>{v.contentHash.slice(0, 16)}</Mono> }, { key: 'n', label: 'Steps', align: 'right', render: (v) => (JSON.parse(v.steps) as unknown[]).length },
              ]} /></Card>}
            </Box>
            <Card title="Details"><KeyValue items={[['Source', manual ? 'Manual' : 'Automated'], ['Status', <StatusChip key="s" value={c.status} />], ['Priority', <StatusChip key="p" value={c.priority} />], ['Suite', c.suiteName ?? 'None'], ['Module', c.module || 'None'], ['Tags', c.tags.join(', ') || 'None'], ['Version', c.latestVersion], ['Last result', <StatusChip key="l" value={c.lastVerdict} />]]} /></Card>
          </Box>
          {editing && <CaseForm project={project} suites={suites.data ?? []} current={c} onClose={() => setEditing(false)} />}
          <FormDialog open={record.open} title="Record manual result" submit="Record result" busy={record.busy} error={record.error} onClose={record.close} onSubmit={() => record.submit()}>
            <Choice label="Result" value={result.verdict} onChange={(v) => setResult({ ...result, verdict: v })} options={[['PASS', 'Passed'], ['FAIL', 'Failed'], ['BLOCKED', 'Blocked']]} />
            <TextField label="Notes" multiline minRows={2} value={result.notes} onChange={(e) => setResult({ ...result, notes: e.target.value })} />
          </FormDialog>
          <FormDialog open={stepsForm.open} width="md" title="Edit steps" submit="Save as new version" busy={stepsForm.busy} error={stepsForm.error} onClose={stepsForm.close} onSubmit={() => stepsForm.submit()}>
            <StepsEditor steps={steps} onChange={setSteps} />
          </FormDialog>
          <FormDialog open={link.open} title="Link defect" submit="Link defect" busy={link.busy} error={link.error} onClose={link.close} onSubmit={() => link.submit()}>
            <Choice label="Defect" required value={defectId} onChange={setDefectId} options={(defects.data?.items ?? []).map((x) => [x.id, `DEF-${x.number} ${x.title}`] as [string, string])} />
          </FormDialog>
        </>
      );
    }}</Async>
  );
}

export const CasesPage = () => <NeedsProject>{(p) => <Body project={p} />}</NeedsProject>;
export function CaseDetailPage() {
  const { id = '' } = useParams();
  return <NeedsProject>{(p) => <Detail project={p} id={id} />}</NeedsProject>;
}
