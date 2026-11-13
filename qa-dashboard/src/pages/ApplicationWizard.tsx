import { useEffect, useRef, useState } from 'react';
import { Alert, Box, Button, CircularProgress, FormControlLabel, Stack, Step, StepLabel, Stepper, Switch, TextField, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api, ApiError, type BaseUrlCheck, type Environment, type LoginProfile, type Target } from '../api';
import { Card, DataTable, Failure, KeyValue, Mono, PageHeader, Tag } from '../design/ui';
import { Choice, ENVIRONMENT_CLASSES, FIXTURE_URL, NeedsProject } from './shared';
import { blankFlow, blankMailbox, flowBody, flowProblems, LoginFlowFields, MailboxFields, mailboxBody, mailboxProblems, METHOD_LABELS, otpBody, TestResult, type FlowDraft, type MailboxDraft } from './signin';

/**
 * Guided setup for an application and its environments. Everything entered lives in this component's
 * state only, so moving between steps loses nothing and leaving the page keeps nothing. Passwords typed
 * on the sign-in step are sent once, with the save, and cleared from the page as soon as it succeeds.
 * The connection test comes after saving, because it runs on the saved configuration through a worker.
 */

const STEPS = ['Application details', 'Environments', 'Sign-in', 'Login flow', 'Review and save', 'Connection test'] as const;
const FLOW = 3;
const REVIEW = 4;
/** Reached only by saving: a test runs against the saved configuration, through a worker. */
const TEST = 5;

type SignIn = 'NONE' | 'PASSWORD' | 'PASSWORD_EMAIL_OTP';

interface Details { name: string; type: 'WEB' | 'API'; description: string; active: boolean }
interface EnvironmentDraft {
  key: number; name: string; classification: string; baseUrl: string; description: string; active: boolean; apiSpecPath: string;
  signIn: SignIn; profileName: string; username: string; password: string; signInEnabled: boolean; mailbox: MailboxDraft; flow: FlowDraft; codeFrom: 'MAILBOX' | 'PERSON';
}
type Errors = Record<string, string>;

const label = (classification: string) => ENVIRONMENT_CLASSES.find(([v]) => v === classification)?.[1] ?? classification;

