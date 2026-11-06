import { useState, type ReactNode } from 'react';
import { Alert, Box, Button, Card as MuiCard, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Skeleton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TablePagination, TableSortLabel, Tooltip, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import type { UseQueryResult } from '@tanstack/react-query';
import CheckCircle from '@mui/icons-material/CheckCircleOutlined';
import ErrorIcon from '@mui/icons-material/ErrorOutlined';
import Warning from '@mui/icons-material/WarningAmberOutlined';
import Block from '@mui/icons-material/BlockOutlined';
import Schedule from '@mui/icons-material/ScheduleOutlined';
import Sync from '@mui/icons-material/SyncOutlined';
import Help from '@mui/icons-material/HelpOutlineOutlined';
import Cancel from '@mui/icons-material/CancelOutlined';
import Inbox from '@mui/icons-material/InboxOutlined';
import KeyboardDoubleArrowUp from '@mui/icons-material/KeyboardDoubleArrowUpOutlined';
import KeyboardArrowUp from '@mui/icons-material/KeyboardArrowUpOutlined';
import KeyboardArrowDown from '@mui/icons-material/KeyboardArrowDownOutlined';
import Remove from '@mui/icons-material/RemoveOutlined';
import type { Tokens } from './tokens';

type Tone = keyof Tokens['status'];
const STATES: Record<string, { tone: Tone; icon: ReactNode; label: string }> = {
  PASS: { tone: 'good', icon: <CheckCircle />, label: 'Passed' },
  FAIL: { tone: 'critical', icon: <ErrorIcon />, label: 'Failed' },
  BLOCKED: { tone: 'warning', icon: <Block />, label: 'Blocked' },
  INCONCLUSIVE: { tone: 'neutral', icon: <Help />, label: 'Inconclusive' },
  QUEUED: { tone: 'neutral', icon: <Schedule />, label: 'Queued' },
  RUNNING: { tone: 'info', icon: <Sync />, label: 'Running' },
  LEASED: { tone: 'info', icon: <Sync />, label: 'Starting' },
  SUCCEEDED: { tone: 'good', icon: <CheckCircle />, label: 'Completed' },
  FAILED: { tone: 'critical', icon: <ErrorIcon />, label: 'Failed' },
  CANCELLED: { tone: 'neutral', icon: <Cancel />, label: 'Cancelled' },
  EXPIRED: { tone: 'serious', icon: <Warning />, label: 'Expired' },
  OPEN: { tone: 'critical', icon: <ErrorIcon />, label: 'Open' },
  TRIAGED: { tone: 'warning', icon: <Schedule />, label: 'Triaged' },
  FIXED: { tone: 'good', icon: <CheckCircle />, label: 'Fixed' },
  WONT_FIX: { tone: 'neutral', icon: <Cancel />, label: "Won't fix" },
  DRAFT: { tone: 'neutral', icon: <Schedule />, label: 'Draft' },
  READY: { tone: 'good', icon: <CheckCircle />, label: 'Ready' },
  DEPRECATED: { tone: 'neutral', icon: <Cancel />, label: 'Deprecated' },
  CRITICAL: { tone: 'critical', icon: <KeyboardDoubleArrowUp />, label: 'Critical' },
  HIGH: { tone: 'serious', icon: <KeyboardArrowUp />, label: 'High' },
  MEDIUM: { tone: 'warning', icon: <Remove />, label: 'Medium' },
  LOW: { tone: 'neutral', icon: <KeyboardArrowDown />, label: 'Low' },
};

/** Status always carries an icon and a word, so it never depends on color alone. */
export function StatusChip({ value, testId }: { value: string | null | undefined; testId?: string }) {
  const { tokens } = useTheme();
  if (!value) return <Typography variant="caption">None</Typography>;
  const state = STATES[value] ?? { tone: 'neutral' as Tone, icon: <Help />, label: value };
  const c = tokens.status[state.tone];
  return (
    <Box component="span" data-testid={testId} data-value={value} sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, px: 0.75, py: '2px', borderRadius: 1, bgcolor: c.bg, color: c.fg, fontSize: 11.5, fontWeight: 600, whiteSpace: 'nowrap', '& svg': { fontSize: 13 } }}>
      {state.icon}{state.label}
    </Box>
  );
}

