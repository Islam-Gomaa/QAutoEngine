import { useCallback, useEffect, useState } from 'react';
import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { api, ApiError, SESSION_ENDED, type Me } from './api';
import { Loading } from './design/ui';
import { AppShell } from './shell/AppShell';
import { WorkspaceProvider, useWorkspace } from './shell/context';
import { DashboardPage } from './pages/Dashboard';
import { ProjectDetailPage, ProjectsPage } from './pages/Projects';
import { ApplicationsPage } from './pages/Applications';
import { ApplicationWizardPage } from './pages/ApplicationWizard';
import { SuiteDetailPage, SuitesPage } from './pages/Suites';
import { CaseDetailPage, CasesPage } from './pages/Cases';
import { ExecutionPage, RunDetailPage } from './pages/Execution';
import { DefectDetailPage, DefectsPage } from './pages/Defects';
import { EvidencePage } from './pages/Evidence';
import { AnalyticsPage, ReportsPage } from './pages/Reports';
import { CredentialsPage, LoginProfilesPage, SettingsPage, UsersPage } from './pages/Admin';

function SignIn({ onDone }: { onDone: () => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totp, setTotp] = useState('');
  const [error, setError] = useState('');
  const submit = async () => {
    setError('');
    try {
      await api('/auth/csrf');
      await api('/auth/login', 'POST', { email, password, totp: totp || null });
      onDone();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 429 ? 'Too many attempts. Try again later.' : 'Sign-in failed.');
    }
  };
  return (
    <Stack alignItems="center" justifyContent="center" sx={{ minHeight: '100vh', p: 2 }}>
      <Box component="form" onSubmit={(e) => { e.preventDefault(); void submit(); }} sx={{ width: 360, bgcolor: 'background.paper', border: 1, borderColor: 'divider', borderRadius: 2, p: 3.5 }}>
        <Stack spacing={2}>
          <Box>
            <Typography variant="h1" component="h1">QA Engine</Typography>
            <Typography color="text.secondary" sx={{ mt: 0.5 }}>Sign in to your workspace</Typography>
          </Box>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField label="Email" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
          <TextField label="Password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
          <TextField label="Authenticator code (if enabled)" autoComplete="one-time-code" value={totp} onChange={(e) => setTotp(e.target.value)} />
          <Button type="submit" variant="contained" size="medium">Sign in</Button>
        </Stack>
      </Box>
    </Stack>
  );
}

function Guarded({ when, children }: { when: (w: ReturnType<typeof useWorkspace>) => boolean; children: React.ReactNode }) {
  const w = useWorkspace();
  if (w.loading) return <Loading />;
  return when(w) ? <>{children}</> : <Navigate to="/" replace />;
}

function Pages() {
  const projectAdmin = (w: ReturnType<typeof useWorkspace>) => w.me.organizationAdmin || w.allowed('PROJECT_ADMIN');
  return (
    <Routes>
      <Route path="/" element={<DashboardPage />} />
      <Route path="/projects" element={<ProjectsPage />} />
      <Route path="/projects/:id" element={<ProjectDetailPage />} />
      <Route path="/applications" element={<ApplicationsPage />} />
      <Route path="/applications/new" element={<Guarded when={projectAdmin}><ApplicationWizardPage /></Guarded>} />
      <Route path="/suites" element={<SuitesPage />} />
      <Route path="/suites/:id" element={<SuiteDetailPage />} />
      <Route path="/cases" element={<CasesPage />} />
      <Route path="/cases/:id" element={<CaseDetailPage />} />
      <Route path="/execution" element={<ExecutionPage />} />
      <Route path="/execution/:id" element={<RunDetailPage />} />
      <Route path="/defects" element={<DefectsPage />} />
      <Route path="/defects/:id" element={<DefectDetailPage />} />
      <Route path="/evidence" element={<EvidencePage />} />
      <Route path="/reports" element={<ReportsPage />} />
      <Route path="/analytics" element={<AnalyticsPage />} />
      <Route path="/users" element={<Guarded when={(w) => w.me.organizationAdmin}><UsersPage /></Guarded>} />
      <Route path="/credentials" element={<Guarded when={projectAdmin}><CredentialsPage /></Guarded>} />
      <Route path="/login-profiles" element={<Guarded when={projectAdmin}><LoginProfilesPage /></Guarded>} />
      <Route path="/settings" element={<SettingsPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export function App() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const me = useQuery<Me | null>({ queryKey: ['me'], queryFn: () => api<Me>('/auth/me'), retry: false });
  // Nothing from one account may survive into the next, except the sign-in state itself.
  const forget = useCallback(() => client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'me' }), [client]);
  const signedOut = useCallback(() => { forget(); client.setQueryData(['me'], null); navigate('/'); }, [client, forget, navigate]);
  useEffect(() => { window.addEventListener(SESSION_ENDED, signedOut); return () => window.removeEventListener(SESSION_ENDED, signedOut); }, [signedOut]);
  const signOut = useMutation({ mutationFn: () => api('/auth/logout', 'POST'), onSettled: signedOut });
  if (me.isPending) return <Box sx={{ p: 4 }}><Loading /></Box>;
  if (!me.data) return <SignIn onDone={() => { forget(); void me.refetch(); }} />;
  return (
    <WorkspaceProvider me={me.data}>
      <AppShell onSignOut={() => signOut.mutate()}><Pages /></AppShell>
    </WorkspaceProvider>
  );
}
