import { useState } from 'react';
import { Box, Button, Stack, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { useQuery } from '@tanstack/react-query';
import { api, query, type Analytics, type Overview } from '../api';
import { ChartCard, LineDays, RankBars, SegmentBar, StackedDays } from '../design/charts';
import { Async, Card, DataTable, Empty, Mono, PageHeader, percent, Stat, words } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { Choice } from './shared';

const ranges: [string, string][] = [['7', 'Last 7 days'], ['30', 'Last 30 days'], ['90', 'Last 90 days']];

function Filters({ scope, setScope, days, setDays }: { scope: string; setScope: (s: string) => void; days: string; setDays: (d: string) => void }) {
  const { projects } = useWorkspace();
  return (
    <Stack direction="row" spacing={1}>
      <Choice label="Projects" width={200} value={scope} onChange={setScope} options={[['', 'All my projects'], ...projects.map((p) => [p.id, p.name] as [string, string])]} />
      <Choice label="Period" width={150} value={days} onChange={setDays} options={ranges} />
    </Stack>
  );
}

export function ReportsPage() {
  const { tokens } = useTheme();
  const [scope, setScope] = useState('');
  const [days, setDays] = useState('30');
  const params = { projectId: scope, days };
  const q = useQuery({ queryKey: ['overview', scope, days], queryFn: () => api<Overview>(`/reports/overview${query(params)}`) });
  const s = tokens.status;
  const csv = (section: string) => `/api/v1/reports/export.csv${query({ ...params, section })}`;
  const Csv = ({ section }: { section: string }) => <Button component="a" href={csv(section)} download>CSV</Button>;
  return (
    <>
      <PageHeader title="Reports" description="Computed from stored results. Exports contain the same numbers shown here." actions={<>
        <Filters scope={scope} setScope={setScope} days={days} setDays={setDays} />
        <Button variant="contained" component="a" href={`/api/v1/reports/export.xlsx${query(params)}`} download>Export Excel</Button>
      </>} />
      <Async q={q} rows={8}>{(o) => (
        <Stack spacing={2}>
          <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2,1fr)', md: 'repeat(5,1fr)' } }}>
            <Stat label="Executions" value={o.execution.runs} hint={`${o.execution.active} active`} /><Stat label="Completed" value={o.execution.succeeded} /><Stat label="Failed runs" value={o.execution.failed} />
            <Stat label="Pass rate" value={percent(o.execution.passRate)} /><Stat label="Blocked steps" value={o.execution.blocked} />
          </Box>
          <Card title="Execution summary" action={<Csv section="execution" />}>
            <SegmentBar parts={[{ label: 'Passed', value: o.execution.passed, color: s.good.mark }, { label: 'Failed', value: o.execution.failedSteps, color: s.critical.mark }, { label: 'Blocked', value: o.execution.blocked, color: s.warning.mark }, { label: 'Inconclusive', value: o.execution.inconclusive, color: s.neutral.mark }]} />
          </Card>
          <Card pad={false} title="Project quality" action={<Csv section="projects" />}>
            {o.projects.length === 0 ? <Empty title="No projects in scope" /> : <DataTable label="Project quality" rows={o.projects} rowKey={(r) => r.project} columns={[
              { key: 'p', label: 'Project', render: (r) => r.project }, { key: 'r', label: 'Executions', align: 'right', render: (r) => r.runs }, { key: 'pr', label: 'Pass rate', align: 'right', render: (r) => percent(r.passRate) },
              { key: 'o', label: 'Open defects', align: 'right', render: (r) => r.openDefects }, { key: 'c', label: 'Critical open', align: 'right', render: (r) => r.criticalDefects }, { key: 'f', label: 'Fixed', align: 'right', render: (r) => r.fixedDefects }, { key: 't', label: 'Test cases', align: 'right', render: (r) => r.testCases },
            ]} />}
          </Card>
          <Card pad={false} title="Test coverage" action={<Csv section="coverage" />}>
            <DataTable label="Coverage" rows={o.coverage} rowKey={(r) => r.project} columns={[
              { key: 'p', label: 'Project', render: (r) => r.project }, { key: 't', label: 'Cases', align: 'right', render: (r) => r.total }, { key: 'a', label: 'Automated', align: 'right', render: (r) => r.automated }, { key: 'm', label: 'Manual', align: 'right', render: (r) => r.manual },
              { key: 'g', label: 'Generated', align: 'right', render: (r) => r.generated }, { key: 'w', label: 'Awaiting review', align: 'right', render: (r) => r.awaitingReview },
              { key: 's', label: 'In a suite', align: 'right', render: (r) => r.inSuite }, { key: 'e', label: 'Executed', align: 'right', render: (r) => r.executed }, { key: 'pc', label: 'Executed %', align: 'right', render: (r) => (r.total ? `${((r.executed / r.total) * 100).toFixed(0)}%` : 'No cases') },
            ]} />
          </Card>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
            <Card title="Defects by severity" action={<Csv section="defects" />}>
              {o.defects.bySeverity.length === 0 ? <Empty title="No defects" /> : <SegmentBar parts={['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((k, i) => ({ label: words(k), value: o.defects.bySeverity.find((c) => c.name === k)?.count ?? 0, color: [s.critical.mark, s.serious.mark, s.warning.mark, s.neutral.mark][i] }))} />}
              <Typography variant="h3" sx={{ mt: 2, mb: 1 }}>By status</Typography>
              <RankBars rows={o.defects.byStatus.map((c) => ({ name: words(c.name), value: c.count }))} />
            </Card>
            <Card pad={false} title="Web and API comparison" action={<Csv section="channels" />}>
              <DataTable label="Web and API comparison" rows={o.channels} rowKey={(r) => r.channel} columns={[
                { key: 'c', label: 'Type', render: (r) => (r.channel === 'WEB' ? 'Web' : 'API') }, { key: 'r', label: 'Executions', align: 'right', render: (r) => r.runs }, { key: 'p', label: 'Passed', align: 'right', render: (r) => r.passed },
                { key: 'f', label: 'Failed', align: 'right', render: (r) => r.failed }, { key: 'b', label: 'Blocked', align: 'right', render: (r) => r.blocked }, { key: 'pr', label: 'Pass rate', align: 'right', render: (r) => percent(r.passRate) }, { key: 'd', label: 'Defects', align: 'right', render: (r) => r.defects },
              ]} />
            </Card>
          </Box>
          <ChartCard title="Pass rate trend" columns={['Day', 'Runs', 'Passed', 'Failed', 'Pass rate']} rows={o.trend.map((t) => [t.day, t.runs, t.passed, t.failed, percent(t.passRate)])}>
            <LineDays rows={o.trend as never} valueKey="passRate" label="Pass rate" unit="%" color={tokens.series[0]} />
          </ChartCard>
        </Stack>
      )}</Async>
    </>
  );
}

export function AnalyticsPage() {
  const { tokens } = useTheme();
  const [scope, setScope] = useState('');
  const [days, setDays] = useState('30');
  const q = useQuery({ queryKey: ['analytics', scope, days], queryFn: () => api<Analytics>(`/analytics${query({ projectId: scope, days })}`) });
  return (
    <>
      <PageHeader title="Analytics" description="Where quality is moving, and where failures concentrate." actions={<Filters scope={scope} setScope={setScope} days={days} setDays={setDays} />} />
      <Async q={q} rows={8}>{(a) => (
        <Stack spacing={2}>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
            <ChartCard title="Pass rate" columns={['Day', 'Pass rate']} rows={a.trend.map((t) => [t.day, percent(t.passRate)])}>
              <LineDays rows={a.trend as never} valueKey="passRate" label="Pass rate" unit="%" color={tokens.series[0]} />
            </ChartCard>
            <ChartCard title="Defects opened and closed" columns={['Day', 'Opened', 'Closed']} rows={a.trend.map((t) => [t.day, t.defectsOpened, t.defectsClosed])}>
              <StackedDays rows={a.trend as never} label="Defects opened and closed per day" series={[{ key: 'defectsOpened', label: 'Opened', color: tokens.series[0] }, { key: 'defectsClosed', label: 'Closed', color: tokens.series[1] }]} />
            </ChartCard>
          </Box>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
            <Card pad={false} title="Failure hotspots">
              {a.hotspots.length === 0 ? <Empty title="No failures in this period" /> : <DataTable label="Failure hotspots" rows={a.hotspots} rowKey={(h) => h.project + h.name} columns={[
                { key: 'n', label: 'Target', render: (h) => <Typography variant="body2" noWrap sx={{ maxWidth: 340 }}>{h.name}</Typography> }, { key: 'p', label: 'Project', render: (h) => h.project }, { key: 'f', label: 'Failures', align: 'right', render: (h) => `${h.failures} of ${h.total}` },
              ]} />}
            </Card>
            <Card title="Defect types">
              {a.defectTypes.length === 0 ? <Empty title="No defects" /> : <RankBars rows={a.defectTypes.slice(0, 10).map((c) => ({ name: words(c.name), value: c.count }))} />}
            </Card>
          </Box>
          <Card pad={false} title="Slowest API operations">
            {a.slowest.length === 0 ? <Empty title="No API calls in this period" /> : <DataTable label="Slowest API operations" rows={a.slowest} rowKey={(s) => s.project + s.operation} columns={[
              { key: 'o', label: 'Operation', render: (s) => <Mono>{s.operation}</Mono> }, { key: 'p', label: 'Project', render: (s) => s.project }, { key: 'c', label: 'Calls', align: 'right', render: (s) => s.calls }, { key: 'a', label: 'Average', align: 'right', render: (s) => `${s.averageMs} ms` }, { key: 'm', label: 'Max', align: 'right', render: (s) => `${s.maxMs} ms` },
            ]} />}
          </Card>
        </Stack>
      )}</Async>
    </>
  );
}
