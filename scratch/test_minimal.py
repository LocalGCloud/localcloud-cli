import json
import urllib.request

def try_ddl(sql):
    payload = {"statements": [sql]}
    req = urllib.request.Request(
        'http://localhost:9020/v1/projects/local-project/instances/test-instance/databases/complex_demo/ddl',
        data=json.dumps(payload).encode('utf-8'),
        headers={'Content-Type': 'application/json'},
        method='PATCH'
    )
    try:
        with urllib.request.urlopen(req) as res:
            return True, res.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return False, e.read().decode('utf-8')
    except Exception as e:
        return False, str(e)

# Let's test a very basic table
sql_basic = """
CREATE TABLE Transactions (
  ShardId          STRING(2)   NOT NULL,
  CustomerId       STRING(36)  NOT NULL,
  TransactionId    STRING(36)  NOT NULL
) PRIMARY KEY (ShardId, CustomerId, TransactionId)
"""
print("Basic:", try_ddl(sql_basic))

# Test with TIMESTAMP OPTIONS
sql_options = """
CREATE TABLE Transactions (
  ShardId          STRING(2)   NOT NULL,
  CustomerId       STRING(36)  NOT NULL,
  TransactionId    STRING(36)  NOT NULL,
  CommitTimestamp  TIMESTAMP   NOT NULL OPTIONS (allow_commit_timestamp=true)
) PRIMARY KEY (ShardId, CustomerId, TransactionId)
"""
print("Options:", try_ddl(sql_options))

# Test with NUMERIC DEFAULT
sql_default = """
CREATE TABLE Transactions (
  ShardId          STRING(2)   NOT NULL,
  CustomerId       STRING(36)  NOT NULL,
  TransactionId    STRING(36)  NOT NULL,
  FeeAmount        NUMERIC     NOT NULL DEFAULT (0)
) PRIMARY KEY (ShardId, CustomerId, TransactionId)
"""
print("Default:", try_ddl(sql_default))

# Test with ARRAY<STRING(MAX)>
sql_array_max = """
CREATE TABLE Transactions_test_array (
  ShardId          STRING(2)   NOT NULL,
  CustomerId       STRING(36)  NOT NULL,
  TransactionId    STRING(36)  NOT NULL,
  ComplianceFlags  ARRAY<STRING(MAX)>
) PRIMARY KEY (ShardId, CustomerId, TransactionId)
"""
print("Array MAX:", try_ddl(sql_array_max))

# Test with ARRAY<STRING(20)>
sql_array_20 = """
CREATE TABLE Transactions_test_array20 (
  ShardId          STRING(2)   NOT NULL,
  CustomerId       STRING(36)  NOT NULL,
  TransactionId    STRING(36)  NOT NULL,
  ComplianceFlags  ARRAY<STRING(20)>
) PRIMARY KEY (ShardId, CustomerId, TransactionId)
"""
print("Array 20:", try_ddl(sql_array_20))

