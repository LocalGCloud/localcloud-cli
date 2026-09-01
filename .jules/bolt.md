## 2025-05-18 - Lazy-loading PyYAML in CLI entry points
**Learning:** Top-level imports of `yaml` (PyYAML) in CLI submodules incur a ~30ms import overhead and ~45ms YAML parsing overhead at module load time, slowing down lightweight CLI commands like `localcloud guide`.
**Action:** Defer heavy dependency imports (`import yaml`) inside function calls and memoize pure string rendering functions (`@lru_cache`) to keep module import times minimal on fast CLI paths.
