import { useState } from 'react';
import { Box, Button, Link, Stack, TextField, Typography } from '@mui/material';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, query, type Defect, type Page, type Trace } from '../api';
import { Async, Card, DataTable, dateTime, Empty, Failure, FormDialog, KeyValue, PageHeader, StatusChip, Tag, words } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { EvidenceViewer } from './EvidenceViewer';
import { Choice, levelOptions, NeedsProject, useForm } from './shared';

const statuses: [string, string][] = [['OPEN', 'Open'], ['TRIAGED', 'Triaged'], ['FIXED', 'Fixed'], ['WONT_FIX', "Won't fix"]];

function NewDefect({ project, onClose }: { project: string; onClose: () => void }) {
  const [v, set] = useState({ title: '', detail: '', expectedResult: '', severity: 'MEDIUM', module: '', url: '' });
  const f = useForm<void>(async () => { await api(`/projects/${project}/defects`, 'POST', v); onClose(); }, [['defects', project]]);
  return (
    <FormDialog open title="New defect" submit="Create defect" busy={f.busy} error={f.error} onClose={onClose} onSubmit={() => f.submit()}>
      <TextField label="Summary" required value={v.title} onChange={(e) => set({ ...v, title: e.target.value })} />
      <TextField label="Actual result" multiline minRows={2} value={v.detail} onChange={(e) => set({ ...v, detail: e.target.value })} />
      <TextField label="Expected result" multiline minRows={2} value={v.expectedResult} onChange={(e) => set({ ...v, expectedResult: e.target.value })} />
      <Stack direction="row" spacing={1.5}>
        <Choice label="Severity" value={v.severity} onChange={(x) => set({ ...v, severity: x })} options={levelOptions} />
        <TextField label="Module" value={v.module} onChange={(e) => set({ ...v, module: e.target.value })} />
      </Stack>
      <TextField label="Where it happens" value={v.url} onChange={(e) => set({ ...v, url: e.target.value })} />
    </FormDialog>
  );
}

function Body({ project }: { project: string }) {
  const { allowed } = useWorkspace();
  const navigate = useNavigate();
  const [f, set] = useState({ q: '', status: '', severity: '', priority: '' });
  const [sort, setSort] = useState<{ key: string; order: 'asc' | 'desc' }>({ key: 'number', order: 'desc' });
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const q = useQuery({ queryKey: ['defects', project, f, sort, page], placeholderData: keepPreviousData, queryFn: () => api<Page<Defect>>(`/projects/${project}/defects${query({ ...f, sort: sort.key, order: sort.order, page, size: 25 })}`) });
  const change = (k: keyof typeof f, v: string) => { set({ ...f, [k]: v }); setPage(0); };
  return (
    <>
      <PageHeader title="Defects" description="Found by runs or reported by people. A recurring problem is one defect, with its occurrences counted." actions={allowed('TESTER') && <Button variant="contained" onClick={() => setCreating(true)}>New defect</Button>} />
      <Card pad={false}>
        <Stack direction="row" spacing={1} sx={{ p: 1.5 }} flexWrap="wrap" useFlexGap>
          <TextField label="Search summary, URL or DEF-number" value={f.q} onChange={(e) => change('q', e.target.value)} sx={{ width: 270 }} />
          <Choice label="Status" width={130} value={f.status} onChange={(v) => change('status', v)} options={[['', 'All'], ...statuses]} />
          <Choice label="Severity" width={130} value={f.severity} onChange={(v) => change('severity', v)} options={[['', 'All'], ...levelOptions]} />
          <Choice label="Priority" width={130} value={f.priority} onChange={(v) => change('priority', v)} options={[['', 'All'], ...levelOptions]} />
        </Stack>
        <Async q={q}>{(p) => (p.items.length === 0 ? <Empty title="No defects match" /> : (
          <DataTable label="Defects" testId="defect-row" rows={p.items} rowKey={(r) => r.id} onRowClick={(r) => navigate(`/defects/${r.id}`)}
            sort={sort} onSort={(key) => setSort({ key, order: sort.key === key && sort.order === 'desc' ? 'asc' : 'desc' })} paging={{ page, size: 25, total: p.total, onPage: setPage }} columns={[
              { key: 'number', label: 'ID', nowrap: true, sortable: true, render: (r) => `DEF-${r.number}` },
              { key: 'title', label: 'Summary', sortable: true, render: (r) => <><Link component={RouterLink} to={`/defects/${r.id}`} onClick={(e) => e.stopPropagation()}>{r.title}</Link><Typography variant="caption" component="div" noWrap sx={{ maxWidth: 380 }}>{r.url}</Typography></> },
              { key: 'severity', label: 'Severity', sortable: true, render: (r) => <StatusChip value={r.severity} /> },
              { key: 'priority', label: 'Priority', sortable: true, render: (r) => <StatusChip value={r.priority} /> },
              { key: 'status', label: 'Status', sortable: true, render: (r) => <StatusChip value={r.status} /> },
              { key: 'application', label: 'Application', render: (r) => r.application ?? 'None' },
              { key: 'module', label: 'Module', render: (r) => r.module || 'None' },
              { key: 'occurrences', label: 'Seen', align: 'right', render: (r) => r.occurrences },
              { key: 'createdAt', label: 'Created', sortable: true, render: (r) => dateTime(r.createdAt) },
            ]} />))}</Async>
      </Card>
      {creating && <NewDefect project={project} onClose={() => setCreating(false)} />}
    </>
  );
}

