import { defineConfig } from 'vite';

export default defineConfig({
    build: {
        lib: {
            entry: 'src/banner.js',
            name: 'CveilBanner',
            formats: ['iife'],
            fileName: () => 'cveil-banner.min.js'
        },
        outDir: 'dist',
        emptyOutDir: true,
        minify: 'esbuild'
    }
});
