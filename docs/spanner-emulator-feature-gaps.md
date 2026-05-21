# Spanner Emulator — Feature Gap Analysis

This document covers two features where the forked Cloud Spanner emulator has
infrastructure but lacks full execution: **Vector Search (ANN)** and **Full-Text
Search (FTS)**. It describes what currently exists, what is missing, and how to
fill the gaps.

---

## 1. Vector Search (ANN)

### Current Status

**Semantically correct, but brute-force.** The emulator has the full scaffolding
— DDL parsing, schema model, validation, conformance tests — yet `APPROX_COSINE_DISTANCE()`
and friends compute exact distances against every row instead of using an
approximate nearest-neighbour index.

### What Exists

| Component | Status | Location |
|---|---|---|
| `CREATE VECTOR INDEX` DDL | ✅ Parse, validate, store metadata | `backend/schema/parser/ddl_parser.cc:1810` |
| `ALTER / DROP VECTOR INDEX` DDL | ✅ Parse, validate | `backend/schema/parser/ddl_parser.cc:2830` |
| Schema model (distance_type, tree_depth, …) | ✅ In proto + index.h | `backend/schema/ddl/operations.proto:971` |
| `ANNValidator` | ✅ Rejects invalid ANN queries | `backend/query/ann_validator.cc (391 lines)` |
| `ANNFunctionsRewriter` | ✅ Strips JSON options arg from `APPROX_*` | `backend/query/ann_functions_rewriter.cc` |
| ZetaSQL distance functions | ✅ `COSINE_DISTANCE`, `DOT_PRODUCT`, `EUCLIDEAN_DISTANCE` | `backend/query/analyzer_options.cc:95-96` |
| Conformance tests | ✅ 687 lines | `tests/conformance/cases/ann_test.cc` |

### What's Missing

| Missing Piece | Impact | Complexity |
|---|---|---|
| **HNSW or IVF index** — stores vectors in a graph/partition structure for approximate search | Without it, every `APPROX_*` query scans all rows — O(n) instead of O(log n). Fine for <10K rows, wrong at scale. | **High** (~2000 lines C++) |
| **Index build on write** — when data is inserted into a table with a `VECTOR INDEX`, insert the embedding into the HNSW graph | Without it, the vector index is just metadata — no actual index data | **Medium** |
| **Query planner hook** — use the HNSW index for `ORDER BY distance() LIMIT k` | Without it, the index is never consulted | **Medium** |

### Implementation Approach (C++ in `cloud-spanner-emulator`)

1. **Pick an ANN algorithm.** HNSW is the standard choice — well-understood,
   excellent recall/performance tradeoffs, and open-source C++ reference
   implementations exist (e.g., hnswlib).

2. **Persist the HNSW graph.** LevelDB is already the storage backend. The HNSW
   graph is a collection of nodes with neighbor pointers + vector data —
   serializable as protobuf and stored in a separate LevelDB key namespace
   (e.g. `\x02{index_id}\x00{node_id}`).

3. **Build on write.** In the storage write path, when a table has a
   `VECTOR INDEX` and the row contains the indexed column, extract the vector,
   find its HNSW insertion point, update the graph, and persist the node.

4. **Query with the index.** When the query pipeline sees:

   ```sql
   SELECT * FROM t ORDER BY COSINE_DISTANCE(embedding, @q) LIMIT 10
   ```

   Instead of full scan + sort, traverse the HNSW graph from the entry point,
   collect candidate vectors, compute exact distances on candidates, return
   top-k. The emulator already has `ANNFunctionsRewriter` that intercepts these
   calls — that is the natural integration point.

#### Simpler First Step

If HNSW is too ambitious initially, a brute-force-but-correct approach is:

- Store vectors inline in the row in the existing LevelDB storage
- The existing `APPROX_*` → exact distance computation already works correctly
- Document the performance limitation and add HNSW as a follow-up

---

## 2. Full-Text Search (FTS)

### Current Status

**Tokenizers and evaluators exist, but no inverted index.** The emulator has 8
tokenizer types, `SEARCH()`, `SCORE()`, `SNIPPET()` functions registered as
ZetaSQL evaluators (64 files in `backend/query/search/`), and they work per-row.
But there is no inverted index, so every `SEARCH()` call scans all rows.

### What Exists