function Detail({ project, id }: { project: string; id: string }) {
  const { allowed } = useWorkspace();
  const client = useQueryClient();
  const lead = allowed('QA_LEAD');
  const q = useQuery({ queryKey: ['defect', id], queryFn: () => api<Trace>(`/projects/${project}/defects/${id}`) });
  const update = useMutation({
    mutationFn: (patch: Partial<Defect>) => api(`/projects/${project}/defects/${id}`, 'PATCH', patch),
    onSuccess: () => Promise.all([client.invalidateQueries({ queryKey: ['defect', id] }), client.invalidateQueries({ queryKey: ['defects', project] })]),
  });
  return (
    <Async q={q}>{(t) => {
      const d = t.defect;
      const latest = t.findings[0];
      return (
        <>
          <PageHeader title={<>DEF-{d.number} {d.title}</>} actions={<StatusChip value={d.status} testId="defect-status" />} />
          {update.isError && <Box sx={{ mb: 2 }}><Failure error={update.error} /></Box>}
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
            <Stack spacing={2} sx={{ minWidth: 0 }}>
              <Card title="What happened">
                <KeyValue items={[['Actual', d.detail || 'Not recorded'], ['Expected', d.expectedResult || 'Not recorded'], ['Where', d.url || 'Not recorded']]} />
              </Card>
              {latest && (
                <Card title="Steps to reproduce" testId="repro-steps">
                  <Box component="ol" sx={{ m: 0, pl: 2.5 }}>
                    {latest.reproduction.map((s) => <Typography component="li" variant="body2" key={s.sequence} sx={{ overflowWrap: 'anywhere', mb: 0.5 }}>{words(s.kind)}: {s.label || s.url}</Typography>)}
                  </Box>
                </Card>
              )}
              {!latest && <Card title="Steps to reproduce"><Typography variant="body2" color="text.secondary">Reported by a person: the summary above is the record.</Typography></Card>}
              {latest?.evidence.map((e) => <Card key={e.id} title={words(e.type)}><EvidenceViewer project={project} item={e} /></Card>)}
            </Stack>
            <Stack spacing={2}>
              <Card title="Triage">
                <Stack spacing={1.5}>
                  {lead ? <>
                    <Choice label="Status" value={d.status} onChange={(status) => update.mutate({ status })} options={statuses} />
                    <Choice label="Priority" value={d.priority} onChange={(priority) => update.mutate({ priority })} options={levelOptions} />
                    <Choice label="Severity" value={d.severity} onChange={(severity) => update.mutate({ severity })} options={levelOptions} />
                  </> : <KeyValue items={[['Status', <StatusChip key="s" value={d.status} />], ['Priority', <StatusChip key="p" value={d.priority} />], ['Severity', <StatusChip key="v" value={d.severity} />]]} />}
                </Stack>
              </Card>
              <Card title="Details">
                <KeyValue items={[['Source', <Tag key="s">{d.source === 'MANUAL' ? 'Manual' : 'Automated'}</Tag>], ['Type', words(d.type)], ['Application', d.application ?? 'None'], ['Module', d.module || 'None'], ['Seen', `${d.occurrences} time(s)`], ['Created', dateTime(d.createdAt)]]} />
              </Card>
              <Card title="Related">
                <Stack spacing={0.75}>
                  {d.lastSeenRun && <Link component={RouterLink} to={`/execution/${d.lastSeenRun}`} variant="body2">Last run that found it</Link>}
                  {d.firstSeenRun && d.firstSeenRun !== d.lastSeenRun && <Link component={RouterLink} to={`/execution/${d.firstSeenRun}`} variant="body2">First run that found it</Link>}
                  <Typography variant="body2" data-testid="linked-cases">Linked test cases: {t.testCases.length}</Typography>
                  {t.testCases.map((c) => <Link key={c.id} component={RouterLink} to={`/cases/${c.id}`} variant="body2">{c.title}</Link>)}
                </Stack>
              </Card>
            </Stack>
          </Box>
        </>
      );
    }}</Async>
  );
}

export const DefectsPage = () => <NeedsProject>{(p) => <Body project={p} />}</NeedsProject>;
export function DefectDetailPage() {
  const { id = '' } = useParams();
  return <NeedsProject>{(p) => <Detail project={p} id={id} />}</NeedsProject>;
}
