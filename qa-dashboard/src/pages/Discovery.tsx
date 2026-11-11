import { useState } from 'react';
import { Alert, Box, Button, Link, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api, type DiscoveryPage, type DiscoveryPageRow, type DiscoverySummary, type Safety } from '../api';
import { Async, Card, DataTable, Empty, KeyValue, Mono, Stat, Tag } from '../design/ui';

/**
 * What a discovery run found: every page behind sign-in, what each holds, and what each action would do.
 * Nothing here was used to find it: sensitive and destructive actions are listed, never pressed.
 */

export const SAFETY: [Safety, string, string][] = [
  ['READ_ONLY', 'Read-only', 'Viewing, navigating, opening a tab or filter.'],
  ['REVERSIBLE', 'Reversible', 'Changes something that can be changed back, such as editing.'],
  ['SENSITIVE', 'Sensitive', 'Sends, pays, invites, publishes or signs out, or its intent is unclear.'],
  ['DESTRUCTIVE', 'Destructive', 'Removes or ends something.'],
];
const label = (s: Safety) => SAFETY.find(([k]) => k === s)?.[1] ?? s;

const path = (url: string) => { try { const u = new URL(url); return u.pathname + u.search; } catch { return url; } };

function counts(actions: Record<Safety, number>) {
  const parts = SAFETY.filter(([k]) => actions[k] > 0).map(([k, l]) => `${actions[k]} ${l.toLowerCase()}`);
  return parts.length ? parts.join(', ') : 'None';
}

function PageDetail({ project, run, pageId, open }: { project: string; run: string; pageId: string; open: (id: string) => void }) {
  const q = useQuery({ queryKey: ['discovery-page', run, pageId], queryFn: () => api<DiscoveryPage>(`/projects/${project}/runs/${run}/discovery/pages/${pageId}`) });
  return (
    <Async q={q}>{(p) => (
      <Card title={<span>{p.title || path(p.url)} <Typography component="span" variant="caption" color="text.secondary"><Mono>{path(p.url)}</Mono></Typography></span>} testId="discovery-page">
        <Stack spacing={2.5}>
          <KeyValue items={[['Links from the start', String(p.depth)], ['Headings', p.headings.join(' · ') || 'None'],
            ['Navigation', p.structure.navigation.map((n) => `${n.name || 'Unnamed'}: ${n.links.join(', ')}`).join('; ') || 'None'],
            ['Screenshot', p.screenshot ? <Link key="s" href={`/api/v1/projects/${project}/evidence/${p.screenshot}/content`} target="_blank" rel="noopener noreferrer">Open the masked screenshot</Link> : 'Not taken']]} />
          <Box>
            <Typography component="h3" variant="body1" sx={{ fontWeight: 600, mb: 1 }}>Forms ({p.structure.forms.length})</Typography>
            {p.structure.forms.length === 0 ? <Typography variant="body2">None.</Typography> : p.structure.forms.map((f, i) => (
              <Box key={i} sx={{ mb: 1.5 }}>
                <Typography variant="body2" sx={{ mb: 0.5 }}><strong>{f.name || 'Unnamed form'}</strong>: {f.method.toUpperCase()} {f.action || '(this page)'}{f.submitLabel ? `, sent by "${f.submitLabel}"` : ''}. Never submitted.</Typography>
                <DataTable label={`Fields of ${f.name || 'form'}`} testId="discovered-field" rows={f.fields} rowKey={(x) => `${x.name}-${x.label}`} columns={[
                  { key: 'l', label: 'Label', render: (x) => x.labelled ? x.label : <Tag title="Screen readers cannot name this field.">No accessible name</Tag> },
                  { key: 'n', label: 'Name', render: (x) => <Mono>{x.name}</Mono> }, { key: 't', label: 'Type', render: (x) => x.type },
                  { key: 'r', label: 'Required', render: (x) => (x.required ? 'Required' : 'Optional') },
                ]} />
              </Box>
            ))}
          </Box>
          <KeyValue items={[['Tables', p.structure.tables.map((t) => `${t.caption || 'Untitled'} (${t.headers.join(', ')}; ${t.rows} rows)`).join('; ') || 'None'],
            ['Dialogs', p.structure.dialogs.map((d) => `${d.name || 'Unnamed'}${d.open ? ' (open)' : ' (closed)'}`).join('; ') || 'None']]} />
          <Box>
            <Typography component="h3" variant="body1" sx={{ fontWeight: 600, mb: 1 }}>Actions ({p.actions.length})</Typography>
            <DataTable label="Actions on this page" testId="discovered-action" rows={p.actions} rowKey={(a) => `${a.kind}-${a.name}-${a.href ?? ''}-${a.reason}`} columns={[
              { key: 'n', label: 'Action', render: (a) => a.name || <Typography variant="caption">Unnamed</Typography> },
              { key: 'k', label: 'Kind', render: (a) => a.kind.charAt(0) + a.kind.slice(1).toLowerCase() },
              { key: 's', label: 'What it would do', render: (a) => <Stack alignItems="flex-start"><Tag>{label(a.safety)}</Tag><Typography variant="caption">{a.reason}</Typography></Stack> },
              { key: 'f', label: 'Found by', render: (a) => (a.locator ? <Mono>{a.locator.strategy.toLowerCase()} {a.locator.value}{a.locator.name ? ` "${a.locator.name}"` : ''}</Mono> : '') },
            ]} />
          </Box>
          {p.linksTo.length > 0 && (
            <Box>
              <Typography component="h3" variant="body1" sx={{ fontWeight: 600, mb: 1 }}>Leads to</Typography>
              <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">{p.linksTo.map((l) => <Button key={`${l.pageId}-${l.via}`} variant="outlined" onClick={() => open(l.pageId)}>{l.via || path(l.url)}</Button>)}</Stack>
            </Box>
          )}
        </Stack>
      </Card>
    )}</Async>
  );
}

