-- BigQuery: Comprehensive DDL + Sample Data
-- Features: partitioned tables, clustered tables, materialized views,
--           range partitioning, nested/repeated fields, DML operations,
--           time-based partitioning, table snapshots, authorized views
--
-- This file demonstrates BQ-specific DDL. Paste into BigQuery query console.
-- ============================================================
-- TABLE DEFINITIONS with partitioning and clustering
-- ============================================================

-- Partitioned by ingestion time with daily partition
CREATE TABLE app_analytics.events (
  event_id      STRING NOT NULL,
  user_id       STRING NOT NULL,
  event_type    STRING NOT NULL,
  event_name    STRING,
  page          STRING,
  referrer      STRING,
  user_agent    STRING,
  ip_address    STRING,
  session_id    STRING,
  device        STRING,
  browser       STRING,
  os            STRING,
  country       STRING,
  region        STRING,
  city          STRING,
  payload       JSON,
  properties    ARRAY<STRUCT<key STRING, value STRING>>,
  created_at    TIMESTAMP NOT NULL
)
PARTITION BY DATE(created_at)
CLUSTER BY user_id, event_type;

-- Partitioned by integer range (e.g., customer ID ranges)
CREATE TABLE app_analytics.customer_segments (
  customer_id   INT64 NOT NULL,
  segment_name  STRING,
  score         FLOAT64,
  last_updated  TIMESTAMP
)
PARTITION BY RANGE_BUCKET(customer_id, GENERATE_ARRAY(0, 1000000, 100000))
CLUSTER BY segment_name;

-- Nested and repeated fields (arrays of structs)
CREATE TABLE app_analytics.user_sessions (
  session_id    STRING NOT NULL,
  user_id       STRING NOT NULL,
  start_time    TIMESTAMP,
  end_time      TIMESTAMP,
  duration_sec  INT64,
  pages         ARRAY<STRUCT<
    page_url STRING,
    page_title STRING,
    time_on_page INT64,
    events ARRAY<STRUCT<
      event_type STRING,
      event_time TIMESTAMP,
      event_data STRING>>>>,
  device_info   STRUCT<
    device_type STRING,
    browser STRING,
    browser_version STRING,
    os STRING,
    os_version STRING,
    screen_resolution STRING>,
  location      STRUCT<
    country STRING,
    region STRING,
    city STRING,
    latitude FLOAT64,
    longitude FLOAT64>,
  traffic_source STRUCT<
    source STRING,
    medium STRING,
    campaign STRING,
    content STRING>,
  created_at    TIMESTAMP NOT NULL
)
PARTITION BY DATE(start_time)
CLUSTER BY user_id;

-- Materialized view: daily event counts by type
CREATE MATERIALIZED VIEW app_analytics.daily_event_summary AS
SELECT
  DATE(created_at) as event_date,
  event_type,
  COUNT(*) as event_count,
  COUNT(DISTINCT user_id) as unique_users,
  COUNT(DISTINCT session_id) as unique_sessions
FROM app_analytics.events
GROUP BY DATE(created_at), event_type;

-- Materialized view: top pages by traffic
CREATE MATERIALIZED VIEW app_analytics.top_pages_daily AS
SELECT
  DATE(created_at) as event_date,
  page,
  COUNT(*) as pageviews,
  COUNT(DISTINCT user_id) as unique_visitors,
  COUNT(DISTINCT session_id) as sessions
FROM app_analytics.events
WHERE page IS NOT NULL
GROUP BY DATE(created_at), page;

-- Time-based partitioned table for orders
CREATE TABLE app_analytics.orders (
  order_id        STRING NOT NULL,
  customer_id     STRING NOT NULL,
  customer_name   STRING,
  customer_email  STRING,
  status          STRING,
  order_type      STRING,
  total_amount    NUMERIC,
  subtotal        NUMERIC,
  tax_amount      NUMERIC,
  shipping_amount NUMERIC,
  discount_amount NUMERIC,
  currency        STRING,
  payment_method  STRING,
  gateway         STRING,
  items           ARRAY<STRUCT<
    product_id STRING,
    product_name STRING,
    quantity INT64,
    unit_price NUMERIC,
    total_price NUMERIC>>,
  shipping_address STRUCT<
    street STRING,
    city STRING,
    state STRING,
    zip STRING,
    country STRING>,
  order_date      TIMESTAMP NOT NULL,
  paid_at         TIMESTAMP,
  shipped_at      TIMESTAMP,
  delivered_at    TIMESTAMP
)
PARTITION BY TIMESTAMP_TRUNC(order_date, MONTH)
CLUSTER BY status, customer_id;