/** A quick local check, so obvious mistakes are caught before asking the server. The server's rules are the real ones. */
function localUrlProblem(url: string): string | null {
  const value = url.trim();
  if (!value) return 'Enter the base URL.';
  if (!/^https?:\/\//i.test(value)) return 'Use a full http:// or https:// address.';
  return null;
}

function blankEnvironment(key: number, type: Details['type']): EnvironmentDraft {
  return { key, name: '', classification: 'QA', baseUrl: '', description: '', active: true, apiSpecPath: type === 'API' ? '/openapi.json' : '',
    signIn: 'NONE', profileName: 'Default', username: '', password: '', signInEnabled: true, mailbox: blankMailbox(), flow: blankFlow(), codeFrom: 'MAILBOX' };
}

/** Masks a username the way the server does, so the review shows what the credential list will show. */
function hint(username: string) {
  const chars = [...username.trim()];
  if (chars.length === 0) return '';
  return chars.length <= 3 ? '•••' : `${chars[0]}•••${chars[chars.length - 1]}`;
}

function StepHeading({ index, focus }: { index: number; focus: boolean }) {
  const ref = useRef<HTMLHeadingElement>(null);
  // Moving to a step puts keyboard and screen-reader focus on its heading, not back at the top of the page.
  useEffect(() => { if (focus) ref.current?.focus(); }, [focus, index]);
  return (
    <Box sx={{ mb: 2 }}>
      <Typography variant="caption" color="text.secondary">Step {index + 1} of {STEPS.length}</Typography>
      <Typography ref={ref} tabIndex={-1} variant="h2" component="h2" sx={{ fontSize: 18, fontWeight: 620, outline: 'none' }}>{STEPS[index]}</Typography>
    </Box>
  );
}

function ErrorSummary({ errors }: { errors: Errors }) {
  const count = Object.keys(errors).length;
  if (count === 0) return null;
  return <Alert severity="error" role="alert" sx={{ mb: 2 }}>{count === 1 ? 'Fix 1 field to continue.' : `Fix ${count} fields to continue.`}</Alert>;
}

function DetailsStep({ details, set, errors }: { details: Details; set: (d: Details) => void; errors: Errors }) {
  return (
    <Stack spacing={2} sx={{ maxWidth: 560 }}>
      <TextField label="Application name" required value={details.name} onChange={(e) => set({ ...details, name: e.target.value })}
        error={!!errors.name} helperText={errors.name} slotProps={{ htmlInput: { maxLength: 200 } }} />
      <Choice label="Application type" value={details.type} onChange={(x) => set({ ...details, type: x as Details['type'] })} options={[['WEB', 'Web'], ['API', 'API']]} />
      <Typography variant="caption">Mobile applications are not available yet.</Typography>
      <TextField label="Description" multiline minRows={2} value={details.description} onChange={(e) => set({ ...details, description: e.target.value })} />
      <FormControlLabel control={<Switch checked={details.active} onChange={(e) => set({ ...details, active: e.target.checked })} />} label="Active" />
      <Typography variant="caption" color="text.secondary" sx={{ mt: -1.5 }}>An inactive application is saved, and none of its environments can be run until it is activated.</Typography>
    </Stack>
  );
}

function EnvironmentsStep({ type, environments, set, errors, add }: { type: Details['type']; environments: EnvironmentDraft[]; set: (key: number, e: Partial<EnvironmentDraft>) => void; errors: Errors; add: () => void }) {
  return (
    <Stack spacing={2}>
      <Alert severity="info">Only the local fixture can be executed. Any other URL can be saved, and stays locked until the pre-pilot security gate passes. A base URL cannot be changed after saving.</Alert>
      {environments.map((e, i) => (
        <Box key={e.key} component="section" aria-labelledby={`environment-${e.key}`} sx={{ border: 1, borderColor: 'divider', borderRadius: 1.5, p: 2 }}>
          <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1.5 }}>
            <Typography id={`environment-${e.key}`} variant="h3" component="h3" sx={{ fontSize: 15, fontWeight: 600 }}>Environment {i + 1}</Typography>
            {environments.length > 1 && <Button color="error" onClick={() => set(e.key, { key: -1 })}>Remove environment {i + 1}</Button>}
          </Stack>
          <Stack spacing={2} sx={{ maxWidth: 560 }}>
            <TextField label="Environment name" required value={e.name} onChange={(x) => set(e.key, { name: x.target.value })} error={!!errors[`${e.key}.name`]} helperText={errors[`${e.key}.name`]} />
            <Choice label="Environment type" value={e.classification} onChange={(x) => set(e.key, { classification: x })} options={ENVIRONMENT_CLASSES} />
            <Stack direction="row" spacing={1} alignItems="flex-start">
              <TextField label="Base URL" required fullWidth value={e.baseUrl} onChange={(x) => set(e.key, { baseUrl: x.target.value })} error={!!errors[`${e.key}.baseUrl`]}
                helperText={errors[`${e.key}.baseUrl`] ?? 'For example https://staging.example.com/. No user name, password, query string, fragment or IP address.'} />
              <Button sx={{ flexShrink: 0, mt: 0.25 }} variant="outlined" onClick={() => set(e.key, { baseUrl: FIXTURE_URL })}>Use local fixture</Button>
            </Stack>
            {type === 'API' && <TextField label="OpenAPI path" value={e.apiSpecPath} onChange={(x) => set(e.key, { apiSpecPath: x.target.value })} helperText="Relative to the base URL." />}
            <TextField label="Description" value={e.description} onChange={(x) => set(e.key, { description: x.target.value })} />
            <FormControlLabel control={<Switch checked={e.active} onChange={(x) => set(e.key, { active: x.target.checked })} />} label="Active" />
          </Stack>
        </Box>
      ))}
      <Box><Button variant="outlined" onClick={add}>Add another environment</Button></Box>
      <Typography variant="caption" color="text.secondary">Production is not offered here. It stays read-only, needs explicit include paths, and cannot be executed.</Typography>
    </Stack>
  );
}

