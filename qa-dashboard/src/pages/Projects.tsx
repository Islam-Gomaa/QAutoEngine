import { useState } from 'react';
import { Box, Button, Tab, Tabs, TextField, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { api, query, type Activity, type Member, type Overview, type Page, type Project, type Run, type User } from '../api';
import { ago, Async, Card, Confirm, DataTable, dateTime, Empty, FormDialog, KeyValue, PageHeader, percent, Stat, StatusChip, Tag, words } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { Choice, ROLES, useForm } from './shared';

function ProjectForm({ form, initial, title, submit }: { form: ReturnType<typeof useForm<{ name: string; description: string }>>; initial?: Project; title: string; submit: string }) {
  const [name, setName] = useState(initial?.name ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  return (
    <FormDialog open={form.open} title={title} submit={submit} busy={form.busy} error={form.error} onClose={form.close} onSubmit={() => form.submit({ name, description })}>
      <TextField label="Project name" required value={name} onChange={(e) => setName(e.target.value)} autoFocus />
      <TextField label="Description" multiline minRows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
    </FormDialog>
  );
}

export function ProjectsPage() {
  const { me, selectProject } = useWorkspace();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [tab, setTab] = useState(0);
  const q = useQuery({ queryKey: ['projects', tab], queryFn: () => api<Project[]>(`/projects${query({ archived: tab === 1 })}`) });
  const create = useForm<{ name: string; description: string }>(async (v) => { const p = await api<Project>('/projects', 'POST', v); selectProject(p.id); navigate(`/projects/${p.id}`); }, [['projects']]);
  return (
    <>
      <PageHeader title="Projects" description="A project owns its applications, tests, runs, defects and evidence. Nothing is shared between projects."
        actions={me.organizationAdmin && <Button variant="contained" onClick={create.show}>New project</Button>} />
      <Card pad={false}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ px: 1 }}><Tab label="Active" /><Tab label="Archived" /></Tabs>
        <Box sx={{ p: 0 }}>
          <Async q={q}>{(rows) => (rows.length === 0
            ? <Empty title={tab === 0 ? 'No projects yet' : 'No archived projects'} action={tab === 0 && me.organizationAdmin ? <Button variant="contained" onClick={create.show}>New project</Button> : undefined} />
            : <DataTable label="Projects" testId="project-row" rows={rows} rowKey={(r) => r.id} onRowClick={(r) => { if (!r.archivedAt) selectProject(r.id); navigate(`/projects/${r.id}`); }} columns={[
                { key: 'name', label: 'Name', render: (r) => <><Typography variant="body1" sx={{ fontWeight: 560 }}>{r.name}</Typography><Typography variant="caption" noWrap component="div" sx={{ maxWidth: 420 }}>{r.description}</Typography></> },
                { key: 'role', label: 'Your role', render: (r) => (r.myRole ? words(r.myRole) : 'None') },
                { key: 'cases', label: 'Test cases', align: 'right', render: (r) => r.testCases },
                { key: 'runs', label: 'Executions', align: 'right', render: (r) => r.runs },
                { key: 'defects', label: 'Open defects', align: 'right', render: (r) => r.openDefects },
                ...(tab === 1 && me.organizationAdmin ? [{ key: 'restore', label: '', render: (r: Project) => <Confirm label="Restore" title="Restore project" body={`Make ${r.name} active again?`} onConfirm={async () => { await api(`/projects/${r.id}/restore`, 'POST'); await client.invalidateQueries({ queryKey: ['projects'] }); }} /> }] : []),
              ]} />)}</Async>
        </Box>
      </Card>
      {create.open && <ProjectForm form={create} title="New project" submit="Create project" />}
    </>
  );
}

function Members({ project, canManage }: { project: Project; canManage: boolean }) {
  const { me } = useWorkspace();
  const client = useQueryClient();
  const members = useQuery({ queryKey: ['members', project.id], queryFn: () => api<Member[]>(`/projects/${project.id}/members`) });
  const users = useQuery({ queryKey: ['users'], queryFn: () => api<User[]>('/users'), enabled: me.organizationAdmin });
  const [userId, setUserId] = useState('');
  const [role, setRole] = useState('TESTER');
  const add = useForm<void>(() => api(`/projects/${project.id}/members`, 'PUT', { userId, role }), [['members', project.id]]);
  return (
    <Card title="Members" pad={false} action={canManage && me.organizationAdmin ? <Button onClick={add.show}>Add member</Button> : undefined}>
      <Async q={members}>{(rows) => (rows.length === 0 ? <Empty title="No members">Organization admins can access every project.</Empty> : (
        <DataTable label="Members" rows={rows} rowKey={(r) => r.userId} columns={[
          { key: 'email', label: 'User', render: (r) => r.email },
          { key: 'role', label: 'Role', render: (r) => (canManage ? <Choice label="Role" value={r.role} options={ROLES} width={170} onChange={async (v) => { await api(`/projects/${project.id}/members`, 'PUT', { userId: r.userId, role: v }); await client.invalidateQueries({ queryKey: ['members', project.id] }); }} /> : words(r.role)) },
          ...(canManage ? [{ key: 'x', label: '', align: 'right' as const, render: (r: Member) => <Confirm label="Remove" color="error" title="Remove member" body={`Remove ${r.email} from this project?`} onConfirm={async () => { await api(`/projects/${project.id}/members/${r.userId}`, 'DELETE'); await client.invalidateQueries({ queryKey: ['members', project.id] }); }} /> }] : []),
        ]} />))}</Async>
      <FormDialog open={add.open} title="Add member" submit="Add member" busy={add.busy} error={add.error} onClose={add.close} onSubmit={() => add.submit()}>
        <Choice label="User" required value={userId} onChange={setUserId} options={(users.data ?? []).filter((u) => !u.disabled).map((u) => [u.id, u.email] as [string, string])} />
        <Choice label="Role" value={role} onChange={setRole} options={ROLES} />
      </FormDialog>
    </Card>
  );
}

