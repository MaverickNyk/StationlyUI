import { defineConfig } from 'vite';

export default defineConfig({
    root: 'src/static',
    build: {
        outDir: '../../build/dist',
        emptyOutDir: true,
        cssCodeSplit: false,
        rollupOptions: {
            input: {
                main: 'src/static/index.html',
            },
            output: {
                entryFileNames: 'js/[name].bundle.js',
                assetFileNames: 'assets/[name].[ext]',
            }
        }
    },
    server: {
        port: 8080,
        open: true
    }
});
