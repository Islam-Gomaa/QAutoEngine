import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Box, IconButton, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import TableChart from '@mui/icons-material/TableChartOutlined';
import ShowChart from '@mui/icons-material/ShowChartOutlined';
import { Card, Empty } from './ui';

export interface Series { key: string; label: string; color: string }

export function Legend({ series }: { series: { label: string; color: string; value?: ReactNode }[] }) {
  return (
    <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1.75} sx={{ mt: 1 }}>
      {series.map((s) => (
        <Stack key={s.label} direction="row" alignItems="center" spacing={0.6}>
          <Box sx={{ width: 9, height: 9, borderRadius: '2px', bgcolor: s.color }} />
          <Typography variant="body2" color="text.secondary">{s.label}{s.value !== undefined && <Box component="span" sx={{ color: 'text.primary', fontWeight: 600, ml: 0.5 }}>{s.value}</Box>}</Typography>
        </Stack>
      ))}
    </Stack>
  );
}

/** Every chart has a table view: the same rows, readable without color or hover. */
export function ChartCard({ title, columns, rows, children, testId }: { title: string; columns: string[]; rows: (string | number)[][]; children: ReactNode; testId?: string }) {
  const [table, setTable] = useState(false);
  return (
    <Card title={title} testId={testId} action={
      <Tooltip title={table ? 'Show chart' : 'Show table'}>
        <IconButton aria-label={`${table ? 'Show chart' : 'Show table'}: ${title}`} onClick={() => setTable(!table)}>{table ? <ShowChart fontSize="small" /> : <TableChart fontSize="small" />}</IconButton>
      </Tooltip>}>
      {table ? (
        <Box sx={{ maxHeight: 260, overflow: 'auto' }}>
          <Table size="small" aria-label={title}>
            <TableHead><TableRow>{columns.map((c) => <TableCell key={c}>{c}</TableCell>)}</TableRow></TableHead>
            <TableBody>{rows.map((r, i) => <TableRow key={i}>{r.map((v, j) => <TableCell key={j}>{v}</TableCell>)}</TableRow>)}</TableBody>
          </Table>
        </Box>
      ) : children}
    </Card>
  );
}

const shortDay = (d: string) => new Date(`${d}T00:00:00`).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });

function ticks(max: number): number[] {
  if (max <= 0) return [0];
  const step = Math.pow(10, Math.floor(Math.log10(max)));
  const nice = [1, 2, 5, 10].map((m) => m * step).find((s) => max / s <= 4) ?? step * 10;
  const out = [];
  for (let v = 0; v <= max + nice * 0.999; v += nice) out.push(v);
  return out;
}

function Hover({ at, title, rows }: { at: number; title: string; rows: { label: string; value: string; color?: string }[] }) {
  return (
    <Box role="status" sx={{ position: 'absolute', top: 4, left: `${at}%`, transform: at > 50 ? 'translateX(calc(-100% - 10px))' : 'translateX(10px)', bgcolor: 'background.paper', border: 1, borderColor: 'divider', borderRadius: 1, boxShadow: '0 4px 14px rgba(16,21,29,.12)', px: 1.25, py: 0.75, pointerEvents: 'none', zIndex: 2, minWidth: 120 }}>
      <Typography variant="body2" sx={{ fontWeight: 620, mb: 0.25 }}>{title}</Typography>
      {rows.map((r) => (
        <Stack key={r.label} direction="row" justifyContent="space-between" spacing={2} alignItems="center">
          <Stack direction="row" spacing={0.6} alignItems="center">
            {r.color && <Box sx={{ width: 8, height: 8, borderRadius: '2px', bgcolor: r.color }} />}
            <Typography variant="body2" color="text.secondary">{r.label}</Typography>
          </Stack>
          <Typography variant="body2" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{r.value}</Typography>
        </Stack>
      ))}
    </Box>
  );
}

const H = 200; const L = 38; const R = 8; const T = 8; const B = 22;

