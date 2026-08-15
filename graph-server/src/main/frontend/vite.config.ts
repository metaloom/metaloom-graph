// vitest's re-export of vite's own defineConfig, so the `test` block below is typed.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * The bundle is written straight into the jar's resource directory.
 *
 * `target/classes/webroot` is what `StaticHandler.create("webroot")` serves and what ends up inside the jar, so the
 * Maven build needs no copy step and `mvn package` cannot ship a stale UI. It is a build output, never a source
 * directory: nothing here is committed.
 *
 * Relative asset paths (`base: './'`) so the page works wherever it is mounted, including behind a path prefix.
 */
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: process.env.UI_OUT_DIR ?? '../../../target/classes/webroot',
    emptyOutDir: true,
    // The whole UI is one page; splitting it would only add round trips.
    chunkSizeWarningLimit: 900
  },
  server: {
    // `npm run dev` gives hot reload against a server started separately.
    proxy: {
      '/api': 'http://localhost:8080'
    }
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts']
  }
});