function SignInStep({ type, environments, set, errors }: { type: Details['type']; environments: EnvironmentDraft[]; set: (key: number, e: Partial<EnvironmentDraft>) => void; errors: Errors }) {
  if (type === 'API') return <Alert severity="info">API applications sign in with a token. Add it after saving, under Credentials and Login Profiles.</Alert>;
  return (
    <Stack spacing={2}>
      <Alert severity="info">Each environment gets its own credential, used for that environment only. Passwords are sealed when you save and cannot be shown again.</Alert>
      {environments.map((e) => (
        <Box key={e.key} component="section" aria-labelledby={`signin-${e.key}`} sx={{ border: 1, borderColor: 'divider', borderRadius: 1.5, p: 2 }}>
          <Typography id={`signin-${e.key}`} variant="h3" component="h3" sx={{ fontSize: 15, fontWeight: 600, mb: 1.5 }}>{e.name.trim()}</Typography>
          <Stack spacing={2} sx={{ maxWidth: 640 }}>
            <Choice label={`Sign-in for ${e.name.trim()}`} value={e.signIn} onChange={(x) => set(e.key, { signIn: x as SignIn })}
              options={[['NONE', 'None: test public pages only'], ['PASSWORD', METHOD_LABELS.PASSWORD], ['PASSWORD_EMAIL_OTP', METHOD_LABELS.PASSWORD_EMAIL_OTP]]} />
            {e.signIn !== 'NONE' && <>
              <TextField label="Profile name" value={e.profileName} onChange={(x) => set(e.key, { profileName: x.target.value })} />
              <TextField label="Username" required autoComplete="off" value={e.username} onChange={(x) => set(e.key, { username: x.target.value })}
                error={!!errors[`${e.key}.username`]} helperText={errors[`${e.key}.username`] ?? 'A dedicated test account, not a person\'s own account.'} />
              <TextField label="Password" type="password" required autoComplete="new-password" value={e.password} onChange={(x) => set(e.key, { password: x.target.value })}
                error={!!errors[`${e.key}.password`]} helperText={errors[`${e.key}.password`]} />
              <FormControlLabel control={<Switch checked={e.signInEnabled} onChange={(x) => set(e.key, { signInEnabled: x.target.checked })} />} label="Sign-in enabled" />
              {e.signIn === 'PASSWORD_EMAIL_OTP' && <>
                <Choice label={`Where the code comes from for ${e.name.trim()}`} value={e.codeFrom} onChange={(x) => set(e.key, { codeFrom: x as 'MAILBOX' | 'PERSON' })}
                  options={[['MAILBOX', 'A dedicated test mailbox'], ['PERSON', 'A person types it in while the run waits']]} />
                {e.codeFrom === 'MAILBOX'
                  ? <MailboxFields value={e.mailbox} set={(m) => set(e.key, { mailbox: m })} errors={errors} prefix={`${e.key}.mailbox.`} />
                  : <Alert severity="info">The run keeps its browser session open and asks for the code on its page, for up to 5 minutes.</Alert>}
              </>}
            </>}
          </Stack>
        </Box>
      ))}
      <Typography variant="caption" color="text.secondary">Where to sign in (the login flow) is set after saving, on the Login Profiles page. Runs that need sign-in wait until it is.</Typography>
    </Stack>
  );
}

function signInSummary(e: EnvironmentDraft) {
  if (e.signIn === 'NONE') return 'None';
  const who = hint(e.username);
  return `${METHOD_LABELS[e.signIn]}${who ? `, ${who}` : ''}${e.signInEnabled ? '' : ', disabled'}`;
}

function LoginFlowStep({ environments, set, errors }: { environments: EnvironmentDraft[]; set: (key: number, e: Partial<EnvironmentDraft>) => void; errors: Errors }) {
  const signing = environments.filter((e) => e.signIn !== 'NONE');
  if (signing.length === 0) return <Alert severity="info">No environment signs in, so there is no login flow to set.</Alert>;
  return (
    <Stack spacing={2}>
      {signing.map((e) => (
        <Box key={e.key} component="section" aria-labelledby={`flow-${e.key}`} sx={{ border: 1, borderColor: 'divider', borderRadius: 1.5, p: 2 }}>
          <Typography id={`flow-${e.key}`} variant="h3" component="h3" sx={{ fontSize: 15, fontWeight: 600, mb: 1.5 }}>{e.name.trim()}</Typography>
          <LoginFlowFields value={e.flow} set={(f) => set(e.key, { flow: f })} errors={errors} prefix={`${e.key}.flow.`} otp={e.signIn === 'PASSWORD_EMAIL_OTP'} />
        </Box>
      ))}
    </Stack>
  );
}

function flowSummary(e: EnvironmentDraft) {
  if (e.signIn === 'NONE') return 'None';
  const checks = [e.flow.successPath.trim() && `opens ${e.flow.successPath.trim()}`, e.flow.success.value.trim() && 'finds the signed-in marker'].filter(Boolean).join(' and ');
  return `Posts to ${e.flow.submitPath.trim()}; ${checks}`;
}