export function Tag({ children, title }: { children: ReactNode; title?: string }) {
  const chip = <Chip size="small" variant="outlined" label={children} sx={{ borderColor: 'divider', color: 'text.secondary' }} />;
  return title ? <Tooltip title={title}>{chip}</Tooltip> : chip;
}

export function Card({ title, action, children, pad = true, testId }: { title?: ReactNode; action?: ReactNode; children: ReactNode; pad?: boolean; testId?: string }) {
  return (
    <MuiCard variant="outlined" data-testid={testId} sx={{ borderColor: 'divider', minWidth: 0 }}>
      {title && (
        <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1} sx={{ px: 2, pt: 1.5, pb: pad ? 0 : 1.5, minHeight: 28 }}>
          <Typography variant="h2" component="h2">{title}</Typography>
          {action}
        </Stack>
      )}
      <Box sx={pad ? { p: 2 } : undefined}>{children}</Box>
    </MuiCard>
  );
}

export function PageHeader({ title, description, actions }: { title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return (
    <Stack direction="row" alignItems="flex-start" justifyContent="space-between" spacing={2} sx={{ mb: 2.5 }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h1" component="h1">{title}</Typography>
        {description && <Typography variant="body1" color="text.secondary" sx={{ mt: 0.5, maxWidth: 760 }}>{description}</Typography>}
      </Box>
      {actions && <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>{actions}</Stack>}
    </Stack>
  );
}

export function Empty({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <Stack alignItems="center" spacing={1} sx={{ py: 5, px: 2, textAlign: 'center' }} data-testid="empty-state">
      <Inbox sx={{ color: 'text.disabled', fontSize: 30 }} />
      <Typography variant="h3">{title}</Typography>
      {children && <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 440 }}>{children}</Typography>}
      {action}
    </Stack>
  );
}

export function Failure({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <Alert severity="error" data-testid="error-state" action={retry && <Button color="inherit" onClick={retry}>Try again</Button>}>
      {error instanceof Error ? error.message : 'Something went wrong.'}
    </Alert>
  );
}

export function Loading({ rows = 4 }: { rows?: number }) {
  return <Stack spacing={1} data-testid="loading-state" aria-label="Loading">{Array.from({ length: rows }, (_, i) => <Skeleton key={i} variant="rounded" height={30} />)}</Stack>;
}

/** The three states every data screen has: waiting, failed, and loaded. */
export function Async<T>({ q, children, rows }: { q: UseQueryResult<T>; children: (data: T) => ReactNode; rows?: number }) {
  if (q.isPending) return <Loading rows={rows} />;
  if (q.isError) return <Failure error={q.error} retry={() => q.refetch()} />;
  return <>{children(q.data)}</>;
}

export interface Column<T> { key: string; label: string; render: (row: T) => ReactNode; sortable?: boolean; align?: 'right' | 'left'; width?: number | string; nowrap?: boolean }