| Component | Status | Location |
|---|---|---|
| `CREATE SEARCH INDEX` DDL | ✅ Parse, validate, store metadata | `backend/schema/parser/ddl_parser.cc:1857` |
| 8 tokenizer types | ✅ PlainText, ExactMatch, Substring, Numeric, Bool, Ngrams, JSON, JSONB | `backend/query/search/` |
| `SEARCH()` function | ✅ Evaluates per-row (tokenizes + matches) | `backend/query/search/search_function_catalog.cc:459` |
| `SCORE()` function | ✅ Per-row relevance score | `backend/query/search/search_function_catalog.cc:531` |
| `SNIPPET()` function | ✅ Generates highlighted result snippets | `backend/query/search/search_function_catalog.cc:584` |
| `SEARCH_SUBSTRING()` | ✅ Substring matching evaluator | `backend/query/search/search_function_catalog.cc:494` |
| `SEARCH_NGRAMS()` | ✅ N-gram matching evaluator | `backend/query/search/search_function_catalog.cc` |
| Search query parser | ✅ JJTree grammar for search query parsing | `backend/query/search/query_parser.jjt` |

### What's Missing

| Missing Piece | Impact | Complexity |
|---|---|---|
| **Inverted index** — mapping token → {doc_id, positions} | Without it, every `SEARCH()` is a full table scan | **High** (~2500 lines C++) |
| **Index build during writes** — tokenize text as written, add to inverted index | Without it, the index is never populated | **Medium** |
| **Query planner integration** — detect `SEARCH(col)` against a search-indexed column and use the index | Without it, the index is never consulted | **Medium** |
| **BM25/BM25F scoring** — proper ranking instead of basic TF | Without it, `SCORE()` returns simplified relevance | **Medium** |
| `language_tag`, tokenizer options wiring | DDL options are parsed but not fully wired to tokenizers | **Low-Medium** |

### Implementation Approach (C++ in `cloud-spanner-emulator`)

1. **Inverted index data structure.** Two LevelDB key namespaces:

   - `\x03{index_id}\x00{token_hash}\x00{doc_id}` → `{positions, field_id, …}`
     (postings list)
   - `\x04{index_id}\x00{doc_id}` → `{doc_length, field_lengths}` (document
     metadata)

2. **Build on write.** When a row is inserted/updated and the table has a
   `SEARCH INDEX`:
   - Read the indexed columns
   - Tokenize each column using the index's configured tokenizer
   - For each token, add a posting to the inverted index
   - Update document metadata (lengths)

3. **Query with the index.** When `SEARCH(col, 'query text')` is evaluated:
   - Tokenize the query text
   - Look up each query token in the inverted index
   - Intersect/union the posting lists to find matching documents
   - Compute relevance scores
   - Return matching rows

4. **Plumbing through `SearchEvaluator`.** The existing `EvalSearch()` in
   `search_function_catalog.cc` already dispatches to tokenizers and matching
   logic. The change is:
   - Have the evaluator check if an inverted index exists for the target column
   - If yes, use the index for fast retrieval
   - If no, fall back to current per-row evaluation

---

## 3. Comparison: Which Is Easier?

| Factor | Vector Search (ANN) | Full-Text Search (FTS) |
|---|---|---|
| Existing infrastructure | Schema + validation + rewriter + tests (solid) | Tokenizers + evaluators + tests (very solid) |
| Index algorithm | HNSW — well-known, ~500 lines core | Inverted index — well-known, ~800 lines core |
| Integration point | `ANNFunctionsRewriter` (already intercepts ANN calls) | `SearchEvaluator` (already evaluates per-row) |
| Storage | HNSW graph → LevelDB (new namespace) | Postings list → LevelDB (new namespace) |
| Correctness today | Brute-force exact distance (works fine) | Per-row token matching (works fine) |
| Need for index | Low for <10K rows, necessary beyond | Low for <1K rows, necessary beyond |

---

## 4. Recommendation

Implement both using a phased approach.

### Phase 1 — Vector Search

- Build the HNSW graph data structure
- Hook into the write path to insert vectors into HNSW
- Use the HNSW index in the `ANNFunctionsRewriter` path when
  `ORDER BY distance() LIMIT k` is detected
- Fall back to exact search when the index does not exist or the query cannot
  use it

### Phase 2 — Full-Text Search

- Build the inverted index data structure
- Hook into the write path to tokenize text and build postings
- Modify `SearchEvaluator` to use the inverted index when available
- Implement BM25 scoring

### Phase 3 — Polish

- Tokenizer options / `language_tag` wiring for FTS
- ANN: support for batch vector insertion
- Performance benchmarks and tuning

---

## Summary

Both features **already work correctly** for small datasets — `SEARCH()` tokenizes
and matches, and `APPROX_COSINE_DISTANCE()` computes exact distances. The gap
is only about **performance at scale** (missing indexes). If the goal is
correctness for testing and development, both are already usable. If the goal
is performance parity with Spanner Omni, the missing piece is the same for both:
build an index structure in the write path and use it in the read path.
