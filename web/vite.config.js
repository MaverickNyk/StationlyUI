import { defineConfig } from 'vite';

const trailingSlashRedirect = {
    name: 'trailing-slash-redirect',
    configureServer(server) {
        server.middlewares.use((req, res, next) => {
            if (req.url === '/privacy' || req.url === '/terms' || req.url === '/mobile/app/android') {
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
                androidRedirect: 'src/static/mobile/app/android/index.html',
            },
            output: {
                // Content-hashed JS so a new deploy never serves stale cached code.
                entryFileNames: 'js/[name]-[hash].js',
                chunkFileNames: 'js/[name]-[hash].js',
                // Hash CSS + content images so changes cache-bust under the server's
                // immutable asset caching. Keep brand/icon files (logo, favicon) on
                // STABLE names because the web manifest references them by fixed path.
                assetFileNames: (assetInfo) => {
                    const name = assetInfo.name || '';
                    if (/stationly_logo|favicon/.test(name)) {
                        return 'assets/[name][extname]';
                    }
                    return 'assets/[name]-[hash][extname]';
                },
            }
        }
    },
    server: {
        port: 3000,
        open: true
    }
});
