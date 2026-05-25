import { context } from 'esbuild';
import { solidPlugin } from 'esbuild-plugin-solid';
import { createServer, request as httpRequest } from 'http';
import { readFileSync, writeFileSync, mkdirSync, existsSync, copyFileSync, readdirSync, rmSync, watch } from 'fs';
import { join, extname } from 'path';

const PREFERRED_PORT = 3001;
const API_TARGET = process.env.LOCALCLOUD_API_TARGET || 'http://127.0.0.1:8080';
const DIST = 'dist';

// --- Build static files ---
function buildStatic() {
    if (!existsSync(DIST)) mkdirSync(DIST);
    if (!existsSync(`${DIST}/icons`)) mkdirSync(`${DIST}/icons`);

    const cssFiles = ['src/styles/main.css', 'src/styles/layout.css', 'src/styles/components.css'];
    writeFileSync(`${DIST}/styles.css`, cssFiles.map(f => readFileSync(f, 'utf8')).join('\n'));

    const buildHash = Date.now().toString(36);
    let html = readFileSync('src/index.html', 'utf8');
    html = html.replace('/app.js', `/app.js?v=${buildHash}`);
    html = html.replace('/styles.css', `/styles.css?v=${buildHash}`);
    writeFileSync(`${DIST}/index.html`, html);

    if (existsSync('src/icons')) {
        readdirSync('src/icons').filter(f => f.endsWith('.svg')).forEach(f =>
            copyFileSync(`src/icons/${f}`, `${DIST}/icons/${f}`)
        );
    }
}

buildStatic();

// Watch CSS/HTML for changes
watch('src/styles', { recursive: true }, () => { buildStatic(); console.log('[dev] CSS rebuilt'); });
watch('src/index.html', () => { buildStatic(); console.log('[dev] HTML rebuilt'); });

// --- esbuild watch (JS/JSX) ---
const ctx = await context({
    entryPoints: ['src/app.jsx'],
    bundle: true,
    outfile: `${DIST}/app.js`,
    sourcemap: true,
    plugins: [solidPlugin()],
});
await ctx.watch();
console.log('[dev] Watching JSX...');

// --- HTTP server: serves dist/ + proxies /* to container ---
const MIME = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.map': 'application/json' };
const API_PREFIXES = [
    '/health', '/services', '/requests', '/env', '/reset', '/browse', '/mutate',
    '/projects', '/routing', '/credentials', '/query', '/schema', '/gcs',
    '/usage', '/query-history', '/workflow-env', '/workflow', '/sync',
    '/export', '/reseed',
];

const server = createServer((req, res) => {
    // Proxy API calls to LocalCloud container
    const pathname = req.url.split('?')[0];
    if (API_PREFIXES.some(prefix => pathname === prefix || pathname.startsWith(`${prefix}/`))) {
        const proxyReq = httpRequest(`${API_TARGET}${req.url}`, {
            method: req.method,
            headers: { ...req.headers, host: new URL(API_TARGET).host },
        }, (proxyRes) => {
            res.writeHead(proxyRes.statusCode, proxyRes.headers);
            proxyRes.pipe(res);
        });
        proxyReq.on('error', (e) => {
            res.writeHead(502, { 'Content-Type': 'text/plain' });
            res.end(`Proxy error: ${e.message}\nIs LocalCloud running on ${API_TARGET}?`);
        });
        req.pipe(proxyReq);
        return;
    }

    // Serve static files from dist/
    let filePath = join(DIST, req.url === '/' ? 'index.html' : req.url.split('?')[0]);
    try {
        const data = readFileSync(filePath);
        res.writeHead(200, { 'Content-Type': MIME[extname(filePath)] || 'application/octet-stream' });
        res.end(data);
    } catch {
        // SPA fallback
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(readFileSync(join(DIST, 'index.html')));
    }
});

// Find available port — test bind on both IPv4 and IPv6
import { createServer as netServer } from 'net';

function testPort(port) {
    return new Promise((resolve) => {
        const s = netServer();
        s.once('error', () => resolve(false));
        s.listen(port, '0.0.0.0', () => {
            s.close(() => {
                // Also check IPv6
                const s6 = netServer();
                s6.once('error', () => resolve(false));
                s6.listen(port, '::', () => { s6.close(() => resolve(true)); });
            });
        });
    });
}

async function findPort(start) {
    for (let p = start; p < start + 20; p++) {
        if (await testPort(p)) return p;
        console.log(`[dev] Port ${p} in use, trying ${p + 1}...`);
    }
    throw new Error('No available port found');
}

const actualPort = await findPort(PREFERRED_PORT);
server.listen(actualPort);
console.log(`[dev] http://localhost:${actualPort}`);
console.log(`[dev] API proxied to ${API_TARGET}`);
console.log(`[dev] Ctrl+C to stop`);
