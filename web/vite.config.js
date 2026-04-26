import { defineConfig } from 'vite';

const trailingSlashRedirect = {
    name: 'trailing-slash-redirect',
    configureServer(server) {
        server.middlewares.use((req, res, next) => {
            if (req.url === '/privacy' || req.url === '/terms') {
                res.writeHead(301, { Location: req.url + '/' });
                res.end();
                return;
            }
            next();
        });
    }
};

export default defineConfig({
    plugins: [trailingSlashRedirect],
    root: 'src/static',
    build: {
        outDir: '../../build/dist',
        emptyOutDir: true,
        cssCodeSplit: false,
        rollupOptions: {
            input: {
                main: 'src/static/index.html',
                privacy: 'src/static/privacy/index.html',
                terms: 'src/static/terms/index.html',
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
