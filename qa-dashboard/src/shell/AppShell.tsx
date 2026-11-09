import { useEffect, useState, type ReactNode } from 'react';
import { Autocomplete, Avatar, Badge, Box, Breadcrumbs, Divider, IconButton, Link, List, ListItem, ListItemButton, ListItemIcon, ListItemText, Menu, MenuItem, Popover, Select, Stack, TextField, Tooltip, Typography, useMediaQuery } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import Dashboard from '@mui/icons-material/SpaceDashboardOutlined';
import Folder from '@mui/icons-material/FolderOutlined';
import Apps from '@mui/icons-material/LanOutlined';
import Layers from '@mui/icons-material/LayersOutlined';
import Checklist from '@mui/icons-material/ChecklistOutlined';
import Play from '@mui/icons-material/PlayCircleOutlined';
import Bug from '@mui/icons-material/BugReportOutlined';
import Image from '@mui/icons-material/PhotoLibraryOutlined';
import Assessment from '@mui/icons-material/AssessmentOutlined';
import Insights from '@mui/icons-material/InsightsOutlined';
import Group from '@mui/icons-material/GroupOutlined';
import Key from '@mui/icons-material/KeyOutlined';
import Login from '@mui/icons-material/LoginOutlined';
import Settings from '@mui/icons-material/SettingsOutlined';
import Notifications from '@mui/icons-material/NotificationsNoneOutlined';
import Menu2 from '@mui/icons-material/MenuOutlined';
import Search from '@mui/icons-material/SearchOutlined';
import { api, query, type Notifications as Feed, type SearchHit } from '../api';
import { ago, StatusChip } from '../design/ui';
import { useWorkspace } from './context';

interface Item { to: string; label: string; icon: ReactNode; show?: boolean }

const LABELS: Record<string, string> = {
  projects: 'Projects', applications: 'Applications & Environments', suites: 'Test Suites', cases: 'Test Cases', execution: 'Test Execution', defects: 'Defects',
  evidence: 'Evidence', reports: 'Reports', analytics: 'Analytics', users: 'Users & Roles', credentials: 'Credentials', 'login-profiles': 'Login Profiles', settings: 'Settings',
};

function Crumbs() {
  const { pathname } = useLocation();
  const parts = pathname.split('/').filter(Boolean);
  return (
    <Breadcrumbs aria-label="Breadcrumb" sx={{ fontSize: 13 }}>
      <Link component={RouterLink} to="/" color="text.secondary">Dashboard</Link>
      {parts.map((p, i) => {
        const to = `/${parts.slice(0, i + 1).join('/')}`;
        const label = LABELS[p] ?? 'Details';
        return i === parts.length - 1
          ? <Typography key={to} fontSize={13} color="text.primary">{label}</Typography>
          : <Link key={to} component={RouterLink} to={to} color="text.secondary">{label}</Link>;
      })}
    </Breadcrumbs>
  );
}

function GlobalSearch() {
  const [text, setText] = useState('');
  const [term, setTerm] = useState('');
  const navigate = useNavigate();
  const { selectProject } = useWorkspace();
  useEffect(() => { const t = setTimeout(() => setTerm(text), 220); return () => clearTimeout(t); }, [text]);
  const hits = useQuery({ queryKey: ['search', term], queryFn: () => api<SearchHit[]>(`/search${query({ q: term })}`), enabled: term.trim().length >= 2 });
  const go = (h: SearchHit) => {
    selectProject(h.projectId);
    navigate({ PROJECT: `/projects/${h.id}`, SUITE: `/suites/${h.id}`, TEST_CASE: `/cases/${h.id}`, DEFECT: `/defects/${h.id}` }[h.kind]);
    setText('');
  };
  return (
    <Autocomplete<SearchHit, false, false, true>
      freeSolo size="small" sx={{ width: { xs: 180, md: 300 } }} options={hits.data ?? []} filterOptions={(x) => x} inputValue={text} value={null} blurOnSelect
      groupBy={(o) => ({ PROJECT: 'Projects', SUITE: 'Suites', TEST_CASE: 'Test cases', DEFECT: 'Defects' }[o.kind])}
      getOptionLabel={(o) => (typeof o === 'string' ? o : o.title)} getOptionKey={(o) => (typeof o === 'string' ? o : o.id)}
      onInputChange={(_, v, reason) => { if (reason !== 'reset') setText(v); }}
      onChange={(_, v) => { if (v && typeof v !== 'string') go(v); }}
      noOptionsText="No matches" loading={hits.isFetching}
      renderInput={(p) => <TextField {...p} placeholder="Search projects, cases, defects" slotProps={{ htmlInput: { ...p.inputProps, 'aria-label': 'Search' }, input: { ...p.InputProps, startAdornment: <Search sx={{ fontSize: 17, color: 'text.secondary', mr: 0.5 }} /> } }} />}
    />
  );
}

