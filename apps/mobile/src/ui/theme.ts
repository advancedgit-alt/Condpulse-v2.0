/**
 * Tokens do CondPulse no aplicativo, alinhados à web e ao kit de identidade (ADR-0010,
 * docs/identidade/kit-v1.0/handoff/design-tokens.json). Toques de no mínimo 48 px nas ações.
 * O app segue só o tema claro nesta fase; o escuro da web ainda não tem par validado no nativo.
 */
export const colors = {
  background: '#F4F7FA', surface: '#FFFFFF', navy: '#102A43', teal: '#007F86', text: '#102A43', muted: '#52667A',
  line: '#DCE5EC', error: '#B42318', errorBg: '#FFF0EE', infoBg: '#EAF1F5',
  /** Texto/ícone sobre o teal das ações (4,8:1). */
  onAction: '#FFFFFF',
  /** Link e texto de destaque sobre fundo claro: o teal puro mede 4,46:1 no fundo e não serve para texto. */
  link: '#00666D',
  accentSubtle: '#E8F4F3',
  successFg: '#17633D', successBg: '#EAF5EE',
  warningFg: '#8A4B08', warningBg: '#FFF4DF',
  neutralFg: '#52667A', neutralBg: '#EEF2F6',
};
export const touch = 48;
