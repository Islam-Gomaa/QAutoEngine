import type { ReactElement } from 'react';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import { buildTheme } from '../design/theme';

export interface Call { method: string; path: string; body: unknown }
type Handler = unknown | ((call: Call) => unknown);

/** Stands in for the server at the network edge. Unlisted routes fail the way a missing one would. */
export function server(routes: Record<string, Handler>) {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET';
    const path = String(url).replace('/api/v1', '');
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    const call = { method, path, body };
    calls.push(call);
    const key = `${method} ${path.split('?')[0]}`;
    if (!(key in routes)) return new Response(JSON.stringify({ detail: `No route ${key}` }), { status: 404 });
    const handler = routes[key];
    const value = typeof handler === 'function' ? (handler as (c: Call) => unknown)(call) : handler;
    if (value instanceof Response) return value;
    return new Response(value === undefined ? '' : JSON.stringify(value), { status: 200 });
  }));
  return calls;
}

export function mount(ui: ReactElement, path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ThemeProvider theme={buildTheme()}><CssBaseline /><MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter></ThemeProvider>
    </QueryClientProvider>,
  );
}

export const admin = { userId: 'u1', organizationId: 'o1', email: 'admin@qa.local', organizationAdmin: true };
export const member = { ...admin, email: 'viewer@qa.local', organizationAdmin: false };
export const project = (role: string | null = 'PROJECT_ADMIN') => ({ id: 'p1', name: 'Shop', description: '', archivedAt: null, myRole: role, testCases: 0, runs: 0, openDefects: 0 });
