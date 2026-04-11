# Bug: PersistentStorage fails to create LevelDB directory on database creation

**Component**: `backend/storage/persistent_storage.cc`
**Severity**: Critical — all `CREATE DATABASE` calls fail when `--data_dir` is set
**Found in**: LocalCloud integration testing (Docker container with `--data_dir=/var/lib/localcloud/spanner-data`)

## Symptom

When the emulator is started with `--data_dir=/path/to/data`, every `CREATE DATABASE` request returns:

```
500 Failed to open LevelDB at /var/lib/localcloud/spanner-data/demo-db-1234/storage:
    NotFound: /var/lib/localcloud/spanner-data/demo-db-1234/storage/LOCK:
    No such file or directory
```

The emulator creates the database-level directory (e.g., `demo-db-1234/`) under `--data_dir`, but then immediately calls `leveldb::DB::Open()` on the `storage/` subdirectory without creating it first. LevelDB's `Open()` with `create_if_missing = true` only creates the final directory — it does not create intermediate parent directories.

## Steps to Reproduce

```bash
# 1. Start the emulator with --data_dir
./emulator_main --data_dir=/tmp/spanner-test

# 2. In another terminal, create an instance (succeeds)
curl -X POST "http://localhost:9020/v1/projects/test/instances" \
  -H "Content-Type: application/json" \
  -d '{"instanceId":"inst","instance":{"config":"emulator-config","displayName":"inst","nodeCount":1}}'

# 3. Create a database (FAILS)
curl -X POST "http://localhost:9020/v1/projects/test/instances/inst/databases" \
  -H "Content-Type: application/json" \
  -d '{"createStatement":"CREATE DATABASE testdb"}'

# Error:
# {"code":13, "message":"Failed to open LevelDB at /tmp/spanner-test/testdb/storage:
#  NotFound: /tmp/spanner-test/testdb/storage/LOCK: No such file or directory"}

# 4. Manually create the directory, then retry — succeeds
mkdir -p /tmp/spanner-test/testdb/storage
# Repeat step 3 → now returns 200 OK
```

## Root Cause

In `PersistentStorage`'s constructor (or the factory method that creates it), the code computes the LevelDB path as:

```
{data_dir}/{database_id}/storage/
```

It then calls `leveldb::DB::Open(options, path, &db)` where `options.create_if_missing = true`.

However, `leveldb::DB::Open()` only creates the leaf directory. It does **not** create parent directories. When the database is new, the `{database_id}/` directory doesn't exist yet, so `{database_id}/storage/` also doesn't exist, and LevelDB fails trying to create the `LOCK` file.

## Expected Behavior

The emulator should create the full directory path before opening LevelDB:

```
{data_dir}/{database_id}/storage/
```

## Suggested Fix

In the `PersistentStorage` constructor or factory (likely in `persistent_storage.cc`), add a `mkdir -p` equivalent before the `leveldb::DB::Open()` call:

```cpp
#include <filesystem>  // C++17

// Before leveldb::DB::Open():
std::filesystem::create_directories(storage_path);  // creates all intermediate dirs
```

Or using POSIX (if C++17 filesystem is not available in the build):

```cpp
#include <sys/stat.h>
#include <sys/types.h>

static void mkdirs(const std::string& path) {
    size_t pos = 0;
    while ((pos = path.find('/', pos + 1)) != std::string::npos) {
        mkdir(path.substr(0, pos).c_str(), 0755);
    }
    mkdir(path.c_str(), 0755);
}

// Before leveldb::DB::Open():
mkdirs(storage_path);
```

The fix is a single call before `leveldb::DB::Open()`. The `create_if_missing` LevelDB option should still be set to `true` as well.

## Verification

After the fix, the following should work without manual directory creation:

```bash
rm -rf /tmp/spanner-test
./emulator_main --data_dir=/tmp/spanner-test

# Create instance + database → should succeed
# Restart emulator → data should persist
# ls /tmp/spanner-test/ → should show database subdirectories with LevelDB files
```

## Workaround (LocalCloud)

Until this is fixed, LocalCloud disables `--data_dir` in the wrapper script so the emulator runs in-memory mode (matching upstream behavior). All 12 Spanner demo operations pass in-memory mode. The Dockerfile documents this:

```dockerfile
# To enable persistence, fix PersistentStorage to mkdir -p the storage/
# subdirectory before calling leveldb::DB::Open(), then uncomment --data_dir.
exec /usr/local/bin/spanner-emulator-main "$@"
```

## Impact

- **Without fix**: `--data_dir` flag is completely non-functional. Every database creation fails. The persistence feature cannot be used.
- **With fix**: One-line change. All existing tests should continue to pass. New databases get their LevelDB directories created automatically.

## Files to Change

| File | Change |
|------|--------|
| `backend/storage/persistent_storage.cc` | Add `std::filesystem::create_directories(path)` or POSIX `mkdir -p` before `leveldb::DB::Open()` call |

## Related

- Spec: `specs/003-spanner-storage-extensibility/spec.md` — NF6 lists `persistent_storage.cc` as a new file in the fork
- Plan Phase 2, Step 6: "Implement PersistentStorage class"
- Test case PS7 (`EmptyDatabase_OpenClose`): "Create storage, close, reopen → no errors" — this test likely passes because it reuses an existing directory rather than creating a new one
