import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// `npm run dev` serves the UI on :5173 and passes API and sign-in calls to the viewer on :18200
// (deploy/local/run-viewer.sh). Sign in once at http://localhost:5173/oauth2/authorization/viewer.
const backend = process.env.VIEWER_BACKEND ?? 'http://localhost:18200';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // No public source maps: the bundle is all that is served
    sourcemap: false,
    target: 'es2022',
  },
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/oauth2': backend,
      '/login': backend,
      '/logout': backend,
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