export function DiscoveryPanel({ project, run, live }: { project: string; run: string; live: number | false }) {
  const q = useQuery({ queryKey: ['discovery', run], queryFn: () => api<DiscoverySummary>(`/projects/${project}/runs/${run}/discovery`), refetchInterval: live });
  const [selected, setSelected] = useState<string | null>(null);
  return (
    <Async q={q}>{(d) => (d.pages === 0 ? <Card><Empty title="No pages recorded yet">Pages appear here as the run finds them.</Empty></Card> : (
      <Stack spacing={2} data-testid="discovery">
        <Alert severity="info">Discovery only reads. Forms are never submitted, and sensitive and destructive actions are recorded, never used.</Alert>
        <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2,1fr)', md: 'repeat(6,1fr)' } }}>
          <Stat label="Pages" value={d.pages} /><Stat label="Links between them" value={d.links} /><Stat label="Forms" value={d.forms} />
          <Stat label="Fields" value={d.fields} hint={`${d.requiredFields} required`} /><Stat label="Fields with no name" value={d.unlabelledFields} tone={d.unlabelledFields ? 'warning' : undefined} />
          <Stat label="Tables and dialogs" value={d.tables + d.dialogs} hint={`${d.tables} tables, ${d.dialogs} dialogs`} />
        </Box>
        <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(2,1fr)', md: 'repeat(4,1fr)' } }} aria-label="Actions by what they would do" role="group">
          {SAFETY.map(([k, l, hint]) => <Stat key={k} label={l} value={d.actions[k]} hint={hint} tone={k === 'DESTRUCTIVE' && d.actions[k] ? 'critical' : undefined} />)}
        </Box>
        <Card pad={false} title="Pages">
          <DataTable label="Discovered pages" testId="discovered-page" rows={d.pageRows} rowKey={(r: DiscoveryPageRow) => r.id} onRowClick={(r) => setSelected(r.id)} columns={[
            { key: 'p', label: 'Page', render: (r) => <><Typography variant="body1" sx={{ fontWeight: 560 }}>{r.title || 'Untitled'}</Typography><Mono>{path(r.url)}</Mono></> },
            { key: 'd', label: 'Depth', render: (r) => r.depth },
            { key: 'f', label: 'Forms', render: (r) => (r.forms ? `${r.forms} (${r.fields} fields)` : '0') },
            { key: 't', label: 'Tables', render: (r) => r.tables }, { key: 'g', label: 'Dialogs', render: (r) => r.dialogs },
            { key: 'a', label: 'Actions', render: (r) => counts(r.actions) },
            { key: 'x', label: '', align: 'right', render: (r) => <Button onClick={(e) => { e.stopPropagation(); setSelected(r.id); }}>Details</Button> },
          ]} />
        </Card>
        {selected && <PageDetail project={project} run={run} pageId={selected} open={setSelected} />}
      </Stack>
    ))}</Async>
  );
}