-- Product catalog with nested attributes
CREATE TABLE app_analytics.products (
  product_id    STRING NOT NULL,
  sku           STRING,
  name          STRING,
  description   STRING,
  category      STRING,
  subcategory   STRING,
  brand         STRING,
  price         NUMERIC,
  cost_price    NUMERIC,
  attributes    ARRAY<STRUCT<name STRING, value STRING>>,
  variants      ARRAY<STRUCT<
    variant_id STRING,
    sku STRING,
    color STRING,
    size STRING,
    price NUMERIC,
    inventory_count INT64>>,
  tags          ARRAY<STRING>,
  images        ARRAY<STRING>,
  rating_avg    FLOAT64,
  review_count  INT64,
  created_at    TIMESTAMP,
  updated_at    TIMESTAMP
)
CLUSTER BY category, brand;

-- ============================================================
-- SAMPLE DATA (INSERT)
-- ============================================================

INSERT INTO app_analytics.events
  (event_id, user_id, event_type, event_name, page, referrer, user_agent, ip_address,
   session_id, device, browser, os, country, region, city, payload, properties, created_at)
VALUES
  ('ev-001', 'user-001', 'page_view', 'View Dashboard', '/dashboard', '/login',
   'Mozilla/5.0', '192.168.1.1', 'ses-abc-001', 'Desktop', 'Chrome 120', 'macOS 14',
   'US', 'CA', 'San Francisco',
   JSON '{"loadTime":1200,"domInteractive":850}',
   [STRUCT('source' AS key, 'direct' AS value), STRUCT('campaign' AS key, 'spring_sale' AS value)],
   TIMESTAMP '2025-05-01 08:00:00'),

  ('ev-002', 'user-001', 'click', 'Click Signup', '/signup', '/pricing',
   'Mozilla/5.0', '192.168.1.1', 'ses-abc-001', 'Desktop', 'Chrome 120', 'macOS 14',
   'US', 'CA', 'San Francisco',
   JSON '{"clickTarget":"button-cta","position":"hero"}',
   [STRUCT('source' AS key, 'direct' AS value)],
   TIMESTAMP '2025-05-01 08:05:00'),

  ('ev-003', 'user-001', 'conversion', 'Signup Complete', '/welcome', '/signup',
   'Mozilla/5.0', '192.168.1.1', 'ses-abc-001', 'Desktop', 'Chrome 120', 'macOS 14',
   'US', 'CA', 'San Francisco',
   JSON '{"conversionType":"signup","plan":"pro"}',
   [STRUCT('plan' AS key, 'pro' AS value)],
   TIMESTAMP '2025-05-01 08:10:00'),

  ('ev-004', 'user-002', 'page_view', 'View Products', '/products', '/dashboard',
   'Mozilla/5.0', '10.0.0.2', 'ses-abc-002', 'Mobile', 'Safari 17', 'iOS 17',
   'US', 'NY', 'New York',
   JSON '{"loadTime":2500,"domInteractive":1800}',
   [STRUCT('source' AS key, 'email' AS value)],
   TIMESTAMP '2025-05-01 09:00:00'),

  ('ev-005', 'user-002', 'search', 'Search Products', '/search', '/products',
   'Mozilla/5.0', '10.0.0.2', 'ses-abc-002', 'Mobile', 'Safari 17', 'iOS 17',
   'US', 'NY', 'New York',
   JSON '{"searchQuery":"api gateway","resultsCount":12}',
   [STRUCT('query' AS key, 'api gateway' AS value)],
   TIMESTAMP '2025-05-01 09:02:00'),

  ('ev-006', 'user-003', 'page_view', 'View Pricing', '/pricing', '/products',
   'Mozilla/5.0', '10.0.0.3', 'ses-abc-003', 'Desktop', 'Firefox 123', 'Linux',
   'DE', 'BE', 'Berlin',
   JSON '{"loadTime":900,"domInteractive":650}',
   [STRUCT('source' AS key, 'google' AS value)],
   TIMESTAMP '2025-05-01 10:00:00'),

  ('ev-007', 'user-003', 'click', 'Start Trial', '/signup', '/pricing',
   'Mozilla/5.0', '10.0.0.3', 'ses-abc-003', 'Desktop', 'Firefox 123', 'Linux',
   'DE', 'BE', 'Berlin',
   JSON '{"clickTarget":"trial-cta","plan":"enterprise"}',
   [STRUCT('plan' AS key, 'enterprise' AS value)],
   TIMESTAMP '2025-05-01 10:05:00'),

  ('ev-008', 'user-004', 'page_view', 'View Dashboard', '/dashboard', '/login',
   'Mozilla/5.0', '10.0.0.4', 'ses-abc-004', 'Tablet', 'Chrome 120', 'Android 14',
   'GB', 'ENG', 'London',
   JSON '{"loadTime":1500,"domInteractive":1100}',
   [STRUCT('source' AS key, 'direct' AS value)],
   TIMESTAMP '2025-05-01 11:00:00'),

  ('ev-009', 'user-001', 'page_view', 'View Settings', '/settings', '/dashboard',
   'Mozilla/5.0', '192.168.1.1', 'ses-abc-005', 'Desktop', 'Chrome 120', 'macOS 14',
   'US', 'CA', 'San Francisco',
   JSON '{"loadTime":800,"domInteractive":600}',
   [STRUCT('source' AS key, 'direct' AS value)],
   TIMESTAMP '2025-05-02 08:00:00'),

  ('ev-010', 'user-005', 'page_view', 'View Reports', '/reports', '/dashboard',
   'Mozilla/5.0', '10.0.0.5', 'ses-abc-006', 'Desktop', 'Edge 120', 'Windows 11',
   'US', 'WA', 'Seattle',
   JSON '{"loadTime":2000,"domInteractive":1500}',
   [STRUCT('source' AS key, 'slack' AS value)],
   TIMESTAMP '2025-05-02 09:30:00'),

  ('ev-011', 'user-005', 'export', 'Export Report', '/reports/export', '/reports',
   'Mozilla/5.0', '10.0.0.5', 'ses-abc-006', 'Desktop', 'Edge 120', 'Windows 11',
   'US', 'WA', 'Seattle',
   JSON '{"exportType":"csv","rows":15000,"duration":3400}',
   [STRUCT('format' AS key, 'csv' AS value)],
   TIMESTAMP '2025-05-02 09:35:00'),

  ('ev-012', 'user-006', 'error', 'Page Load Error', '/checkout/payment', '/cart',
   'Mozilla/5.0', '10.0.0.6', 'ses-abc-007', 'Mobile', 'Chrome 120', 'Android 14',
   'IN', 'KA', 'Bangalore',
   JSON '{"errorCode":"PAYMENT_TIMEOUT","httpStatus":504,"retryCount":2}',
   [STRUCT('errorType' AS key, 'timeout' AS value)],
   TIMESTAMP '2025-05-02 10:00:00'),

  ('ev-013', 'user-002', 'order', 'Order Placed', '/order/confirmation', '/checkout',
   'Mozilla/5.0', '10.0.0.2', 'ses-abc-008', 'Mobile', 'Safari 17', 'iOS 17',
   'US', 'NY', 'New York',
   JSON '{"orderId":"ord-2025-100","total":299.99,"items":3}',
   [STRUCT('orderValue' AS key, '299.99' AS value)],
   TIMESTAMP '2025-05-02 11:00:00'),

  ('ev-014', 'user-007', 'page_view', 'View Billing', '/billing', '/settings',
   'Mozilla/5.0', '10.0.0.7', 'ses-abc-009', 'Desktop', 'Firefox 123', 'Windows 11',
   'JP', '13', 'Tokyo',
   JSON '{"loadTime":950,"domInteractive":700}',
   [STRUCT('source' AS key, 'email' AS value)],
   TIMESTAMP '2025-05-03 07:00:00'),

  ('ev-015', 'user-008', 'page_view', 'View Team', '/team', '/dashboard',
   'Mozilla/5.0', '10.0.0.8', 'ses-abc-010', 'Desktop', 'Chrome 120', 'macOS 14',
   'AU', 'NSW', 'Sydney',
   JSON '{"loadTime":1100,"domInteractive":850}',
   [STRUCT('source' AS key, 'direct' AS value)],
   TIMESTAMP '2025-05-03 08:00:00');