/** Draws at the container's real width, so labels keep their size on any card. */
function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  useEffect(() => {
    if (!ref.current || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([e]) => { if (e.contentRect.width > 0) setWidth(e.contentRect.width); });
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

function Axis({ W, max, labels, format }: { W: number; max: number; labels: { x: number; text: string }[]; format?: (v: number) => string }) {
  const { tokens } = useTheme();
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  return (
    <g>
      {ticks(max).filter((v) => v <= max).map((v) => (
        <g key={v}>
          <line x1={L} x2={W - R} y1={y(v)} y2={y(v)} stroke={v === 0 ? tokens.borderStrong : tokens.grid} strokeWidth={1} />
          <text x={L - 6} y={y(v) + 3.5} textAnchor="end" fontSize={10.5} fill={tokens.textMuted}>{format ? format(v) : v}</text>
        </g>
      ))}
      {labels.map((l) => <text key={l.x} x={l.x} y={H - 6} textAnchor="middle" fontSize={10.5} fill={tokens.textMuted}>{l.text}</text>)}
    </g>
  );
}

function everyNth<T>(items: T[], target: number): number {
  return Math.max(1, Math.ceil(items.length / target));
}

/** Stacked columns over time. Hovering anywhere in a day's column shows all of its values. */
export function StackedDays({ rows, series, label }: { rows: ({ day: string } & Record<string, unknown>)[]; series: Series[]; label: string }) {
  const { tokens } = useTheme();
  const [box, W] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const value = (r: Record<string, unknown>, k: string) => Number(r[k] ?? 0);
  const totals = rows.map((r) => series.reduce((sum, s) => sum + value(r, s.key), 0));
  if (totals.every((t) => t === 0)) return <Empty title="No results in this period">Run a test to see the trend here.</Empty>;
  const top = ticks(Math.max(...totals));
  const max = top[top.length - 1];
  const band = (W - L - R) / rows.length;
  const bar = Math.min(18, band * 0.62);
  const y = (v: number) => (H - T - B) * (v / max);
  const n = everyNth(rows, Math.max(3, Math.floor(W / 90)));
  return (
    <Box ref={box} sx={{ position: 'relative' }} onMouseLeave={() => setHover(null)}>
      <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} role="img" aria-label={label} style={{ display: 'block' }}>
        <Axis W={W} max={max} labels={rows.flatMap((r, i) => (i % n === 0 ? [{ x: L + band * (i + 0.5), text: shortDay(r.day) }] : []))} />
        {rows.map((r, i) => {
          const x = L + band * i + (band - bar) / 2;
          let base = H - B;
          const segments = series.filter((s) => value(r, s.key) > 0);
          return (
            <g key={r.day}>
              {hover === i && <rect x={L + band * i} y={T} width={band} height={H - T - B} fill={tokens.sunken} />}
              {segments.map((s, j) => {
                const h = Math.max(2, y(value(r, s.key)));
                const gap = j === segments.length - 1 ? 0 : 2;
                base -= h;
                const top2 = j === segments.length - 1;
                return <path key={s.key} fill={s.color} d={top2
                  ? `M${x},${base + h} V${base + Math.min(3, h)} Q${x},${base} ${x + 3},${base} H${x + bar - 3} Q${x + bar},${base} ${x + bar},${base + Math.min(3, h)} V${base + h} Z`
                  : `M${x},${base + h} V${base + gap} H${x + bar} V${base + h} Z`} />;
              })}
              <rect x={L + band * i} y={0} width={band} height={H} fill="transparent" onMouseEnter={() => setHover(i)} />
            </g>
          );
        })}
      </svg>
      {hover !== null && (
        <Hover at={((L + band * (hover + 0.5)) / W) * 100} title={shortDay(rows[hover].day)}
          rows={series.map((s) => ({ label: s.label, value: String(value(rows[hover], s.key)), color: s.color }))} />
      )}
      <Legend series={series.map((s) => ({ ...s, value: rows.reduce((sum, r) => sum + value(r, s.key), 0) }))} />
    </Box>
  );
}

/** One line over time with a crosshair. Days with no results leave a gap rather than reading as zero. */
export function LineDays({ rows, valueKey, label, unit, color }: { rows: ({ day: string } & Record<string, unknown>)[]; valueKey: string; label: string; unit: string; color: string }) {
  const { tokens } = useTheme();
  const clip = useId();
  const [box, W] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const v = (r: Record<string, unknown>) => (r[valueKey] === null || r[valueKey] === undefined ? null : Number(r[valueKey]));
  if (rows.every((r) => v(r) === null)) return <Empty title="No results in this period" />;
  const max = 100;
  const step = (W - L - R) / Math.max(1, rows.length - 1);
  const px = (i: number) => L + step * i;
  const py = (n: number) => T + (H - T - B) * (1 - n / max);
  const segments: string[] = [];
  let current = '';
  rows.forEach((r, i) => {
    const n = v(r);
    if (n === null) { if (current) segments.push(current); current = ''; return; }
    current += `${current ? 'L' : 'M'}${px(i)},${py(n)} `;
  });
  if (current) segments.push(current);
  const n = everyNth(rows, Math.max(3, Math.floor(W / 90)));
  return (
    <Box ref={box} sx={{ position: 'relative' }} onMouseLeave={() => setHover(null)}>
      <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} role="img" aria-label={label} style={{ display: 'block' }}>
        <clipPath id={clip}><rect x={L} y={0} width={W - L} height={H} /></clipPath>
        <Axis W={W} max={max} format={(t) => `${t}${unit}`} labels={rows.flatMap((r, i) => (i % n === 0 ? [{ x: px(i), text: shortDay(r.day) }] : []))} />
        <g clipPath={`url(#${clip})`}>
          {segments.map((d) => <path key={d} d={d} fill="none" stroke={color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />)}
          {rows.map((r, i) => (v(r) === null ? null : <circle key={r.day} cx={px(i)} cy={py(v(r)!)} r={hover === i ? 5 : 4} fill={color} stroke={tokens.surface} strokeWidth={2} />))}
        </g>
        {hover !== null && <line x1={px(hover)} x2={px(hover)} y1={T} y2={H - B} stroke={tokens.borderStrong} />}
        {rows.map((r, i) => <rect key={r.day} x={px(i) - step / 2} y={0} width={step} height={H} fill="transparent" onMouseEnter={() => setHover(i)} />)}
      </svg>
      {hover !== null && <Hover at={(px(hover) / W) * 100} title={shortDay(rows[hover].day)} rows={[{ label, value: v(rows[hover]) === null ? 'No results' : `${v(rows[hover])!.toFixed(1)}${unit}`, color }]} />}
    </Box>
  );
}

/** Part-to-whole in one bar, with the numbers beside it. */
export function SegmentBar({ parts, height = 14, legend = true }: { parts: { label: string; value: number; color: string }[]; height?: number; legend?: boolean }) {
  const total = parts.reduce((s, p) => s + p.value, 0);
  const shown = parts.filter((p) => p.value > 0);
  return (
    <Box>
      <Box sx={{ display: 'flex', gap: '2px', height, borderRadius: 1, overflow: 'hidden', bgcolor: total === 0 ? 'action.hover' : undefined }}>
        {shown.map((p) => (
          <Tooltip key={p.label} title={`${p.label}: ${p.value} (${((p.value / total) * 100).toFixed(1)}%)`}>
            <Box sx={{ flexGrow: p.value, flexBasis: 0, minWidth: 3, bgcolor: p.color }} />
          </Tooltip>
        ))}
      </Box>
      {legend && <Legend series={parts.map((p) => ({ label: p.label, color: p.color, value: p.value }))} />}
    </Box>
  );
}

/** Ranked horizontal bars. One series, so one color. */
export function RankBars({ rows, format }: { rows: { name: string; value: number }[]; format?: (v: number) => string }) {
  const { tokens } = useTheme();
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <Stack spacing={1}>
      {rows.map((r) => (
        <Tooltip key={r.name} title={`${r.name}: ${format ? format(r.value) : r.value}`} placement="top-start">
          <Box>
            <Stack direction="row" justifyContent="space-between" spacing={1}>
              <Typography variant="body2" noWrap color="text.secondary">{r.name}</Typography>
              <Typography variant="body2" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{format ? format(r.value) : r.value}</Typography>
            </Stack>
            <Box sx={{ height: 6, mt: 0.4, borderRadius: '0 3px 3px 0', width: `${(r.value / max) * 100}%`, minWidth: 3, bgcolor: tokens.series[0] }} />
          </Box>
        </Tooltip>
      ))}
    </Stack>
  );
}
