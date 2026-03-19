import { defineConfig } from 'vite';
import { resolve } from 'path';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [tailwindcss()],
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
        assetFileNames: 'cveil-web.[ext]',
      },
    },
    outDir: 'dist',
    emptyOutDir: true,
    cssCodeSplit: false,
  },
});
