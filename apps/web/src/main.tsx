import './styles/tokens.css';
import '@fontsource/lato/400.css';
import '@fontsource/lato/700.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { initTheme } from './ui/theme/theme';

// Tema resolvido antes do primeiro render (ADR-0010): evita pintar claro e trocar para escuro.
initTheme();
const root = document.getElementById('root');
if (!root) throw new Error('Raiz da aplicação não encontrada.');
createRoot(root).render(<StrictMode><App /></StrictMode>);
