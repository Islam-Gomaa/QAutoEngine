/**
 * Design tokens. Components read these through the MUI theme, so a dark set can be added here
 * without touching a page. Chart colors come from the validated reference palette.
 */
export interface Tokens {
  mode: 'light' | 'dark';
  surface: string; canvas: string; sunken: string; border: string; borderStrong: string;
  text: string; textSecondary: string; textMuted: string;
  accent: string; accentSoft: string;
  sidebar: string; sidebarText: string; sidebarMuted: string; sidebarActive: string;
  status: Record<'good' | 'warning' | 'serious' | 'critical' | 'neutral' | 'info', { fg: string; bg: string; mark: string }>;
  series: string[];
  grid: string;
}

export const light: Tokens = {
  mode: 'light',
  surface: '#ffffff', canvas: '#f6f7f9', sunken: '#f0f2f5', border: '#e3e6eb', borderStrong: '#cdd2da',
  text: '#14171c', textSecondary: '#4c5462', textMuted: '#6b7382',
  accent: '#1f5fbf', accentSoft: '#e9f0fb',
  sidebar: '#10151d', sidebarText: '#e6e9ef', sidebarMuted: '#8f98a8', sidebarActive: '#1f2937',
  status: {
    good: { fg: '#0d6b3d', bg: '#e6f4ec', mark: '#1a9a5c' },
    warning: { fg: '#7a5200', bg: '#fbf1da', mark: '#d99a00' },
    serious: { fg: '#a13e0b', bg: '#fcebe0', mark: '#e0702c' },
    critical: { fg: '#ab1f24', bg: '#fbe6e6', mark: '#d9363a' },
    neutral: { fg: '#4c5462', bg: '#eef0f3', mark: '#8d95a3' },
    info: { fg: '#1f5fbf', bg: '#e9f0fb', mark: '#2a78d6' },
  },
  series: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100'],
  grid: '#eceef2',
};
