import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, can, type Me, type Project, type Role } from '../api';

interface Workspace {
  me: Me;
  projects: Project[];
  loading: boolean;
  project: Project | null;
  selectProject: (id: string | null) => void;
  /** What to offer in the UI. The server applies the same rule to every request. */
  allowed: (needed: Role) => boolean;
}

const Context = createContext<Workspace | null>(null);
const KEY = 'qa.project';

export function WorkspaceProvider({ me, children }: { me: Me; children: ReactNode }) {
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api<Project[]>('/projects') });
  const [selected, setSelected] = useState<string | null>(() => localStorage.getItem(KEY));
  const list = useMemo(() => projects.data ?? [], [projects.data]);
  const project = list.find((p) => p.id === selected) ?? list[0] ?? null;
  useEffect(() => { if (selected) localStorage.setItem(KEY, selected); }, [selected]);
  const value: Workspace = {
    me, projects: list, loading: projects.isPending, project, selectProject: setSelected,
    allowed: (needed) => !!project && !project.archivedAt && can(project.myRole, needed),
  };
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useWorkspace(): Workspace {
  const value = useContext(Context);
  if (!value) throw new Error('Workspace is missing');
  return value;
}