INSERT INTO app_analytics.orders
  (order_id, customer_id, customer_name, customer_email, status, order_type,
   total_amount, subtotal, tax_amount, shipping_amount, discount_amount, currency,
   payment_method, gateway, items, shipping_address, order_date, paid_at, shipped_at, delivered_at)
VALUES
  ('ord-2025-100', 'user-002', 'Alice Johnson', 'alice@example.com',
   'delivered', 'standard', 299.99, 259.99, 26.00, 14.00, 0.00, 'USD', 'visa', 'stripe',
   [STRUCT('prod-007' AS product_id, 'API Gateway Enterprise' AS product_name, 2 AS quantity, 149.99 AS unit_price, 299.98 AS total_price)],
   STRUCT('100 Market St' AS street, 'San Francisco' AS city, 'CA' AS state, '94105' AS zip, 'US' AS country),
   TIMESTAMP '2025-05-02 11:00:00', TIMESTAMP '2025-05-02 11:05:00',
   TIMESTAMP '2025-05-03 14:00:00', TIMESTAMP '2025-05-05 09:00:00'),

  ('ord-2025-101', 'user-003', 'Bob Smith', 'bob@techcorp.com',
   'shipped', 'standard', 149.00, 129.00, 12.90, 7.10, 0.00, 'USD', 'paypal', 'paypal',
   [STRUCT('prod-010' AS product_id, 'Secrets Vault Enterprise' AS product_name, 1 AS quantity, 149.00 AS unit_price, 149.00 AS total_price)],
   STRUCT('500 Terry Ave' AS street, 'Seattle' AS city, 'WA' AS state, '98109' AS zip, 'US' AS country),
   TIMESTAMP '2025-05-03 10:00:00', TIMESTAMP '2025-05-03 10:05:00',
   TIMESTAMP '2025-05-04 11:00:00', NULL),

  ('ord-2025-102', 'user-005', 'David Chen', 'david@cloudbase.io',
   'processing', 'enterprise', 4500.00, 4000.00, 400.00, 100.00, 0.00, 'USD', 'invoice', 'wire',
   [STRUCT('prod-014' AS product_id, 'AI/ML Pipeline Builder' AS product_name, 5 AS quantity, 299.00 AS unit_price, 1495.00 AS total_price),
    STRUCT('prod-007' AS product_id, 'API Gateway Enterprise' AS product_name, 5 AS quantity, 149.00 AS unit_price, 745.00 AS total_price),
    STRUCT('prod-015' AS product_id, 'Edge Compute Platform' AS product_name, 3 AS quantity, 249.00 AS unit_price, 747.00 AS total_price),
    STRUCT('prod-011' AS product_id, 'Service Mesh Standard' AS product_name, 3 AS quantity, 199.00 AS unit_price, 597.00 AS total_price)],
   STRUCT('1 Raffles Place' AS street, 'Singapore' AS city, NULL AS state, '048616' AS zip, 'SG' AS country),
   TIMESTAMP '2025-05-05 09:00:00', TIMESTAMP '2025-05-05 09:05:00', NULL, NULL),

  ('ord-2025-103', 'user-004', 'Carol Davis', 'carol@dataflow.com',
   'delivered', 'standard', 89.50, 79.00, 7.90, 2.60, 0.00, 'USD', 'mastercard', 'stripe',
   [STRUCT('prod-004' AS product_id, 'CI/CD Pipeline Pro' AS product_name, 1 AS quantity, 89.00 AS unit_price, 89.00 AS total_price)],
   STRUCT('200 Park Ave' AS street, 'New York' AS city, 'NY' AS state, '10166' AS zip, 'US' AS country),
   TIMESTAMP '2025-05-01 14:00:00', TIMESTAMP '2025-05-01 14:05:00',
   TIMESTAMP '2025-05-02 10:00:00', TIMESTAMP '2025-05-04 11:00:00'),

  ('ord-2025-104', 'user-009', 'Ivy Zhang', 'ivy@webscale.io',
   'shipped', 'enterprise', 3200.00, 2800.00, 280.00, 120.00, 0.00, 'USD', 'wire', 'wire',
   [STRUCT('prod-014' AS product_id, 'AI/ML Pipeline Builder' AS product_name, 3 AS quantity, 299.00 AS unit_price, 897.00 AS total_price),
    STRUCT('prod-007' AS product_id, 'API Gateway Enterprise' AS product_name, 3 AS quantity, 149.00 AS unit_price, 447.00 AS total_price),
    STRUCT('prod-009' AS product_id, 'Log Analytics Suite' AS product_name, 5 AS quantity, 79.00 AS unit_price, 395.00 AS total_price),
    STRUCT('prod-015' AS product_id, 'Edge Compute Platform' AS product_name, 2 AS quantity, 249.00 AS unit_price, 498.00 AS total_price),
    STRUCT('prod-005' AS product_id, 'Observability Dashboard Pro' AS product_name, 3 AS quantity, 59.99 AS unit_price, 179.97 AS total_price)],
   STRUCT('100 Century Ave' AS street, 'Shanghai' AS city, NULL AS state, '200120' AS zip, 'CN' AS country),
   TIMESTAMP '2025-05-04 10:00:00', TIMESTAMP '2025-05-04 10:05:00',
   TIMESTAMP '2025-05-05 14:00:00', NULL);

