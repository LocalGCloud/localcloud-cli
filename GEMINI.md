<!-- gortex:communities:start -->
<!-- gortex:skills:start -->
## Community Skills

| Area | Description | Skill |
|------|-------------|-------|
| Get | 257 symbols | `/gortex-get` |
| Get | 199 symbols | `/gortex-get` |
| Seed | 196 symbols | `/gortex-seed` |
| Get | 182 symbols | `/gortex-get` |
| List | 141 symbols | `/gortex-list` |
| Stdlib | 80 symbols | `/gortex-stdlib` |
| Licensing | 75 symbols | `/gortex-licensing` |
| Bigtablesql | 74 symbols | `/gortex-bigtablesql` |
| Expression | 64 symbols | `/gortex-expression` |
| Generate | 60 symbols | `/gortex-generate` |
| Adapters | 49 symbols | `/gortex-adapters` |
| Build | 46 symbols | `/gortex-build` |
| Bigtablesql | 45 symbols | `/gortex-bigtablesql` |
| Pages | 45 symbols | `/gortex-pages` |
| Services | 45 symbols | `/gortex-services` |
| Gateway | 44 symbols | `/gortex-gateway` |
| Engine | 43 symbols | `/gortex-engine` |
| Localcloud | 32 symbols | `/gortex-localcloud` |
| Expression | 32 symbols | `/gortex-expression` |
| Get | 32 symbols | `/gortex-get` |
<!-- gortex:skills:end -->

<!-- gortex:communities:end -->

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)