/** After saving: runs a connection test per environment and shows what it established. Locked targets cannot be tested. */
function TestStep({ project, targetId }: { project: string; targetId: string }) {
  const client = useQueryClient();
  const envs = useQuery({ queryKey: ['environments', project], queryFn: () => api<Environment[]>(`/projects/${project}/environments`) });
  const tests = useQuery({ queryKey: ['login-profiles', project], queryFn: () => api<LoginProfile[]>(`/projects/${project}/login-profiles`),
    refetchInterval: (q) => (q.state.data?.some((r) => r.lastTest && (r.lastTest.status === 'WAITING_FOR_OTP' || (!r.lastTest.status && ['QUEUED', 'RUNNING'].includes(r.lastTest.runStatus)))) ? 2000 : false) });
  const [error, setError] = useState<unknown>();
  const rows = (envs.data ?? []).filter((e) => e.targetId === targetId);
  const start = async (environmentId: string) => {
    setError(undefined);
    try {
      await api(`/projects/${project}/runs`, 'POST', { environmentId, kind: 'AUTH_TEST' }, { 'Idempotency-Key': crypto.randomUUID() });
      await client.invalidateQueries({ queryKey: ['login-profiles', project] });
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Stack spacing={2}>
      <Alert severity="success">The application is saved. A connection test opens each environment through the same safety gate as a run, and signs in where a sign-in is set up.</Alert>
      {error ? <Failure error={error} /> : null}
      <DataTable label="Connection tests" testId="connection-test" rows={rows} rowKey={(e) => e.id} columns={[
        { key: 'name', label: 'Environment', render: (e) => e.name },
        { key: 'url', label: 'Base URL', render: (e) => <Mono>{e.baseUrl}</Mono> },
        { key: 'signin', label: 'Sign-in', render: (e) => (e.loginMethod ? METHOD_LABELS[e.loginMethod] ?? e.loginMethod : 'None') },
        { key: 'result', label: 'Result', render: (e) => (e.executable ? <TestResult test={tests.data?.find((t) => t.environmentId === e.id)?.lastTest} /> : <Tag title="Real targets stay locked until the pre-pilot security gate passes.">Real target, locked</Tag>) },
        { key: 'x', label: '', align: 'right', render: (e) => {
          const last = tests.data?.find((t) => t.environmentId === e.id)?.lastTest;
          const running = !!last && (last.status === 'WAITING_FOR_OTP' || (!last.status && ['QUEUED', 'RUNNING'].includes(last.runStatus)));
          return e.executable ? <Button disabled={running} onClick={() => void start(e.id)}>{last ? 'Test again' : 'Run test'}</Button> : null;
        } },
      ]} />
    </Stack>
  );
}

function ReviewStep({ details, environments, checks }: { details: Details; environments: EnvironmentDraft[]; checks: Record<number, BaseUrlCheck> }) {
  return (
    <Stack spacing={2}>
      <Card title="Application">
        <KeyValue items={[['Name', details.name.trim()], ['Type', details.type === 'WEB' ? 'Web' : 'API'], ['Description', details.description.trim() || 'None'], ['Status', details.active ? 'Active' : 'Inactive']]} />
      </Card>
      <Card pad={false} title="Environments">
        <DataTable label="Environments to create" testId="review-environment" rows={environments} rowKey={(e) => String(e.key)} columns={[
          { key: 'name', label: 'Environment', render: (e) => <><Typography variant="body1" sx={{ fontWeight: 560 }}>{e.name.trim()}</Typography><Typography variant="caption">{label(e.classification)}</Typography></> },
          { key: 'url', label: 'Base URL, as it will be saved', render: (e) => <Mono>{checks[e.key]?.baseUrl ?? e.baseUrl}</Mono> },
          { key: 'run', label: 'Execution', render: (e) => (checks[e.key]?.executable ? <Tag>Local fixture</Tag> : <Tag title="Execution stays locked until the pre-pilot security gate passes.">Real target, locked</Tag>) },
          { key: 'policy', label: 'Policy', render: () => <Tag title="Reads only. Mutation is disabled.">Read-only</Tag> },
          { key: 'signin', label: 'Sign-in', render: (e) => signInSummary(e) },
          { key: 'flow', label: 'Login flow', render: (e) => flowSummary(e) },
          { key: 'status', label: 'Status', render: (e) => (e.active && details.active ? 'Active' : 'Inactive') },
        ]} />
      </Card>
      <Alert severity="info">Saving creates the application, its environments and their sign-in credentials together. If any part is refused, nothing is saved.</Alert>
    </Stack>
  );
}

function Wizard({ project }: { project: string }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [step, setStep] = useState(0);
  const [moved, setMoved] = useState(false);
  const [details, setDetails] = useState<Details>({ name: '', type: 'WEB', description: '', active: true });
  const [environments, setEnvironments] = useState<EnvironmentDraft[]>([blankEnvironment(1, 'WEB')]);
  const [nextKey, setNextKey] = useState(2);
  const [checks, setChecks] = useState<Record<number, BaseUrlCheck>>({});
  const [errors, setErrors] = useState<Errors>({});
  const [savedTarget, setSavedTarget] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<unknown>();

  const go = (to: number) => { setErrors({}); setSaveError(undefined); setStep(to); setMoved(true); };

  const setDetailsAndApiPaths = (d: Details) => {
    // The OpenAPI path only means something for an API application, so it follows the type.
    if (d.type !== details.type) setEnvironments((all) => all.map((e) => ({ ...e, apiSpecPath: d.type === 'API' ? e.apiSpecPath || '/openapi.json' : '' })));
    setDetails(d);
  };

  const setEnvironment = (key: number, change: Partial<EnvironmentDraft>) => {
    setEnvironments((all) => (change.key === -1 ? all.filter((e) => e.key !== key) : all.map((e) => (e.key === key ? { ...e, ...change } : e))));
    if (change.baseUrl !== undefined) setChecks((c) => { const next = { ...c }; delete next[key]; return next; });
  };

  const detailsErrors = (): Errors => (details.name.trim() ? {} : { name: 'Enter the application name.' });

  const environmentErrors = (): Errors => {
    const found: Errors = {};
    const names = new Map<string, number>();
    for (const e of environments) {
      const name = e.name.trim().toLowerCase();
      if (!name) found[`${e.key}.name`] = 'Enter the environment name.';
      else if (names.has(name)) found[`${e.key}.name`] = 'Each environment needs a different name.';
      names.set(name, e.key);
      const problem = localUrlProblem(e.baseUrl);
      if (problem) found[`${e.key}.baseUrl`] = problem;
    }
    return found;
  };

  const signInErrors = (): Errors => {
    const found: Errors = {};
    if (details.type === 'API') return found;
    for (const e of environments) {
      if (e.signIn === 'NONE') continue;
      if (!e.username.trim()) found[`${e.key}.username`] = 'Enter the username.';
      if (!e.password) found[`${e.key}.password`] = 'Enter the password.';
      if (e.signIn === 'PASSWORD_EMAIL_OTP' && e.codeFrom === 'MAILBOX') for (const [k, v] of Object.entries(mailboxProblems(e.mailbox))) found[`${e.key}.mailbox.${k}`] = v;
    }
    return found;
  };

  const next = async () => {
    if (step === 2) {
      const found = signInErrors();
      setErrors(found);
      if (Object.keys(found).length === 0) go(FLOW);
      return;
    }
    if (step === FLOW) {
      const found: Errors = {};
      for (const e of environments) {
        if (details.type !== 'WEB' || e.signIn === 'NONE') continue;
        for (const [k, v] of Object.entries(flowProblems(e.flow, e.signIn))) found[`${e.key}.flow.${k}`] = v;
      }
      setErrors(found);
      if (Object.keys(found).length === 0) go(REVIEW);
      return;
    }
    if (step === 0) {
      const found = detailsErrors();
      setErrors(found);
      if (Object.keys(found).length === 0) go(1);
      return;
    }
    const found = environmentErrors();
    if (Object.keys(found).length > 0) { setErrors(found); return; }
    setBusy(true);
    const confirmed: Record<number, BaseUrlCheck> = {};
    try {
      for (const e of environments) {
        try {
          confirmed[e.key] = await api<BaseUrlCheck>(`/projects/${project}/base-url-check`, 'POST', { baseUrl: e.baseUrl.trim() });
        } catch (error) {
          if (error instanceof ApiError && error.status === 409) found[`${e.key}.baseUrl`] = error.message;
          else throw error;
        }
      }
    } catch (error) {
      setSaveError(error);
      return;
    } finally {
      setBusy(false);
    }
    setChecks(confirmed);
    setErrors(found);
    if (Object.keys(found).length === 0) go(2);
  };

  const save = async () => {
    setBusy(true);
    setSaveError(undefined);
    try {
      const created = await api<Target>(`/projects/${project}/targets`, 'POST', {
        name: details.name.trim(), type: details.type, description: details.description.trim(), active: details.active,
        environments: environments.map((e) => ({
          name: e.name.trim(), classification: e.classification, baseUrl: checks[e.key]?.baseUrl ?? e.baseUrl.trim(), description: e.description.trim(),
          active: e.active, channel: details.type, apiSpecPath: details.type === 'API' && e.apiSpecPath.trim() ? e.apiSpecPath.trim() : null,
          ...(details.type === 'WEB' && e.signIn !== 'NONE' ? { authentication: {
            name: e.profileName.trim() || 'Default', method: e.signIn, username: e.username.trim(), password: e.password, enabled: e.signInEnabled,
            mailbox: e.signIn === 'PASSWORD_EMAIL_OTP' && e.codeFrom === 'MAILBOX' ? mailboxBody(e.mailbox) : null, ...flowBody(e.flow),
            otp: e.signIn === 'PASSWORD_EMAIL_OTP' ? { ...otpBody(e.flow), typedByPerson: e.codeFrom === 'PERSON' } : null,
          } } : {}),
        })),
      });
      await Promise.all([client.invalidateQueries({ queryKey: ['targets', project] }), client.invalidateQueries({ queryKey: ['environments', project] })]);
      // The passwords have done their job. They are not kept in the page any longer than the save.
      setEnvironments((all) => all.map((e) => ({ ...e, password: '', mailbox: { ...e.mailbox, password: '' } })));
      setSavedTarget(created.id);
      go(TEST);
    } catch (error) {
      setSaveError(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Set up application" description="Describe the application, where it runs and how runs sign in, then test the connection. Nothing reads as connected until a test has passed." />
      <Stepper activeStep={step} sx={{ mb: 3 }}>
        {STEPS.map((s, i) => <Step key={s} completed={i < step}><StepLabel>{s}</StepLabel></Step>)}
      </Stepper>
      <Card>
        <form onSubmit={(e) => { e.preventDefault(); if (step === TEST) navigate('/applications'); else void (step === REVIEW ? save() : next()); }} noValidate>
          <StepHeading index={step} focus={moved} />
          <ErrorSummary errors={errors} />
          {saveError ? <Box sx={{ mb: 2 }}><Failure error={saveError} /></Box> : null}
          {step === 0 && <DetailsStep details={details} set={setDetailsAndApiPaths} errors={errors} />}
          {step === 1 && <EnvironmentsStep type={details.type} environments={environments} set={setEnvironment} errors={errors}
            add={() => { setEnvironments((all) => [...all, blankEnvironment(nextKey, details.type)]); setNextKey(nextKey + 1); }} />}
          {step === 2 && <SignInStep type={details.type} environments={environments} set={setEnvironment} errors={errors} />}
          {step === FLOW && (details.type === 'WEB' ? <LoginFlowStep environments={environments} set={setEnvironment} errors={errors} /> : <Alert severity="info">API applications have no login form.</Alert>)}
          {step === REVIEW && <ReviewStep details={details} environments={environments} checks={checks} />}
          {step === TEST && savedTarget && <TestStep project={project} targetId={savedTarget} />}
          <Stack direction="row" spacing={1} justifyContent="space-between" sx={{ mt: 3 }}>
            {step === TEST ? <span /> : <Button onClick={() => navigate('/applications')}>Cancel</Button>}
            <Stack direction="row" spacing={1}>
              {step > 0 && step !== TEST && <Button variant="outlined" disabled={busy} onClick={() => go(step - 1)}>Back</Button>}
              <Button type="submit" variant="contained" disabled={busy} startIcon={busy ? <CircularProgress size={13} color="inherit" /> : undefined}>
                {step === REVIEW ? 'Save application' : step === TEST ? 'Finish' : 'Next'}
              </Button>
            </Stack>
          </Stack>
        </form>
      </Card>
    </>
  );
}

export const ApplicationWizardPage = () => <NeedsProject>{(p) => <Wizard project={p} />}</NeedsProject>;
