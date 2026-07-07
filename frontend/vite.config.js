import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Proxy /api to the Spring Boot backend so the browser makes same-origin calls (no CORS).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
