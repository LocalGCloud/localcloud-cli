# Spanner Full-Text Search & String Function Emulation

## Why

Production Spanner supports full-text search (TOKENLIST, SEARCH_NGRAMS, SCORE_NGRAMS), Unicode normalization (NORMALIZE), phonetic matching (SOUNDEX), index hints (@{FORCE_INDEX=...}), and optimizer hints (@{OPTIMIZER_VERSION=...}). The Spanner emulator supports none of these. This makes it impossible to locally develop and test queries that use fuzzy name matching, geo-filtered search, or any full-text search pipeline.

Real-world example: a peer-to-peer user search query that combines ngram fuzzy matching, soundex fallback, trigram scoring, and geo-block filtering cannot execute at all on the emulator — it fails on 6+ unsupported functions/types.

## Goal

Make the following query constructs **functionally correct** on the LocalCloud Spanner emulator. Performance parity with production is not required — correctness is.

## What's Missing

### 1. TOKENLIST Column Type

**Production behavior:** `TOKENLIST` is a column type that stores pre-computed token indexes for full-text search. Created via generated columns:

```sql
CREATE TABLE MyTable (
    name STRING(MAX),
    name_tokens TOKENLIST AS (TOKENIZE_FULLTEXT(name)) HIDDEN
);
```

**Required emulation:** Store as `BYTES(MAX)` or `STRING(MAX)` internally. The emulator must accept `TOKENLIST` in DDL without error. The column should store tokenized data that `SEARCH_NGRAMS` and `SCORE_NGRAMS` can consume.

**Acceptance criteria:**
- `CREATE TABLE` with `TOKENLIST` columns succeeds
- `TOKENIZE_FULLTEXT(expr)` function returns a value storable in the column
- `TOKENIZE_NGRAMS(expr)` function returns a value storable in the column
- `HIDDEN` column modifier is accepted (may already work)
- `INSERT` populates generated TOKENLIST columns automatically

### 2. SEARCH_NGRAMS(tokenlist, query [, min_ngrams_percent => N])

**Production behavior:** Returns `BOOL`. Checks whether the ngram tokens of `query` overlap with the ngram tokens stored in `tokenlist` column. `min_ngrams_percent` (default 80.0) sets the minimum percentage of query ngrams that must match.

**Required emulation:**

```
SEARCH_NGRAMS(tokenlist_col, search_string) → BOOL
SEARCH_NGRAMS(tokenlist_col, search_string, min_ngrams_percent => 30.0) → BOOL
```

Algorithm (functional, not optimized):
1. Generate character ngrams (bigrams + trigrams) from `search_string`
2. Generate character ngrams from the original text (stored or derivable from tokenlist)
3. Compute overlap percentage = `|intersection| / |query_ngrams| * 100`
4. Return `overlap >= min_ngrams_percent`

Named parameter syntax (`min_ngrams_percent => 30.0`) must be parsed. If too complex, accept it as a positional third argument.

**Acceptance criteria:**
- `WHERE SEARCH_NGRAMS(col, 'james')` returns rows where ngram overlap ≥ 80%
- `WHERE SEARCH_NGRAMS(col, 'james', min_ngrams_percent => 30.0)` uses 30% threshold
- Empty tokenlist returns FALSE
- NULL inputs return NULL

### 3. SCORE_NGRAMS(tokenlist, query)

**Production behavior:** Returns `FLOAT64` between 0.0 and 1.0 representing the ngram similarity score.

**Required emulation:**

```
SCORE_NGRAMS(tokenlist_col, search_string) → FLOAT64
```

Algorithm:
1. Generate ngrams from both operands
2. Return `|intersection| / |union|` (Jaccard similarity) or `|intersection| / |query_ngrams|` (overlap coefficient)
3. Either metric is acceptable for emulation — document which one is used

**Acceptance criteria:**
- Exact match returns 1.0
- No overlap returns 0.0
- Partial match returns value between 0.0 and 1.0
- Usable in SELECT, WHERE, ORDER BY

### 4. SOUNDEX(string)

**Production behavior:** Returns `STRING`. Computes the Soundex phonetic code of the input string. Standard American Soundex algorithm (letter + 3 digits, e.g., `SOUNDEX('Robert')` → `'R163'`).

**Required emulation:**

```
SOUNDEX(expr) → STRING
```

Standard algorithm:
1. Keep first letter (uppercased)
2. Map remaining letters: B/F/P/V→1, C/G/J/K/Q/S/X/Z→2, D/T→3, L→4, M/N→5, R→6
3. Remove duplicates, drop A/E/I/O/U/H/W/Y
4. Pad with zeros or truncate to 4 characters

