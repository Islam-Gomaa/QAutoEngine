import { useState } from 'react';
import { Alert, Box, Button, Drawer, Link, Stack, Typography } from '@mui/material';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { api, query, type EvidenceItem, type Page } from '../api';
import { Async, bytes, Card, DataTable, dateTime, Empty, Mono, PageHeader, words } from '../design/ui';
import { EvidenceViewer } from './EvidenceViewer';
import { Choice, NeedsProject } from './shared';

function Body({ project }: { project: string }) {
  const [type, setType] = useState('');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<EvidenceItem | null>(null);
  const q = useQuery({ queryKey: ['evidence', project, type, page], placeholderData: keepPreviousData, queryFn: () => api<Page<EvidenceItem>>(`/projects/${project}/evidence${query({ type, page, size: 25 })}`) });
  return (
    <>
      <PageHeader title="Evidence" description="Proof captured during runs. It is private to this project, redacted before storage, and removed when its retention ends." />
      <Alert severity="info" sx={{ mb: 2 }}>Screenshots are masked, and logs and API exchanges appear as plain text. DOM snapshots are download-only. Every view is recorded in the audit log. Playwright traces are not captured.</Alert>
      <Card pad={false} title="Stored evidence" action={<Choice label="Type" width={170} value={type} onChange={(v) => { setType(v); setPage(0); }} options={[['', 'All'], ['SCREENSHOT', 'Screenshots'], ['SIGNAL_LOG', 'Browser logs'], ['API_EXCHANGE', 'API exchanges'], ['DOM_SNAPSHOT', 'DOM snapshots']]} />}>
        <Async q={q}>{(p) => (p.items.length === 0 ? <Empty title="No evidence yet">Runs store evidence for the problems they find.</Empty> : (
          <DataTable label="Evidence" testId="evidence-row" rows={p.items} rowKey={(r) => r.id} paging={{ page, size: 25, total: p.total, onPage: setPage }} columns={[
            { key: 't', label: 'Type', render: (r) => words(r.type) },
            { key: 'r', label: 'Run', render: (r) => <Link component={RouterLink} to={`/execution/${r.runId}`}><Mono>{r.runId.slice(0, 8)}</Mono></Link> },
            { key: 's', label: 'Step', align: 'right', render: (r) => r.step }, { key: 'z', label: 'Size', align: 'right', render: (r) => bytes(r.size) },
            { key: 'c', label: 'Captured', render: (r) => dateTime(r.createdAt) }, { key: 'e', label: 'Retained until', render: (r) => (r.legalHold ? 'Legal hold' : dateTime(r.expiresAt)) },
            { key: 'x', label: '', align: 'right', render: (r) => <Button onClick={() => setOpen(r)}>View</Button> },
          ]} />))}</Async>
      </Card>
      <Drawer anchor="right" open={!!open} onClose={() => setOpen(null)}>
        <Box sx={{ width: { xs: '100vw', md: 640 }, p: 2.5 }}>
          {open && <Stack spacing={1.5}><Typography variant="h2">{words(open.type)}, step {open.step}</Typography><EvidenceViewer project={project} item={open} /></Stack>}
        </Box>
      </Drawer>
    </>
  );
}

export const EvidencePage = () => <NeedsProject>{(p) => <Body project={p} />}</NeedsProject>;
