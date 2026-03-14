import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.js'),
      name: 'CveilWeb',
      formats: ['iife'],
      fileName: () => 'cveil-web.iife.js',
    },
    rollupOptions: {
      external: ['d3'],
      output: {
        globals: { d3: 'd3' },
      },
    },
    outDir: 'dist',
    emptyOutDir: true,
  },
});