**Acceptance criteria:**
- `SOUNDEX('james')` → `'J520'`
- `SOUNDEX('jaymes')` → `'J520'` (same code = phonetic match)
- `SOUNDEX('')` → `''`
- `SOUNDEX(NULL)` → `NULL`
- Usable in WHERE, JOIN, GROUP BY, indexes

### 5. NORMALIZE(string, form)

**Production behavior:** Returns `STRING`. Applies Unicode normalization. Forms: `NFC`, `NFD`, `NFKC`, `NFKD`.

**Required emulation:**

```
NORMALIZE(expr, NFC)  → STRING
NORMALIZE(expr, NFD)  → STRING
NORMALIZE(expr, NFKC) → STRING
NORMALIZE(expr, NFKD) → STRING
```

The second argument is an **unquoted keyword**, not a string literal.

**Acceptance criteria:**
- `NORMALIZE('café', NFD)` decomposes é into e + combining acute accent
- `NORMALIZE('café', NFC)` composes back to single é codepoint
- ASCII-only strings pass through unchanged
- `NORMALIZE(NULL, NFD)` → `NULL`
- Usable with `REGEXP_REPLACE` to strip combining marks: `REGEXP_REPLACE(NORMALIZE(x, NFD), r'\pM', '')`

### 6. SAFE_DIVIDE(x, y)

**Production behavior:** Returns `FLOAT64`. Same as `x / y` but returns `NULL` instead of error when `y = 0`.

**Required emulation:**

```
SAFE_DIVIDE(numerator, denominator) → FLOAT64
```

**Acceptance criteria:**
- `SAFE_DIVIDE(10, 3)` → `3.333...`
- `SAFE_DIVIDE(10, 0)` → `NULL`
- `SAFE_DIVIDE(NULL, 5)` → `NULL`
- `SAFE_DIVIDE(0, 0)` → `NULL`

### 7. Index Hints: @{FORCE_INDEX=name}

**Production behavior:** Directs the query optimizer to use a specific secondary index.

```sql
SELECT * FROM MyTable@{FORCE_INDEX=idx_name} WHERE ...
```

**Required emulation:** Parse and **ignore** the hint. The emulator doesn't have a cost-based optimizer — it always does full scans. But the syntax must be accepted without error so production queries run unmodified.

**Acceptance criteria:**
- `FROM MyTable@{FORCE_INDEX=idx_foo}` parses as `FROM MyTable`
- `@{FORCE_INDEX=_BASE_TABLE}` also accepted
- Multiple hints: `@{FORCE_INDEX=idx_foo, GROUPBY_SCAN_OPTIMIZATION=TRUE}` accepted
- Hint on non-existent index: no error (silently ignored)

### 8. Query-Level Optimizer Hints: @{OPTIMIZER_VERSION=...}

**Production behavior:** Sets the query optimizer version.

```sql
@{OPTIMIZER_VERSION=latest}
SELECT ...
```

**Required emulation:** Parse and ignore. Must appear at the start of the SQL statement, before any SELECT/INSERT/UPDATE/DELETE.

**Acceptance criteria:**
- `@{OPTIMIZER_VERSION=latest} SELECT 1` executes as `SELECT 1`
- `@{OPTIMIZER_VERSION=7} SELECT 1` also works
- Unknown hint keys are silently ignored

### 9. Regex Unicode Property Classes

**Production behavior:** `REGEXP_REPLACE` supports Unicode property escapes like `\pM` (combining marks), `\p{L}` (letters), etc.

**Required emulation:** The emulator's `REGEXP_REPLACE` must support at minimum:
- `\pM` or `\p{M}` — Unicode Mark category (combining marks)
- `\pL` or `\p{L}` — Unicode Letter category
- `\pN` or `\p{N}` — Unicode Number category

If the underlying regex engine (RE2) already supports these, no work needed. If not, pre-process the pattern to expand property classes.

**Acceptance criteria:**
- `REGEXP_REPLACE(NORMALIZE('café', NFD), r'\pM', '')` → `'cafe'`
- `REGEXP_REPLACE('abc123', r'\pN', '')` → `'abc'`

## Integration Test Query

The following query MUST execute end-to-end on the emulator after implementation. It can return 0 rows (empty table), but must not error:

