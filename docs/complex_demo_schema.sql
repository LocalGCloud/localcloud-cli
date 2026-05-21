-- ============================================================
-- Spanner: Complex E-Commerce Schema
-- 8 tables, 8 indexes, 3-level interleaving, generated columns
-- ============================================================

CREATE TABLE customers (
  customer_id STRING(36) NOT NULL,
  email STRING(255) NOT NULL,
  display_name STRING(100),
  tier STRING(20),
  metadata JSON,
  created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  updated_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
) PRIMARY KEY(customer_id);

CREATE INDEX idx_customers_email ON customers(email);

CREATE NULL_FILTERED INDEX idx_customers_tier ON customers(tier);

CREATE TABLE products (
  product_id STRING(36) NOT NULL,
  sku STRING(50) NOT NULL,
  name STRING(200) NOT NULL,
  description STRING(MAX),
  price NUMERIC NOT NULL,
  attributes JSON,
  inventory_count INT64 NOT NULL DEFAULT (0),
  is_active BOOL NOT NULL DEFAULT (TRUE),
  created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
) PRIMARY KEY(product_id);

CREATE UNIQUE INDEX idx_products_sku ON products(sku);

CREATE TABLE orders (
  customer_id STRING(36) NOT NULL,
  order_id STRING(36) NOT NULL,
  status STRING(30) NOT NULL DEFAULT ("pending"),
  total_amount NUMERIC NOT NULL,
  currency STRING(3) NOT NULL DEFAULT ("USD"),
  shipping_address JSON,
  notes STRING(MAX),
  order_date TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  shipped_at TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  delivered_at TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  cancelled_at TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  cancellation_reason STRING(500),
  promo_code STRING(50),
  discount_amount NUMERIC,
  tax_amount NUMERIC,
  metadata JSON,
) PRIMARY KEY(customer_id, order_id),
  INTERLEAVE IN PARENT customers ON DELETE CASCADE;

CREATE INDEX idx_orders_customer ON orders(customer_id, order_date DESC);

CREATE INDEX idx_orders_status ON orders(status, order_date DESC) STORING (total_amount, currency);

CREATE TABLE order_items (
  customer_id STRING(36) NOT NULL,
  order_id STRING(36) NOT NULL,
  line_number INT64 NOT NULL,
  product_id STRING(36) NOT NULL,
  quantity INT64 NOT NULL,
  unit_price NUMERIC NOT NULL,
  discount_pct NUMERIC DEFAULT (0),
  tax_rate NUMERIC DEFAULT (0),
  subtotal NUMERIC AS (quantity * unit_price * (CAST(1 AS NUMERIC) - discount_pct / CAST(100 AS NUMERIC))) STORED,
  tax_amount NUMERIC AS (quantity * unit_price * (CAST(1 AS NUMERIC) - discount_pct / CAST(100 AS NUMERIC)) * tax_rate / CAST(100 AS NUMERIC)) STORED,
  total NUMERIC AS (quantity * unit_price * (CAST(1 AS NUMERIC) - discount_pct / CAST(100 AS NUMERIC)) * (CAST(1 AS NUMERIC) + tax_rate / CAST(100 AS NUMERIC))) STORED,
  item_metadata JSON,
  created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
) PRIMARY KEY(customer_id, order_id, line_number),
  INTERLEAVE IN PARENT orders ON DELETE CASCADE;

CREATE TABLE payments (
  customer_id STRING(36) NOT NULL,
  order_id STRING(36) NOT NULL,
  payment_id STRING(36) NOT NULL,
  payment_method STRING(30) NOT NULL,
  amount NUMERIC NOT NULL,
  currency STRING(3) NOT NULL DEFAULT ("USD"),
  status STRING(20) NOT NULL DEFAULT ("pending"),
  transaction_id STRING(100),
  gateway_response JSON,
  initiated_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  completed_at TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  failure_reason STRING(500),
  refund_amount NUMERIC,
  refunded_at TIMESTAMP OPTIONS (allow_commit_timestamp = true),
) PRIMARY KEY(customer_id, order_id, payment_id),
  INTERLEAVE IN PARENT orders ON DELETE NO ACTION;

CREATE INDEX idx_payments_customer ON payments(customer_id, initiated_at DESC) STORING (status, amount, payment_method);

CREATE INDEX idx_payments_order ON payments(customer_id, order_id) STORING (status, amount);

CREATE TABLE inventory_snapshots (
  product_id STRING(36) NOT NULL,
  snapshot_date DATE NOT NULL,
  quantity_on_hand INT64 NOT NULL,
  quantity_reserved INT64 NOT NULL DEFAULT (0),
  quantity_available INT64 AS (quantity_on_hand - quantity_reserved) STORED,
  warehouse_location STRING(50),
  notes STRING(MAX),
  recorded_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
) PRIMARY KEY(product_id, snapshot_date),
  INTERLEAVE IN PARENT products ON DELETE CASCADE;

CREATE TABLE customer_addresses (
  customer_id STRING(36) NOT NULL,
  address_type STRING(20) NOT NULL,
  address_id STRING(36) NOT NULL,
  street_address STRING(200) NOT NULL,
  city STRING(100) NOT NULL,
  state STRING(50),
  postal_code STRING(20) NOT NULL,
  country STRING(2) NOT NULL DEFAULT ("US"),
  is_default BOOL NOT NULL DEFAULT (FALSE),
  validated_at TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
) PRIMARY KEY(customer_id, address_type, address_id),
  INTERLEAVE IN PARENT customers ON DELETE CASCADE;

CREATE TABLE audit_log (
  log_id STRING(36) NOT NULL,
  entity_type STRING(50) NOT NULL,
  entity_id STRING(36) NOT NULL,
  action STRING(30) NOT NULL,
  old_values JSON,
  new_values JSON,
  changed_by STRING(36),
  changed_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  ip_address STRING(45),
  user_agent STRING(500),
) PRIMARY KEY(log_id);

CREATE INDEX idx_audit_actor ON audit_log(changed_by, changed_at DESC) STORING (entity_type, action);

CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id, changed_at DESC);
