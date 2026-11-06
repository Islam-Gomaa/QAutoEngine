import { createTheme } from '@mui/material/styles';
import { light, type Tokens } from './tokens';

declare module '@mui/material/styles' {
  interface Theme { tokens: Tokens }
  interface ThemeOptions { tokens?: Tokens }
}

const font = 'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';

export function buildTheme(t: Tokens = light) {
  return createTheme({
    tokens: t,
    palette: {
      mode: t.mode,
      primary: { main: t.accent },
      error: { main: t.status.critical.mark },
      warning: { main: t.status.warning.mark },
      success: { main: t.status.good.mark },
      background: { default: t.canvas, paper: t.surface },
      text: { primary: t.text, secondary: t.textSecondary },
      divider: t.border,
    },
    shape: { borderRadius: 8 },
    typography: {
      fontFamily: font,
      fontSize: 13.5,
      h1: { fontSize: 22, fontWeight: 650, letterSpacing: '-0.01em', lineHeight: 1.25 },
      h2: { fontSize: 15, fontWeight: 620, lineHeight: 1.35 },
      h3: { fontSize: 13.5, fontWeight: 620 },
      body1: { fontSize: 13.5 },
      body2: { fontSize: 12.5 },
      caption: { fontSize: 11.5, color: t.textMuted },
      overline: { fontSize: 10.5, fontWeight: 650, letterSpacing: '0.08em', lineHeight: 1.4 },
      button: { textTransform: 'none', fontWeight: 580, fontSize: 13 },
    },
    components: {
      MuiButton: {
        defaultProps: { disableElevation: true, size: 'small' },
        styleOverrides: { root: { borderRadius: 7, paddingInline: 12, minHeight: 32 }, outlined: { borderColor: t.borderStrong, color: t.text } },
      },
      MuiIconButton: { defaultProps: { size: 'small' } },
      MuiPaper: { defaultProps: { elevation: 0 }, styleOverrides: { root: { backgroundImage: 'none' } } },
      MuiTextField: { defaultProps: { size: 'small', fullWidth: true } },
      MuiSelect: { defaultProps: { size: 'small' } },
      MuiOutlinedInput: { styleOverrides: { root: { borderRadius: 7 }, notchedOutline: { borderColor: t.borderStrong } } },
      MuiTableCell: {
        styleOverrides: {
          root: { borderColor: t.border, padding: '9px 14px', fontSize: 13 },
          head: { fontSize: 11.5, fontWeight: 620, color: t.textSecondary, background: t.surface, whiteSpace: 'nowrap' },
        },
      },
      MuiTableRow: { styleOverrides: { root: { '&:last-child td': { borderBottom: 0 } } } },
      MuiTabs: { styleOverrides: { root: { minHeight: 38, borderBottom: `1px solid ${t.border}` } } },
      MuiTab: { styleOverrides: { root: { minHeight: 38, paddingInline: 12, minWidth: 0 } } },
      MuiDialog: { defaultProps: { fullWidth: true, maxWidth: 'sm' } },
      MuiTooltip: { defaultProps: { arrow: true } },
      MuiChip: { styleOverrides: { root: { borderRadius: 6, height: 22, fontSize: 11.5, fontWeight: 580 } } },
      MuiLink: { defaultProps: { underline: 'hover' } },
      MuiCssBaseline: {
        styleOverrides: {
          body: { WebkitFontSmoothing: 'antialiased' },
          ':focus-visible': { outline: `2px solid ${t.accent}`, outlineOffset: 1 },
          '@media (prefers-reduced-motion: reduce)': { '*': { transition: 'none !important', animation: 'none !important' } },
        },
      },
    },
  });
}
