import { build } from 'esbuild';
import { solidPlugin } from 'esbuild-plugin-solid';
import { readFileSync, writeFileSync, mkdirSync, existsSync, copyFileSync, readdirSync, rmSync } from 'fs';

const t0 = performance.now();

// Clean dist
if (existsSync('dist')) rmSync('dist', { recursive: true });
mkdirSync('dist');
mkdirSync('dist/icons');

// Run all tasks in parallel — CSS/HTML/icons are pure fs ops, JS is esbuild
const [jsResult] = await Promise.allSettled([
    // JS bundle (slowest — run in parallel with everything else)
    build({
        entryPoints: ['src/app.jsx'],
        bundle: true,
        outfile: 'dist/app.js',
        minify: true,
        plugins: [solidPlugin()],
    }),

    // CSS — concatenate in dependency order
    Promise.resolve().then(() => {
        const cssFiles = ['src/styles/main.css', 'src/styles/layout.css', 'src/styles/components.css'];
        writeFileSync('dist/styles.css', cssFiles.map(f => readFileSync(f, 'utf8')).join('\n'));
    }),

    // HTML
    Promise.resolve().then(() => {
        copyFileSync('src/index.html', 'dist/index.html');
    }),

    // Icons
    Promise.resolve().then(() => {
        if (existsSync('src/icons')) {
            readdirSync('src/icons').filter(f => f.endsWith('.svg')).forEach(f =>
                copyFileSync(`src/icons/${f}`, `dist/icons/${f}`)
            );
        }
    }),
]);

if (jsResult.status === 'rejected') {
    console.error('Build failed:', jsResult.reason?.message || jsResult.reason);
    process.exit(1);
}

console.log(`Build complete in ${(performance.now() - t0).toFixed(0)}ms`);