export function ProjectDetailPage() {
  const { id = '' } = useParams();
  const { me } = useWorkspace();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [tab, setTab] = useState('overview');
  const q = useQuery({ queryKey: ['project', id], queryFn: () => api<Project>(`/projects/${id}`) });
  const overview = useQuery({ queryKey: ['project-report', id], queryFn: () => api<Overview>(`/reports/overview${query({ projectId: id })}`), enabled: q.isSuccess });
  const activity = useQuery({ queryKey: ['activity', id], queryFn: () => api<Activity[]>(`/activity${query({ projectId: id })}`), enabled: tab === 'activity' });
  const runs = useQuery({ queryKey: ['project-runs', id], queryFn: () => api<Page<Run>>(`/projects/${id}/runs${query({ size: 25 })}`), enabled: tab === 'executions' });
  const edit = useForm<{ name: string; description: string }>((v) => api(`/projects/${id}`, 'PATCH', v), [['project', id], ['projects']]);
  return (
    <Async q={q}>{(p) => {
      const manage = !p.archivedAt && (p.myRole === 'PROJECT_ADMIN');
      const c = overview.data?.coverage[0];
      return (
        <>
          <PageHeader title={p.name} description={p.description || undefined} actions={<>
            {p.archivedAt && <StatusChip value="DEPRECATED" />}
            {manage && <Button variant="outlined" onClick={edit.show}>Edit</Button>}
            {manage && me.organizationAdmin && <Confirm label="Archive" color="error" title="Archive project" body="Runs, defects and evidence are kept, and the project becomes read-only. You can restore it later." onConfirm={async () => { await api(`/projects/${id}/archive`, 'POST'); await client.invalidateQueries({ queryKey: ['projects'] }); navigate('/projects'); }} />}
          </>} />
          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
            <Tab value="overview" label="Overview" /><Tab value="members" label="Members" /><Tab value="coverage" label="Coverage" /><Tab value="executions" label="Executions" /><Tab value="activity" label="Activity" />
          </Tabs>
          {tab === 'overview' && (
            <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2,1fr)', md: 'repeat(4,1fr)' } }}>
              <Stat label="Test cases" value={p.testCases} /><Stat label="Executions" value={p.runs} /><Stat label="Open defects" value={p.openDefects} />
              <Stat label="Pass rate, 30 days" value={percent(overview.data?.execution.passRate)} />
            </Box>
          )}
          {tab === 'members' && <Members project={p} canManage={manage} />}
          {tab === 'coverage' && (
            <Card title="Test coverage">
              {!c || c.total === 0 ? <Empty title="No test cases yet" /> : <KeyValue items={[['Total cases', c.total], ['Automated', c.automated], ['Manual', c.manual], ['In a suite', c.inSuite], ['Executed at least once', c.executed], ['Ready / Draft / Deprecated', `${c.ready} / ${c.draft} / ${c.deprecated}`]]} />}
            </Card>
          )}
          {tab === 'executions' && (
            <Card pad={false}><Async q={runs}>{(page) => (page.items.length === 0 ? <Empty title="No executions yet" /> : (
              <DataTable label="Executions" rows={page.items} rowKey={(r) => r.id} onRowClick={(r) => navigate(`/execution/${r.id}`)} columns={[
                { key: 'app', label: 'Application', render: (r) => `${r.applicationName} / ${r.environmentName}` }, { key: 'type', label: 'Type', render: (r) => <Tag>{r.channel}</Tag> },
                { key: 'status', label: 'Status', render: (r) => <StatusChip value={r.status} /> }, { key: 'at', label: 'Started', render: (r) => dateTime(r.createdAt) },
              ]} />))}</Async></Card>
          )}
          {tab === 'activity' && (
            <Card pad={false}><Async q={activity}>{(rows) => (rows.length === 0 ? <Empty title="No activity yet" /> : (
              <DataTable label="Activity" rows={rows.map((r, i) => ({ ...r, i }))} rowKey={(r) => String(r.i)} columns={[
                { key: 'action', label: 'Action', render: (r) => words(r.action) }, { key: 'actor', label: 'By', render: (r) => r.actor }, { key: 'at', label: 'When', render: (r) => ago(r.at) },
              ]} />))}</Async></Card>
          )}
          {edit.open && <ProjectForm form={edit} initial={p} title="Edit project" submit="Save changes" />}
        </>
      );
    }}</Async>
  );
}

