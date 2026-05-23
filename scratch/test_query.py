import json
import urllib.request

with open('scratch/test_spanner_fixed.sql', 'r') as f:
    sql = f.read()

payload = {
    "statements": [sql]
}

req = urllib.request.Request(
    'http://localhost:9020/v1/projects/local-project/instances/test-instance/databases/complex_demo/ddl',
    data=json.dumps(payload).encode('utf-8'),
    headers={'Content-Type': 'application/json'},
    method='PATCH'
)

try:
    with urllib.request.urlopen(req) as res:
        print("Success:")
        print(res.read().decode('utf-8'))
except urllib.error.HTTPError as e:
    print(f"HTTP Error {e.code}:")
    print(e.read().decode('utf-8'))
except Exception as e:
    print(f"Error: {e}")
