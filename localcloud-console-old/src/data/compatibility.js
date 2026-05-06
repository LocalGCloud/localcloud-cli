/**
 * SQL compatibility data for LocalCloud emulators.
 * Maps CONFIRMED unsupported features to warning messages and alternatives.
 * Only includes features verified to NOT work — many BigQuery functions
 * are supported via SQLGlot translation to DuckDB.
 *
 * Tested working (NOT listed here): APPROX_COUNT_DISTINCT, SAFE_DIVIDE,
 * SAFE_CAST, GENERATE_UUID, QUALIFY, PIVOT, UNNEST, STRUCT, ARRAY,
 * ST_GEOGPOINT, window functions, CTEs, REGEXP, JSON functions.
 */

export const COMPATIBILITY = {
  bigquery: {
    functions: {
      'NET.IP_FROM_STRING': 'NET functions are not available in the BigQuery emulator',
      'NET.IP_TO_STRING': 'NET functions are not available in the BigQuery emulator',
      'NET.SAFE_IP_FROM_STRING': 'NET functions are not available in the BigQuery emulator',
      'NET.HOST': 'NET functions are not available in the BigQuery emulator',
      'NET.REG_DOMAIN': 'NET functions are not available in the BigQuery emulator',
      'NET.PUBLIC_SUFFIX': 'NET functions are not available in the BigQuery emulator',
      'ML.PREDICT': 'ML functions are not available in the emulator',
      'ML.EVALUATE': 'ML functions are not available in the emulator',
      'ML.TRAINING_INFO': 'ML functions are not available in the emulator',
      'ML.FEATURE_INFO': 'ML functions are not available in the emulator',
      'SESSION_USER': 'Not available in the emulator context',
      'ERROR': 'BigQuery ERROR() function is not supported by DuckDB',
    },
    types: {
      'BIGNUMERIC': 'Use NUMERIC or FLOAT64 instead',
    },
    clauses: {
      'FOR SYSTEM_TIME AS OF': 'Time travel is not supported in the emulator',
      'ASSERT_ROWS_MODIFIED': 'Not supported by the emulator',
      'TABLESAMPLE': 'Not supported — use LIMIT with ORDER BY RANDOM() instead',
    },
  },

  spanner: {
    functions: {
      'PENDING_COMMIT_TIMESTAMP': 'Use CURRENT_TIMESTAMP instead',
      'ML.PREDICT': 'ML functions are not available in the Spanner emulator',
    },
    types: {},
    clauses: {
      'MERGE': 'Not supported by Spanner emulator — use separate INSERT/UPDATE/DELETE',
      'TABLESAMPLE': 'Not supported by the Spanner emulator',
    },
  },

  // PostgreSQL — no compatibility warnings (queries run against real PostgreSQL)
  postgresql: {
    functions: {},
    types: {},
    clauses: {},
  },
};

/**
 * Get all unsupported keywords for a dialect as a flat list.
 */
export function getUnsupportedKeywords(dialect) {
  const data = COMPATIBILITY[dialect];
  if (!data) return [];
  return [
    ...Object.keys(data.functions || {}),
    ...Object.keys(data.types || {}),
    ...Object.keys(data.clauses || {}),
  ];
}

/**
 * Get the warning message for a specific keyword in a dialect.
 */
export function getWarningMessage(dialect, keyword) {
  const data = COMPATIBILITY[dialect];
  if (!data) return null;
  const upper = keyword.toUpperCase();
  return data.functions?.[upper] || data.types?.[upper] || data.clauses?.[upper] || null;
}
