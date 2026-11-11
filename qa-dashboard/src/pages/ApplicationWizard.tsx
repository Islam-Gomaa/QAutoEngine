import { useEffect, useRef, useState } from 'react';
import { Alert, Box, Button, CircularProgress, FormControlLabel, Stack, Step, StepLabel, Stepper, Switch, TextField, Typography } from '@mui/material';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api, ApiError, type BaseUrlCheck, type Target } from '../api';
import { Card, DataTable, Failure, KeyValue, Mono, PageHeader, Tag } from '../design/ui';
import { Choice, ENVIRONMENT_CLASSES, FIXTURE_URL, NeedsProject } from './shared';

/**
 * Guided setup for an application and its environments. Everything entered lives in this component's
 * state only, so moving between steps loses nothing and leaving the page keeps nothing. No secret is
 * asked for here; later steps (authentication, login flow, connection test) belong to later milestones.
 */

const STEPS = ['Application details', 'Environments', 'Review and save'] as const;

interface Details { name: string; type: 'WEB' | 'API'; description: string; active: boolean }
interface EnvironmentDraft { key: number; name: string; classification: string; baseUrl: string; description: string; active: boolean; apiSpecPath: string }
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
  return { key, name: '', classification: 'QA', baseUrl: '', description: '', active: true, apiSpecPath: type === 'API' ? '/openapi.json' : '' };
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
          { key: 'status', label: 'Status', render: (e) => (e.active && details.active ? 'Active' : 'Inactive') },
        ]} />
      </Card>
      <Alert severity="info">Saving creates the application and all its environments together. If any part is refused, nothing is saved.</Alert>
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

  const next = async () => {
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
      await api<Target>(`/projects/${project}/targets`, 'POST', {
        name: details.name.trim(), type: details.type, description: details.description.trim(), active: details.active,
        environments: environments.map((e) => ({
          name: e.name.trim(), classification: e.classification, baseUrl: checks[e.key]?.baseUrl ?? e.baseUrl.trim(), description: e.description.trim(),
          active: e.active, channel: details.type, apiSpecPath: details.type === 'API' && e.apiSpecPath.trim() ? e.apiSpecPath.trim() : null,
        })),
      });
      await Promise.all([client.invalidateQueries({ queryKey: ['targets', project] }), client.invalidateQueries({ queryKey: ['environments', project] })]);
      navigate('/applications');
    } catch (error) {
      setSaveError(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Set up application" description="Describe the application and where it runs. Authentication and connection tests are added in a later release." />
      <Stepper activeStep={step} sx={{ mb: 3 }}>
        {STEPS.map((s, i) => <Step key={s} completed={i < step}><StepLabel>{s}</StepLabel></Step>)}
      </Stepper>
      <Card>
        <form onSubmit={(e) => { e.preventDefault(); void (step === 2 ? save() : next()); }} noValidate>
          <StepHeading index={step} focus={moved} />
          <ErrorSummary errors={errors} />
          {saveError ? <Box sx={{ mb: 2 }}><Failure error={saveError} /></Box> : null}
          {step === 0 && <DetailsStep details={details} set={setDetailsAndApiPaths} errors={errors} />}
          {step === 1 && <EnvironmentsStep type={details.type} environments={environments} set={setEnvironment} errors={errors}
            add={() => { setEnvironments((all) => [...all, blankEnvironment(nextKey, details.type)]); setNextKey(nextKey + 1); }} />}
          {step === 2 && <ReviewStep details={details} environments={environments} checks={checks} />}
          <Stack direction="row" spacing={1} justifyContent="space-between" sx={{ mt: 3 }}>
            <Button onClick={() => navigate('/applications')}>Cancel</Button>
            <Stack direction="row" spacing={1}>
              {step > 0 && <Button variant="outlined" disabled={busy} onClick={() => go(step - 1)}>Back</Button>}
              <Button type="submit" variant="contained" disabled={busy} startIcon={busy ? <CircularProgress size={13} color="inherit" /> : undefined}>
                {step === 2 ? 'Save application' : 'Next'}
              </Button>
            </Stack>
          </Stack>
        </form>
      </Card>
    </>
  );
}

export const ApplicationWizardPage = () => <NeedsProject>{(p) => <Wizard project={p} />}</NeedsProject>;
