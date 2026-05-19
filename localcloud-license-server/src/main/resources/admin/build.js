import * as esbuild from 'esbuild';
import { solidPlugin } from 'esbuild-plugin-solid';
import { readFileSync, writeFileSync, cpSync, rmSync, mkdirSync, existsSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const src = join(__dirname, 'src');
const dist = join(__dirname, 'dist');

// Clean
if (existsSync(dist)) rmSync(dist, { recursive: true });
mkdirSync(dist);

// Build JS bundle
await esbuild.build({
  entryPoints: [join(src, 'app.jsx')],
  bundle: true,
  minify: true,
  outfile: join(dist, 'app.js'),
  plugins: [solidPlugin()],
});

// Copy HTML with cache busting
let html = readFileSync(join(src, 'index.html'), 'utf-8');
const ts = Date.now().toString(36);
html = html.replace(/\?v=__VERSION__/g, `?v=${ts}`);
writeFileSync(join(dist, 'index.html'), html);

// Copy styles
if (existsSync(join(src, 'styles', 'admin.css'))) {
  cpSync(join(src, 'styles', 'admin.css'), join(dist, 'admin.css'));
}

console.log('Admin UI built →', dist);