function Bell() {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const client = useQueryClient();
  const navigate = useNavigate();
  const { selectProject } = useWorkspace();
  const feed = useQuery({ queryKey: ['notifications'], queryFn: () => api<Feed>('/notifications'), refetchInterval: 30000 });
  const seen = useMutation({ mutationFn: () => api('/notifications/seen', 'POST'), onSuccess: () => client.invalidateQueries({ queryKey: ['notifications'] }) });
  return (
    <>
      <Tooltip title="Notifications">
        {/* The name repeats what the badge shows, "9+" included, so voice control can match it (WCAG 2.5.3). */}
        <IconButton aria-label={`Notifications, ${badge(feed.data?.unread ?? 0)} unread`} onClick={(e) => { setAnchor(e.currentTarget); if (feed.data?.unread) seen.mutate(); }}>
          <Badge badgeContent={feed.data?.unread ?? 0} color="error" max={UNREAD_MAX}><Notifications fontSize="small" /></Badge>
        </IconButton>
      </Tooltip>
      <Popover open={!!anchor} anchorEl={anchor} onClose={() => setAnchor(null)} anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }} transformOrigin={{ vertical: 'top', horizontal: 'right' }}>
        <Box sx={{ width: 360, maxHeight: 420, overflow: 'auto' }}>
          <Typography variant="h3" sx={{ px: 2, py: 1.25 }}>Notifications</Typography>
          <Divider />
          {feed.data?.items.length === 0 && <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>Finished runs and high-severity defects appear here.</Typography>}
          <List dense disablePadding>
            {feed.data?.items.map((n) => (
              <ListItem key={n.kind + n.id} disablePadding>
                <ListItemButton onClick={() => { selectProject(n.projectId); setAnchor(null); navigate(n.kind === 'RUN' ? `/execution/${n.id}` : `/defects/${n.id}`); }}>
                  <Stack spacing={0.25} sx={{ minWidth: 0, width: '100%' }}>
                    <Stack direction="row" justifyContent="space-between"><StatusChip value={n.title} /><Typography variant="caption">{ago(n.at)}</Typography></Stack>
                    <Typography variant="body2" noWrap>{n.detail}</Typography>
                  </Stack>
                </ListItemButton>
              </ListItem>
            ))}
          </List>
        </Box>
      </Popover>
    </>
  );
}

const UNREAD_MAX = 9;
/** What the unread badge shows. */
export const badge = (unread: number) => (unread > UNREAD_MAX ? `${UNREAD_MAX}+` : String(unread));

/** Who is signed in, at the top of the account menu. Not an action, so the menu must not give it focus. */
function Identity({ email, organizationAdmin }: { email: string; organizationAdmin: boolean }) {
  return (
    <Box component="li" role="none" sx={{ px: 2, py: 0.75 }}>
      <Typography variant="body2" component="span" sx={{ display: 'block', fontWeight: 600 }}>{email}</Typography>
      <Typography variant="caption" component="span" sx={{ display: 'block' }}>{organizationAdmin ? 'Organization admin' : 'Member'}</Typography>
    </Box>
  );
}
Identity.muiSkipListHighlight = true;

function Profile({ onSignOut }: { onSignOut: () => void }) {
  const { me } = useWorkspace();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const navigate = useNavigate();
  return (
    <>
      <Tooltip title={me.email}>
        <IconButton aria-label="Account" onClick={(e) => setAnchor(e.currentTarget)}>
          <Avatar sx={{ width: 26, height: 26, fontSize: 12, bgcolor: 'primary.main' }}>{me.email[0].toUpperCase()}</Avatar>
        </IconButton>
      </Tooltip>
      <Menu open={!!anchor} anchorEl={anchor} onClose={() => setAnchor(null)}>
        <Identity email={me.email} organizationAdmin={me.organizationAdmin} />
        <Divider />
        <MenuItem onClick={() => { setAnchor(null); navigate('/settings'); }}>Settings</MenuItem>
        <MenuItem onClick={onSignOut}>Sign out</MenuItem>
      </Menu>
    </>
  );
}

