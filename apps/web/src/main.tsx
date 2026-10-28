import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import '@fontsource/lato/400.css';
import '@fontsource/lato/700.css';

const root = document.getElementById('root');
if (!root) throw new Error('Raiz da aplicação não encontrada.');
createRoot(root).render(<StrictMode><App /></StrictMode>);
