import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: { sourcemap: false },
  server: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    // Desenvolvimento apenas; produção usa reverse proxy na mesma origem HTTPS.
    proxy: { '/auth': 'http://127.0.0.1:8080', '/v1': 'http://127.0.0.1:8080' },
  },
});
