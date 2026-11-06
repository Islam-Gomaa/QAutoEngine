import { Box, Link, Stack, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { api, query, type Dashboard } from '../api';
import { ChartCard, SegmentBar, StackedDays } from '../design/charts';
import { ago, Async, Card, DataTable, Empty, PageHeader, percent, Stat, StatusChip, Tag, words } from '../design/ui';
import { useWorkspace } from '../shell/context';

export function DashboardPage() {
  const { tokens } = useTheme();
  const { selectProject } = useWorkspace();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['dashboard'], queryFn: () => api<Dashboard>(`/dashboard${query({ days: 14 })}`), refetchInterval: 15000 });
  const s = tokens.status;
  const verdicts = [
    { key: 'passed', label: 'Passed', color: s.good.mark }, { key: 'failed', label: 'Failed', color: s.critical.mark },
    { key: 'blocked', label: 'Blocked', color: s.warning.mark }, { key: 'inconclusive', label: 'Inconclusive', color: s.neutral.mark },
  ];
  const open = (projectId: string, to: string) => { selectProject(projectId); navigate(to); };
  return (
    <>
      <PageHeader title="Dashboard" description="Quality across the projects you can access. Counts are test steps from each run's final attempt." />
      <Async q={q} rows={8}>{(d) => (
        <Stack spacing={2}>
          <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)', xl: 'repeat(8, 1fr)' } }}>
            <Stat label="Projects" value={d.stats.projects} />
            <Stat label="Applications" value={d.stats.applications} />
            <Stat label="Test cases" value={d.stats.testCases} />
            <Stat label="Executions" value={d.stats.runs} />
            <Stat label="Pass rate" value={percent(d.stats.passRate)} hint="Passed / (passed + failed)" />
            <Stat label="Failed tests" value={d.stats.failed} tone={d.stats.failed ? 'critical' : undefined} />
            <Stat label="Blocked tests" value={d.stats.blocked} hint="Refused by the safety gate" />
            <Stat label="Open defects" value={d.stats.openDefects} tone={d.stats.openDefects ? 'serious' : undefined} />
          </Box>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
            <ChartCard title="Execution trend, last 14 days" columns={['Day', 'Runs', 'Passed', 'Failed', 'Blocked', 'Inconclusive']} rows={d.trend.map((t) => [t.day, t.runs, t.passed, t.failed, t.blocked, t.inconclusive])}>
              <StackedDays rows={d.trend as never} series={verdicts} label="Test results per day" />
            </ChartCard>
            <Stack spacing={2}>
              <Card title="Result distribution">
                <SegmentBar parts={verdicts.map((v) => ({ label: v.label, color: v.color, value: d.distribution[v.key as keyof typeof d.distribution] }))} />
              </Card>
              <Card title="Web and API">
                {d.channels.every((c) => c.runs === 0) ? <Typography variant="body2" color="text.secondary">No runs yet.</Typography> : (
                  <Stack spacing={1.5}>
                    {d.channels.filter((c) => c.runs > 0).map((c) => (
                      <Box key={c.channel}>
                        <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
                          <Typography variant="body2" sx={{ fontWeight: 600 }}>{c.channel === 'WEB' ? 'Web' : 'API'}</Typography>
                          <Typography variant="body2" color="text.secondary">{c.runs} runs, {percent(c.passRate)}</Typography>
                        </Stack>
                        <SegmentBar height={8} legend={false} parts={[{ label: 'Passed', value: c.passed, color: s.good.mark }, { label: 'Failed', value: c.failed, color: s.critical.mark }, { label: 'Blocked', value: c.blocked, color: s.warning.mark }]} />
                      </Box>
                    ))}
                  </Stack>
                )}
              </Card>
            </Stack>
          </Box>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
            <Card title="Recent executions" pad={false} testId="recent-runs" action={<Link component={RouterLink} to="/execution" variant="body2">View all</Link>}>
              {d.recentRuns.length === 0 ? <Empty title="No executions yet">Start a fixture run from Test Execution.</Empty> : (
                <DataTable label="Recent executions" rows={d.recentRuns} rowKey={(r) => r.id} onRowClick={(r) => open(r.projectId, `/execution/${r.id}`)} columns={[
                  { key: 'app', label: 'Application', render: (r) => <><Typography variant="body1" noWrap>{r.applicationName}</Typography><Typography variant="caption">{r.projectName}</Typography></> },
                  { key: 'channel', label: 'Type', render: (r) => <Tag>{r.channel === 'WEB' ? 'Web' : 'API'}</Tag> },
                  { key: 'status', label: 'Status', render: (r) => <StatusChip value={r.status} /> },
                  { key: 'result', label: 'Pass / Fail', align: 'right', render: (r) => `${r.passed} / ${r.failed}` },
                  { key: 'at', label: 'Started', render: (r) => ago(r.createdAt) },
                ]} />
              )}
            </Card>
            <Card title="Recent defects" pad={false} action={<Link component={RouterLink} to="/defects" variant="body2">View all</Link>}>
              {d.recentDefects.length === 0 ? <Empty title="No defects found" /> : (
                <DataTable label="Recent defects" rows={d.recentDefects} rowKey={(r) => r.id} onRowClick={(r) => open(r.projectId, `/defects/${r.id}`)} columns={[
                  { key: 'id', label: 'ID', nowrap: true, render: (r) => `DEF-${r.number}` },
                  { key: 'title', label: 'Summary', render: (r) => <><Typography variant="body1" noWrap sx={{ maxWidth: 260 }}>{r.title}</Typography><Typography variant="caption">{r.projectName}</Typography></> },
                  { key: 'severity', label: 'Severity', render: (r) => <StatusChip value={r.severity} /> },
                  { key: 'status', label: 'Status', render: (r) => <StatusChip value={r.status} /> },
                ]} />
              )}
            </Card>
          </Box>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '3fr 2fr' } }}>
            <Card title="Project health" pad={false}>
              {d.projects.length === 0 ? <Empty title="No projects yet" action={<Link component={RouterLink} to="/projects">Go to Projects</Link>} /> : (
                <DataTable label="Project health" rows={d.projects} rowKey={(r) => r.id} onRowClick={(r) => open(r.id, `/projects/${r.id}`)} columns={[
                  { key: 'name', label: 'Project', render: (r) => r.name },
                  { key: 'rate', label: 'Pass rate', align: 'right', render: (r) => percent(r.passRate) },
                  { key: 'open', label: 'Open defects', align: 'right', render: (r) => r.openDefects },
                  { key: 'crit', label: 'Critical', align: 'right', render: (r) => r.criticalDefects },
                  { key: 'last', label: 'Last run', render: (r) => (r.lastRunStatus ? <Stack direction="row" spacing={1} alignItems="center"><StatusChip value={r.lastRunStatus} /><Typography variant="caption">{ago(r.lastRunAt)}</Typography></Stack> : 'No runs') },
                ]} />
              )}
            </Card>
            <Card title="Recent activity">
              {d.activity.length === 0 ? <Typography variant="body2" color="text.secondary">Nothing yet.</Typography> : (
                <Stack spacing={1} sx={{ maxHeight: 300, overflow: 'auto' }}>
                  {d.activity.map((a, i) => (
                    <Box key={i}>
                      <Typography variant="body2">{words(a.action)}{a.projectName ? ` in ${a.projectName}` : ''}</Typography>
                      <Typography variant="caption">{a.actor}, {ago(a.at)}</Typography>
                    </Box>
                  ))}
                </Stack>
              )}
            </Card>
          </Box>
        </Stack>
      )}</Async>
    </>
  );
}
