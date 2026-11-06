import { useState } from 'react';
import { Alert, Button, Stack, Tab, Tabs, TextField, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { api, query, type Page, type Suite, type TestCase } from '../api';
import { Async, Card, Confirm, DataTable, Empty, FormDialog, PageHeader, StatusChip, Tag } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { Choice, NeedsProject, useForm } from './shared';

function SuiteForm({ project, suite, onClose }: { project: string; suite?: Suite; onClose: () => void }) {
  const [v, set] = useState({ name: suite?.name ?? '', description: suite?.description ?? '', module: suite?.module ?? '', channel: suite?.channel ?? 'WEB' });
  const f = useForm<void>(async () => {
    await (suite ? api(`/projects/${project}/suites/${suite.id}`, 'PATCH', v) : api(`/projects/${project}/suites`, 'POST', v));
    onClose();
  }, [['suites', project]]);
  return (
    <FormDialog open title={suite ? 'Edit suite' : 'New suite'} submit={suite ? 'Save changes' : 'Create suite'} busy={f.busy} error={f.error} onClose={onClose} onSubmit={() => f.submit()}>
      <TextField label="Suite name" required value={v.name} onChange={(e) => set({ ...v, name: e.target.value })} />
      <Choice label="Suite type" value={v.channel} onChange={(x) => set({ ...v, channel: x as 'WEB' | 'API' })} options={[['WEB', 'Web'], ['API', 'API']]} />
      <TextField label="Module" value={v.module} onChange={(e) => set({ ...v, module: e.target.value })} helperText="Suites with the same module are grouped together." />
      <TextField label="Description" multiline minRows={2} value={v.description} onChange={(e) => set({ ...v, description: e.target.value })} />
    </FormDialog>
  );
}

function Body({ project }: { project: string }) {
  const { allowed } = useWorkspace();
  const lead = allowed('QA_LEAD');
  const client = useQueryClient();
  const navigate = useNavigate();
  const [tab, setTab] = useState(0);
  const [editing, setEditing] = useState<Suite | 'new' | null>(null);
  const q = useQuery({ queryKey: ['suites', project, tab], queryFn: () => api<Suite[]>(`/projects/${project}/suites${query({ archived: tab === 1 })}`) });
  const refresh = () => client.invalidateQueries({ queryKey: ['suites', project] });
  return (
    <>
      <PageHeader title="Test Suites" description="Group test cases by module. A suite runs only its automated cases; manual cases are recorded by a person."
        actions={lead && <Button variant="contained" onClick={() => setEditing('new')}>New suite</Button>} />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}><Tab label="Active" /><Tab label="Archived" /></Tabs>
      <Async q={q}>{(rows) => {
        if (rows.length === 0) return <Card><Empty title={tab ? 'No archived suites' : 'No suites yet'} action={lead && !tab ? <Button variant="contained" onClick={() => setEditing('new')}>New suite</Button> : undefined} /></Card>;
        const modules = [...new Set(rows.map((r) => r.module))];
        return (
          <Stack spacing={2}>
            {modules.map((m) => (
              <Card key={m} pad={false} title={m || 'No module'}>
                <DataTable label={`Suites in ${m || 'no module'}`} testId="suite-row" rows={rows.filter((r) => r.module === m)} rowKey={(r) => r.id} onRowClick={(r) => navigate(`/suites/${r.id}`)} columns={[
                  { key: 'name', label: 'Suite', render: (r) => <><Typography variant="body1" sx={{ fontWeight: 560 }}>{r.name}</Typography><Typography variant="caption">{r.description}</Typography></> },
                  { key: 'type', label: 'Type', render: (r) => <Tag>{r.channel === 'WEB' ? 'Web' : 'API'}</Tag> },
                  { key: 'cases', label: 'Cases', align: 'right', render: (r) => r.cases },
                  { key: 'auto', label: 'Automated', align: 'right', render: (r) => r.automatedCases },
                  ...(lead ? [{ key: 'x', label: '', align: 'right' as const, render: (r: Suite) => (tab
                    ? <Confirm label="Restore" title="Restore suite" body={`Restore ${r.name}?`} onConfirm={async () => { await api(`/projects/${project}/suites/${r.id}/restore`, 'POST'); await refresh(); }} />
                    : <Stack direction="row" justifyContent="flex-end" onClick={(e) => e.stopPropagation()}>
                        <Button onClick={() => setEditing(r)}>Edit</Button>
                        <Confirm label="Archive" color="error" title="Archive suite" body={`Archive ${r.name}? Its cases stay in the project.`} onConfirm={async () => { await api(`/projects/${project}/suites/${r.id}/archive`, 'POST'); await refresh(); }} />
                      </Stack>) }] : []),
                ]} />
              </Card>
            ))}
          </Stack>
        );
      }}</Async>
      {editing && <SuiteForm project={project} suite={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function Detail({ project, id }: { project: string; id: string }) {
  const navigate = useNavigate();
  const suites = useQuery({ queryKey: ['suites', project, 0], queryFn: () => api<Suite[]>(`/projects/${project}/suites`) });
  const cases = useQuery({ queryKey: ['cases', project, 'suite', id], queryFn: () => api<Page<TestCase>>(`/projects/${project}/test-cases${query({ suiteId: id, size: 200 })}`) });
  const suite = suites.data?.find((s) => s.id === id);
  return (
    <>
      <PageHeader title={suite?.name ?? 'Suite'} description={suite?.description} actions={suite && suite.automatedCases > 0 && <Button variant="contained" onClick={() => navigate(`/execution?suite=${id}`)}>Run this suite</Button>} />
      {suite && suite.automatedCases === 0 && <Alert severity="info" sx={{ mb: 2 }}>This suite has no automated cases, so it cannot be executed. Add automated cases from Test Cases, or record manual results there.</Alert>}
      <Card pad={false} title="Test cases"><Async q={cases}>{(page) => (page.items.length === 0 ? <Empty title="No test cases in this suite">Assign cases to this suite from Test Cases.</Empty> : (
        <DataTable label="Suite cases" rows={page.items} rowKey={(r) => r.id} onRowClick={(r) => navigate(`/cases/${r.id}`)} columns={[
          { key: 'title', label: 'Title', render: (r) => r.title }, { key: 'source', label: 'Source', render: (r) => <Tag>{r.source === 'MANUAL' ? 'Manual' : 'Automated'}</Tag> },
          { key: 'priority', label: 'Priority', render: (r) => <StatusChip value={r.priority} /> }, { key: 'last', label: 'Last result', render: (r) => <StatusChip value={r.lastVerdict} /> },
        ]} />))}</Async></Card>
    </>
  );
}

export const SuitesPage = () => <NeedsProject>{(p) => <Body project={p} />}</NeedsProject>;
export function SuiteDetailPage() {
  const { id = '' } = useParams();
  return <NeedsProject>{(p) => <Detail project={p} id={id} />}</NeedsProject>;
}
