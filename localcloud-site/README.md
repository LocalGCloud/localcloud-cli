# LocalCloud Documentation Site

This is the source for the [LocalCloud](https://github.com/localcloud/localcloud) documentation and landing page, built with [VitePress](https://vitepress.dev/).

## Local Development

To run the site locally for development:

```bash
# Install dependencies
npm install

# Start development server
npm run docs:dev
```

The site will be available at `http://localhost:5173`.

## Deployment

This site is configured to automatically deploy to GitHub Pages via GitHub Actions when changes are pushed to the `main` branch.

The configuration can be found in `.github/workflows/deploy.yml`.