INSERT INTO app_analytics.products
  (product_id, sku, name, description, category, subcategory, brand, price, cost_price,
   attributes, variants, tags, rating_avg, review_count, created_at, updated_at)
VALUES
  ('prod-001', 'CLD-IDE-001', 'Cloud IDE Pro License',
   'Enterprise cloud IDE with real-time collaboration and AI code completion.',
   'Software', 'IDE', 'LocalCloud', 29.99, 8.50,
   [STRUCT('licenseType' AS name, 'subscription' AS value),
    STRUCT('maxUsers' AS name, '1' AS value),
    STRUCT('features' AS name, 'ai-completion,collaboration,debugger' AS value)],
   [STRUCT('var-001' AS variant_id, 'CLD-IDE-001-TEAM' AS sku, NULL AS color, NULL AS size, 79.99 AS price, 500 AS inventory_count)],
   ['ide', 'development', 'cloud'], 4.5, 2340, TIMESTAMP '2024-01-01', TIMESTAMP '2025-05-20'),

  ('prod-007', 'API-GWY-001', 'API Gateway Enterprise',
   'Enterprise API gateway with rate limiting, auth, caching, and analytics.',
   'Networking', 'API Gateway', 'LocalCloud', 149.00, 38.00,
   [STRUCT('maxRPS' AS name, '10000' AS value),
    STRUCT('plugins' AS name, 'auth,rate-limit,cache,transform' AS value)],
   [STRUCT('var-007' AS variant_id, 'API-GWY-001-START' AS sku, NULL AS color, NULL AS size, 49.00 AS price, 2000 AS inventory_count),
    STRUCT('var-008' AS variant_id, 'API-GWY-001-ENT' AS sku, NULL AS color, NULL AS size, 299.00 AS price, 300 AS inventory_count)],
   ['api', 'gateway', 'networking', 'microservices'], 4.7, 3120, TIMESTAMP '2024-01-10', TIMESTAMP '2025-05-20'),

  ('prod-014', 'AI-ML-001', 'AI/ML Pipeline Builder',
   'End-to-end ML platform with feature store, model training, serving, and monitoring.',
   'AI', 'ML Platform', 'LocalCloud', 299.00, 85.00,
   [STRUCT('maxModels' AS name, '50' AS value),
    STRUCT('gpuHours' AS name, '100' AS value)],
   [STRUCT('var-014' AS variant_id, 'AI-ML-001-PRO' AS sku, NULL AS color, NULL AS size, 599.00 AS price, 100 AS inventory_count)],
   ['ai', 'ml', 'machine-learning', 'data-science'], 4.7, 2340, TIMESTAMP '2024-06-15', TIMESTAMP '2025-05-08');

-- Sample queries (uncomment to run):
-- SELECT * FROM app_analytics.daily_event_summary ORDER BY event_date, event_type;
-- SELECT * FROM app_analytics.top_pages_daily ORDER BY event_date, pageviews DESC;
-- SELECT s.user_id, p.page_url, p.time_on_page, e.event_type
-- FROM app_analytics.user_sessions s, UNNEST(s.pages) AS p, UNNEST(p.events) AS e
-- WHERE s.user_id = 'user-001';
-- SELECT DATE_TRUNC(order_date, MONTH) as month, SUM(total_amount) as revenue,
--   SUM(SUM(total_amount)) OVER (ORDER BY DATE_TRUNC(order_date, MONTH)) as running_total
-- FROM app_analytics.orders GROUP BY month ORDER BY month;