```sql
@{OPTIMIZER_VERSION=latest}
WITH qp AS (
    SELECT
        REGEXP_REPLACE(REGEXP_REPLACE(LOWER(NORMALIZE('james', NFD)), r'\pM', ''), r'[-\s]+', '') AS norm_query,
        SOUNDEX('james') AS token_sdx,
        CHAR_LENGTH('james') AS token_len
)
SELECT
    p.PEER_ID,
    SCORE_NGRAMS(p.fuzzy_name_tkn, 'james') AS fuzzy_score,
    SAFE_DIVIDE(1.0, 3.0) AS test_div,
    qp.token_sdx
FROM P2P_USER_PEERS_V5@{FORCE_INDEX=idx_fuzzy_name} p
CROSS JOIN qp
WHERE SEARCH_NGRAMS(p.fuzzy_name_tkn, 'james', min_ngrams_percent => 30.0)
LIMIT 10;
```

## Table DDL That Must Be Accepted

```sql
CREATE TABLE P2P_USER_PEERS_V5 (
    PEER_ID STRING(MAX) NOT NULL,
    ENTITY_FIRST_NAME STRING(MAX),
    ENTITY_LAST_NAME STRING(MAX),
    ENTITY_CITY STRING(MAX),
    ENTITY_STATE STRING(MAX),
    ENTITY_COUNTRY STRING(MAX),
    GEO_BLOCK INT64,
    norm_first STRING(MAX),
    norm_last STRING(MAX),
    norm_full_name STRING(MAX),
    first_soundex STRING(4),
    last_soundex STRING(4),
    fuzzy_name_tkn TOKENLIST AS (TOKENIZE_NGRAMS(
        CONCAT(IFNULL(ENTITY_FIRST_NAME, ''), ' ', IFNULL(ENTITY_LAST_NAME, ''))
    )) HIDDEN,
) PRIMARY KEY(PEER_ID);

CREATE INDEX idx_fuzzy_name ON P2P_USER_PEERS_V5(fuzzy_name_tkn);
CREATE INDEX idx_sdx_first ON P2P_USER_PEERS_V5(first_soundex, ENTITY_FIRST_NAME);
CREATE INDEX idx_sdx_last ON P2P_USER_PEERS_V5(last_soundex, ENTITY_LAST_NAME);
```

## Non-Goals

- Performance optimization (no inverted indexes, no ngram indexing)
- TOKENIZE_SUBSTRING, TOKENIZE_BOOL, TOKEN function variants (only TOKENIZE_FULLTEXT and TOKENIZE_NGRAMS needed)
- Full-text SEARCH() function (only SEARCH_NGRAMS needed)
- Production-scale ngram storage format

## Implementation Priority

| Priority | Feature | Complexity | Unlocks |
|----------|---------|-----------|---------|
| P0 | Optimizer/index hint parsing (ignore) | Low | All production queries parse |
| P0 | SOUNDEX() | Low | Phonetic matching paths |
| P0 | SAFE_DIVIDE() | Low | Scoring arithmetic |
| P1 | NORMALIZE(str, form) | Medium | Unicode-normalized comparisons |
| P1 | TOKENLIST type + TOKENIZE_NGRAMS() | Medium | Table DDL acceptance |
| P1 | SEARCH_NGRAMS() | Medium | Fuzzy search WHERE clause |
| P1 | SCORE_NGRAMS() | Medium | Fuzzy search ranking |
| P2 | Unicode regex property classes (\pM) | Low-Medium | Diacritic stripping |

## Architecture Decision

Two approaches:

### A. Patch the Spanner Emulator Binary (C++)
- Modify the open-source emulator's function registry
- Highest fidelity — functions live inside the SQL engine
- Requires building and maintaining a fork
- We already maintain a fork (`spanner-emulator-build`) for persistence

### B. SQL Preprocessing in Java Gateway (QueryService.java)
- Intercept SQL in `executeSpannerQuery()` before sending to emulator
- Strip hints, rewrite `SOUNDEX()` → subquery/UDF, rewrite `NORMALIZE()` → passthrough
- Cannot easily emulate `SEARCH_NGRAMS` on TOKENLIST columns (needs storage-layer support)
- Lower effort but limited to stateless function rewrites

### Recommendation: Hybrid (A for storage types, B for simple functions)

- **Emulator fork (A):** TOKENLIST type, TOKENIZE_NGRAMS, SEARCH_NGRAMS, SCORE_NGRAMS, NORMALIZE — these need storage-layer integration
- **Java gateway (B):** Hint stripping, SOUNDEX, SAFE_DIVIDE — stateless rewrites that don't need storage access