export function DataTable<T>({ columns, rows, rowKey, sort, onSort, paging, testId, onRowClick, label }: {
  columns: Column<T>[]; rows: T[]; rowKey: (row: T) => string; label: string; testId?: string; onRowClick?: (row: T) => void;
  sort?: { key: string; order: 'asc' | 'desc' }; onSort?: (key: string) => void;
  paging?: { page: number; size: number; total: number; onPage: (page: number) => void };
}) {
  return (
    <>
      <TableContainer>
        <Table size="small" aria-label={label}>
          <TableHead>
            <TableRow>
              {columns.map((c) => (
                <TableCell key={c.key} align={c.align} sx={{ width: c.width }} sortDirection={sort?.key === c.key ? sort.order : false}>
                  {c.sortable && onSort ? <TableSortLabel active={sort?.key === c.key} direction={sort?.key === c.key ? sort.order : 'asc'} onClick={() => onSort(c.key)}>{c.label}</TableSortLabel> : c.label}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={rowKey(row)} hover data-testid={testId} onClick={onRowClick ? () => onRowClick(row) : undefined} sx={onRowClick ? { cursor: 'pointer' } : undefined}>
                {columns.map((c) => <TableCell key={c.key} align={c.align} sx={c.nowrap ? { whiteSpace: 'nowrap' } : undefined}>{c.render(row)}</TableCell>)}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {paging && paging.total > paging.size && (
        <TablePagination component="div" count={paging.total} page={paging.page} rowsPerPage={paging.size} rowsPerPageOptions={[paging.size]} onPageChange={(_, p) => paging.onPage(p)} />
      )}
    </>
  );
}

export function Stat({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: string; tone?: Tone }) {
  const { tokens } = useTheme();
  return (
    <MuiCard variant="outlined" sx={{ borderColor: 'divider', p: 1.75, minWidth: 0 }} data-testid={`stat-${label}`}>
      <Typography variant="body2" color="text.secondary" noWrap>{label}</Typography>
      <Typography sx={{ fontSize: 24, fontWeight: 650, lineHeight: 1.25, mt: 0.25, letterSpacing: '-0.01em', color: tone ? tokens.status[tone].fg : 'text.primary', fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>
      {hint && <Typography variant="caption" noWrap component="div">{hint}</Typography>}
    </MuiCard>
  );
}

export function KeyValue({ items }: { items: [string, ReactNode][] }) {
  return (
    <Box component="dl" sx={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', columnGap: 3, rowGap: 0.75, m: 0 }}>
      {items.map(([k, v]) => (
        <Box key={k} sx={{ display: 'contents' }}>
          <Typography component="dt" variant="body2" color="text.secondary">{k}</Typography>
          <Typography component="dd" variant="body2" sx={{ m: 0, minWidth: 0, overflowWrap: 'anywhere' }}>{v}</Typography>
        </Box>
      ))}
    </Box>
  );
}

export function Mono({ children }: { children: ReactNode }) {
  return <Box component="span" sx={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12 }}>{children}</Box>;
}

export function FormDialog({ open, title, submit, onClose, onSubmit, children, busy, error, width }: {
  open: boolean; title: string; submit: string; onClose: () => void; onSubmit: () => void; children: ReactNode; busy?: boolean; error?: unknown; width?: 'sm' | 'md';
}) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth={width ?? 'sm'} aria-labelledby="form-dialog-title">
      <form onSubmit={(e) => { e.preventDefault(); onSubmit(); }}>
        <DialogTitle id="form-dialog-title" sx={{ fontSize: 16, fontWeight: 620 }}>{title}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {error ? <Failure error={error} /> : null}
            {children}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={busy} startIcon={busy ? <CircularProgress size={13} color="inherit" /> : undefined}>{submit}</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

export function Confirm({ label, title, body, onConfirm, color, disabled }: { label: string; title: string; body: ReactNode; onConfirm: () => Promise<unknown>; color?: 'error' | 'primary'; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  return (
    <>
      <Button color={color ?? 'primary'} disabled={disabled} onClick={(e) => { e.stopPropagation(); setOpen(true); }}>{label}</Button>
      <FormDialog open={open} title={title} submit={label} busy={busy} error={error} onClose={() => setOpen(false)}
        onSubmit={async () => { setBusy(true); setError(undefined); try { await onConfirm(); setOpen(false); } catch (e) { setError(e); } finally { setBusy(false); } }}>
        <Typography variant="body1">{body}</Typography>
      </FormDialog>
    </>
  );
}

export const dateTime = (s: string | null | undefined) => (s ? new Date(s).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : 'Never');
export function ago(s: string | null | undefined): string {
  if (!s) return 'Never';
  const seconds = Math.max(0, (Date.now() - new Date(s).getTime()) / 1000);
  if (seconds < 60) return 'Just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
export const percent = (v: number | null | undefined) => (v === null || v === undefined ? 'No results' : `${v.toFixed(1)}%`);
export const words = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase()).replace(/\b(api|dom|totp|url|http)\b/gi, (w) => w.toUpperCase());
export const bytes = (n: number) => (n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`);
export const duration = (s: number) => (s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`);
