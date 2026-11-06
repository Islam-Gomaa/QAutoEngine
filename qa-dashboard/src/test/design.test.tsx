import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material';
import type { UseQueryResult } from '@tanstack/react-query';
import { buildTheme } from '../design/theme';
import { ago, Async, bytes, Confirm, DataTable, percent, StatusChip, words } from '../design/ui';
import { ChartCard, SegmentBar, StackedDays } from '../design/charts';
import { can, query } from '../api';

const themed = (ui: React.ReactElement) => render(<ThemeProvider theme={buildTheme()}>{ui}</ThemeProvider>);
const state = (s: object) => s as UseQueryResult<string>;

describe('StatusChip', () => {
  it.each([['PASS', 'Passed'], ['FAIL', 'Failed'], ['BLOCKED', 'Blocked'], ['CRITICAL', 'Critical'], ['WONT_FIX', "Won't fix"]])('%s has a word and an icon', (value, label) => {
    const { container } = themed(<StatusChip value={value} />);
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(container.querySelector('svg')).not.toBeNull();
  });
  it('says None rather than rendering blank', () => { themed(<StatusChip value={null} />); expect(screen.getByText('None')).toBeInTheDocument(); });
});

describe('Async', () => {
  it('shows loading', () => { themed(<Async q={state({ isPending: true })}>{() => 'x'}</Async>); expect(screen.getByTestId('loading-state')).toBeInTheDocument(); });
  it('shows the failure and retries', async () => {
    const refetch = vi.fn();
    themed(<Async q={state({ isPending: false, isError: true, error: new Error('Boom'), refetch })}>{() => 'x'}</Async>);
    expect(screen.getByTestId('error-state')).toHaveTextContent('Boom');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });
  it('shows data', () => { themed(<Async q={state({ isPending: false, isError: false, data: 'loaded' })}>{(d) => d}</Async>); expect(screen.getByText('loaded')).toBeInTheDocument(); });
});

describe('DataTable', () => {
  const columns = [{ key: 'a', label: 'Name', sortable: true, render: (r: { a: string }) => r.a }];
  it('sorts through its header', async () => {
    const onSort = vi.fn();
    themed(<DataTable label="T" columns={columns} rows={[{ a: 'one' }]} rowKey={(r) => r.a} sort={{ key: 'a', order: 'asc' }} onSort={onSort} />);
    await userEvent.click(screen.getByRole('button', { name: 'Name' }));
    expect(onSort).toHaveBeenCalledWith('a');
  });
  it('pages only when there is more than a page', () => {
    const { rerender } = themed(<DataTable label="T" columns={columns} rows={[{ a: 'one' }]} rowKey={(r) => r.a} paging={{ page: 0, size: 25, total: 1, onPage: () => {} }} />);
    expect(screen.queryByRole('button', { name: /next page/i })).toBeNull();
    rerender(<ThemeProvider theme={buildTheme()}><DataTable label="T" columns={columns} rows={[{ a: 'one' }]} rowKey={(r) => r.a} paging={{ page: 0, size: 25, total: 40, onPage: () => {} }} /></ThemeProvider>);
    expect(screen.getByRole('button', { name: /next page/i })).toBeInTheDocument();
  });
});

describe('Confirm', () => {
  it('asks first, and keeps the dialog open with the failure', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error('Refused'));
    themed(<Confirm label="Archive" title="Archive it" body="Sure?" onConfirm={onConfirm} />);
    expect(onConfirm).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Archive' }));
    await userEvent.click(screen.getByRole('dialog').querySelector('button[type=submit]')!);
    expect(await screen.findByText('Refused')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('charts', () => {
  const rows = [{ day: '2026-11-01', passed: 3, failed: 1 }, { day: '2026-11-02', passed: 0, failed: 0 }];
  const series = [{ key: 'passed', label: 'Passed', color: '#000' }, { key: 'failed', label: 'Failed', color: '#111' }];
  it('has a legend for two series', () => { themed(<StackedDays rows={rows} series={series} label="Trend" />); expect(screen.getByRole('img', { name: 'Trend' })).toBeInTheDocument(); expect(screen.getByText('Passed')).toBeInTheDocument(); });
  it('does not draw an empty chart', () => { themed(<StackedDays rows={[{ day: '2026-11-01', passed: 0, failed: 0 }]} series={series} label="Trend" />); expect(screen.queryByRole('img')).toBeNull(); expect(screen.getByText('No results in this period')).toBeInTheDocument(); });
  it('offers the same data as a table', async () => {
    themed(<ChartCard title="Trend" columns={['Day', 'Passed']} rows={[['2026-11-01', 3]]}><div>plot</div></ChartCard>);
    await userEvent.click(screen.getByRole('button', { name: 'Show table: Trend' }));
    expect(screen.getByRole('table', { name: 'Trend' })).toHaveTextContent('2026-11-01');
    expect(screen.queryByText('plot')).toBeNull();
  });
  it('shows the values of a segment bar', () => { themed(<SegmentBar parts={[{ label: 'Passed', value: 7, color: '#000' }]} />); expect(screen.getByText('7')).toBeInTheDocument(); });
});

describe('helpers', () => {
  it('format values honestly', () => {
    expect(percent(null)).toBe('No results');
    expect(percent(66.666)).toBe('66.7%');
    expect(bytes(2048)).toBe('2.0 KB');
    expect(words('API_INFO_LEAK')).toBe('API info leak');
    expect(words('DOM_SNAPSHOT')).toBe('DOM snapshot');
    expect(ago(null)).toBe('Never');
  });
  it('rank roles the way the server does', () => {
    expect(can('QA_LEAD', 'TESTER')).toBe(true);
    expect(can('TESTER', 'QA_LEAD')).toBe(false);
    expect(can(null, 'VIEWER')).toBe(false);
  });
  it('leaves out empty query values', () => { expect(query({ a: '', b: null, c: 0, d: 'x y' })).toBe('?c=0&d=x+y'); });
});
