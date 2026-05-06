<!-- gortex:communities:start -->
<!-- gortex:skills:start -->
## Community Skills

| Area | Description | Skill |
|------|-------------|-------|
| Get | 219 symbols | `/gortex-get` |
| Handle | 186 symbols | `/gortex-handle` |
| Build | 138 symbols | `/gortex-build` |
| Admin | 136 symbols | `/gortex-admin` |
| Stdlib | 80 symbols | `/gortex-stdlib` |
| Bigtablesql | 74 symbols | `/gortex-bigtablesql` |
| Expression | 64 symbols | `/gortex-expression` |
| Bigtablesql | 60 symbols | `/gortex-bigtablesql` |
| Engine | 52 symbols | `/gortex-engine` |
| Adapters | 40 symbols | `/gortex-adapters` |
| Engine | 37 symbols | `/gortex-engine` |
| Expression | 32 symbols | `/gortex-expression` |
| Test | 30 symbols | `/gortex-test` |
| Gateway | 30 symbols | `/gortex-gateway` |
| Adapters | 29 symbols | `/gortex-adapters` |
| Pages | 27 symbols | `/gortex-pages` |
| Engine | 27 symbols | `/gortex-engine` |
| Get | 27 symbols | `/gortex-get` |
| Gateway | 27 symbols | `/gortex-gateway` |
| Localcloud | 26 symbols | `/gortex-localcloud` |
<!-- gortex:skills:end -->

<!-- gortex:communities:end -->

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)