export function AppShell({ children, onSignOut }: { children: ReactNode; onSignOut: () => void }) {
  const { tokens, breakpoints } = useTheme();
  const { me, projects, project, selectProject, allowed } = useWorkspace();
  const narrow = useMediaQuery(breakpoints.down('lg'));
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('qa.sidebar') === 'collapsed');
  const small = collapsed || narrow;
  const { pathname } = useLocation();
  const admin = me.organizationAdmin || allowed('PROJECT_ADMIN');
  const groups: { title: string; items: Item[] }[] = [
    { title: 'Overview', items: [{ to: '/', label: 'Dashboard', icon: <Dashboard /> }] },
    { title: 'Workspace', items: [
      { to: '/projects', label: 'Projects', icon: <Folder /> }, { to: '/applications', label: 'Applications & Environments', icon: <Apps /> },
      { to: '/suites', label: 'Test Suites', icon: <Layers /> }, { to: '/cases', label: 'Test Cases', icon: <Checklist /> }, { to: '/execution', label: 'Test Execution', icon: <Play /> },
    ] },
    { title: 'Quality', items: [
      { to: '/defects', label: 'Defects', icon: <Bug /> }, { to: '/evidence', label: 'Evidence', icon: <Image /> },
      { to: '/reports', label: 'Reports', icon: <Assessment /> }, { to: '/analytics', label: 'Analytics', icon: <Insights /> },
    ] },
    { title: 'Administration', items: [
      { to: '/users', label: 'Users & Roles', icon: <Group />, show: me.organizationAdmin }, { to: '/credentials', label: 'Credentials', icon: <Key />, show: admin },
      { to: '/login-profiles', label: 'Login Profiles', icon: <Login />, show: admin }, { to: '/settings', label: 'Settings', icon: <Settings /> },
    ] },
  ];
  const active = (to: string) => (to === '/' ? pathname === '/' : pathname === to || pathname.startsWith(`${to}/`));
  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <Box sx={{ width: small ? 60 : 272, flexShrink: 0, bgcolor: tokens.sidebar, transition: 'width .15s' }}>
      <Box component="nav" aria-label="Main" sx={{ color: tokens.sidebarText, position: 'sticky', top: 0, height: '100vh', overflowY: 'auto' }}>
        <Stack direction="row" alignItems="center" spacing={1} sx={{ px: 2, height: 54 }}>
          <Box aria-hidden sx={{ width: 26, height: 26, borderRadius: 1.5, bgcolor: tokens.accent, display: 'grid', placeItems: 'center', fontSize: 12, fontWeight: 700, color: '#fff', flexShrink: 0 }}>QA</Box>
          {!small && <Typography sx={{ fontWeight: 650, fontSize: 14.5 }}>QA Engine</Typography>}
        </Stack>
        {groups.map((g) => {
          const items = g.items.filter((i) => i.show !== false);
          if (!items.length) return null;
          return (
            <Box key={g.title} sx={{ mb: 1 }}>
              {!small && <Typography variant="overline" component="div" sx={{ px: 2, pt: 1, pb: 0.25, color: tokens.sidebarMuted }}>{g.title}</Typography>}
              <List dense disablePadding sx={{ px: 1 }}>
                {items.map((i) => (
                  <ListItem key={i.to} disablePadding>
                  <Tooltip title={small ? i.label : ''} placement="right">
                    <ListItemButton component={RouterLink} to={i.to} aria-label={i.label} aria-current={active(i.to) ? 'page' : undefined} selected={active(i.to)}
                      sx={{ borderRadius: 1.5, py: 0.6, px: 1.1, color: tokens.sidebarText, '&.Mui-selected, &.Mui-selected:hover': { bgcolor: tokens.sidebarActive }, '&:hover': { bgcolor: tokens.sidebarActive }, '&:focus-visible': { outline: '2px solid #fff' } }}>
                      <ListItemIcon sx={{ minWidth: small ? 0 : 30, color: active(i.to) ? '#fff' : tokens.sidebarMuted, '& svg': { fontSize: 19 } }}>{i.icon}</ListItemIcon>
                      {!small && <ListItemText primary={i.label} slotProps={{ primary: { noWrap: true, fontSize: 13, fontWeight: active(i.to) ? 600 : 450 } }} />}
                    </ListItemButton>
                  </Tooltip>
                  </ListItem>
                ))}
              </List>
            </Box>
          );
        })}
      </Box>
      </Box>
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Stack component="header" direction="row" alignItems="center" spacing={1.5} sx={{ height: 54, px: 2, bgcolor: 'background.paper', borderBottom: 1, borderColor: 'divider', position: 'sticky', top: 0, zIndex: 10 }}>
          {!narrow && <IconButton aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} onClick={() => { localStorage.setItem('qa.sidebar', collapsed ? 'open' : 'collapsed'); setCollapsed(!collapsed); }}><Menu2 fontSize="small" /></IconButton>}
          <Box sx={{ flexGrow: 1, minWidth: 0, display: { xs: 'none', sm: 'block' } }}><Crumbs /></Box>
          <Select aria-label="Project" value={project?.id ?? ''} displayEmpty onChange={(e) => selectProject(e.target.value)} sx={{ width: { xs: 140, md: 200 } }} inputProps={{ 'aria-label': 'Project', 'data-testid': 'project-selector' }}>
            {projects.length === 0 && <MenuItem value="">No projects</MenuItem>}
            {projects.map((p) => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
          </Select>
          <GlobalSearch />
          <Bell />
          <Profile onSignOut={onSignOut} />
        </Stack>
        <Box component="main" sx={{ p: { xs: 2, md: 3 }, maxWidth: 1480, mx: 'auto' }}>{children}</Box>
      </Box>
    </Box>
  );
}
