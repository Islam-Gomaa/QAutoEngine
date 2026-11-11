import { useState, type ReactNode } from 'react';
import { MenuItem, TextField } from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Empty } from '../design/ui';
import { useWorkspace } from '../shell/context';
import { Button } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';

/** Screens that belong to a project show this until there is one to work in. */
export function NeedsProject({ children }: { children: (projectId: string) => ReactNode }) {
  const { project, loading, me } = useWorkspace();
  if (loading) return null;
  if (!project) return <Empty title="No project selected" action={<Button component={RouterLink} to="/projects" variant="contained">Go to Projects</Button>}>{me.organizationAdmin ? 'Create a project to start.' : 'Ask an administrator to add you to a project.'}</Empty>;
  return <>{children(project.id)}</>;
}

export function Choice({ label, value, onChange, options, width, required }: { label: string; value: string; onChange: (v: string) => void; options: (string | [string, string])[]; width?: number; required?: boolean }) {
  return (
    <TextField select label={label} value={value} required={required} onChange={(e) => onChange(e.target.value)} sx={width ? { width } : undefined}
      slotProps={options.some((o) => o === '' || o[0] === '') ? { select: { displayEmpty: true }, inputLabel: { shrink: true } } : undefined}>
      {options.map((o) => { const [v, l] = typeof o === 'string' ? [o, o] : o; return <MenuItem key={v} value={v}>{l}</MenuItem>; })}
    </TextField>
  );
}

/** A dialog form: it remembers the failure, and refreshes the listed queries on success. */
export function useForm<T>(run: (values: T) => Promise<unknown>, refresh: string[][]) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const m = useMutation({ mutationFn: run, onSuccess: () => { setOpen(false); refresh.forEach((k) => client.invalidateQueries({ queryKey: k })); } });
  return { open, show: () => { m.reset(); setOpen(true); }, close: () => setOpen(false), submit: m.mutate, busy: m.isPending, error: m.error };
}


export const levelOptions: [string, string][] = [['LOW', 'Low'], ['MEDIUM', 'Medium'], ['HIGH', 'High'], ['CRITICAL', 'Critical']];
export const ROLES: [string, string][] = [['VIEWER', 'Viewer'], ['TESTER', 'Tester'], ['QA_LEAD', 'QA Lead'], ['PROJECT_ADMIN', 'Project Admin']];

/** The local fixture, the only place a run can go until the pre-pilot security gate passes. */
export const FIXTURE_URL = 'http://fixture.qa.internal:8090/';
/** Production is not offered in the product: it is READ_ONLY, needs explicit include paths, and stays locked. */
export const ENVIRONMENT_CLASSES: [string, string][] = [['LOCAL', 'Local'], ['DEV', 'Development'], ['QA', 'QA'], ['UAT', 'UAT'], ['STAGING', 'Staging']];
