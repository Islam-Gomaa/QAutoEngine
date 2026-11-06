import { Box, Button, Link, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Failure, Loading, Mono } from '../design/ui';

const url = (project: string, id: string) => `/api/v1/projects/${project}/evidence/${id}/content`;

/** Logs and API exchanges are shown as plain text. Text nodes cannot run anything, however hostile the content. */
function TextEvidence({ project, id }: { project: string; id: string }) {
  const q = useQuery({
    queryKey: ['evidence-text', id], queryFn: async () => {
      const r = await fetch(url(project, id), { credentials: 'same-origin' });
      if (!r.ok) throw new Error(`Could not load this evidence (${r.status})`);
      return r.text();
    },
  });
  if (q.isPending) return <Loading rows={3} />;
  if (q.isError) return <Failure error={q.error} />;
  let text = q.data;
  try { text = JSON.stringify(JSON.parse(q.data), null, 2); } catch { /* not JSON: show it as stored */ }
  return <Box component="pre" data-testid="evidence-text" tabIndex={0} sx={{ m: 0, p: 1.5, bgcolor: 'action.hover', borderRadius: 1, fontSize: 12, maxHeight: 340, overflow: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'ui-monospace, Menlo, monospace' }}>{text}</Box>;
}

export function EvidenceViewer({ project, item }: { project: string; item: { id: string; type: string; sha256: string } }) {
  return (
    <Stack spacing={1}>
      {item.type === 'SCREENSHOT' && (
        <Link href={url(project, item.id)} target="_blank" rel="noreferrer noopener">
          <Box component="img" data-testid="evidence-image" src={url(project, item.id)} alt="Masked screenshot captured during the run" sx={{ maxWidth: '100%', maxHeight: 420, border: 1, borderColor: 'divider', borderRadius: 1, display: 'block' }} />
        </Link>
      )}
      {(item.type === 'SIGNAL_LOG' || item.type === 'API_EXCHANGE') && <TextEvidence project={project} id={item.id} />}
      {item.type === 'DOM_SNAPSHOT' && (
        <Stack direction="row" spacing={1.5} alignItems="center">
          <Button variant="outlined" component="a" href={url(project, item.id)} download>Download DOM snapshot</Button>
          <Typography variant="body2" color="text.secondary">Captured pages are untrusted, so they are never opened here.</Typography>
        </Stack>
      )}
      <Typography variant="caption">SHA-256 <Mono>{item.sha256.slice(0, 24)}</Mono></Typography>
    </Stack>
  );
}
