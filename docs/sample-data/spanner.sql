-- Cloud Spanner: Comprehensive DDL + Sample Data
-- Features: INTERLEAVE, generated columns, commit timestamps,
--           ARRAY, JSON, NUMERIC, STORING indexes, CHECK, FK,
--           NULL_FILTERED indexes, interleaved with CASCADE/NO ACTION
--
-- This file demonstrates the full complexity of the Spanner DDL parser.
-- Paste into the Spanner query console to execute.

-- ============================================================
-- TABLE DEFINITIONS
-- ============================================================

-- Customers: top-level parent table
CREATE TABLE customers (
  customer_id   STRING(36) NOT NULL,
  email         STRING(255) NOT NULL,
  display_name  STRING(100) NOT NULL,
  tier          STRING(20) NOT NULL DEFAULT ("standard"),
  status        STRING(20) NOT NULL DEFAULT ("active"),
  balance       NUMERIC NOT NULL DEFAULT (0.00),
  lifetime_value NUMERIC NOT NULL DEFAULT (0.00),
  preferred_currency STRING(3) NOT NULL DEFAULT ("USD"),
  tags          ARRAY<STRING(MAX)>,
  metadata      JSON,
  is_verified   BOOL NOT NULL DEFAULT (FALSE),
  signup_channel STRING(50),
  risk_score    FLOAT64,
  created_at    TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  updated_at    TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  CONSTRAINT CHK_customers_tier CHECK (tier IN ('free', 'standard', 'pro', 'enterprise')),
  CONSTRAINT CHK_customers_status CHECK (status IN ('active', 'suspended', 'closed', 'fraud_review'))
) PRIMARY KEY (customer_id);

CREATE UNIQUE INDEX idx_customers_email ON customers(email);

CREATE NULL_FILTERED INDEX idx_customers_tier ON customers(tier, created_at DESC) STORING (display_name, email);

CREATE INDEX idx_customers_risk ON customers(risk_score DESC) WHERE risk_score IS NOT NULL;


-- Orders: interleaved in customers with CASCADE
CREATE TABLE orders (
  customer_id       STRING(36) NOT NULL,
  order_id          STRING(36) NOT NULL,
  status            STRING(30) NOT NULL DEFAULT ("pending"),
  order_type        STRING(20) NOT NULL DEFAULT ("standard"),
  total_amount      NUMERIC NOT NULL,
  subtotal          NUMERIC NOT NULL,
  tax_amount        NUMERIC NOT NULL DEFAULT (0.00),
  shipping_amount   NUMERIC NOT NULL DEFAULT (0.00),
  discount_amount   NUMERIC NOT NULL DEFAULT (0.00),
  currency          STRING(3) NOT NULL DEFAULT ("USD"),
  payment_method    STRING(30),
  transaction_id    STRING(100),
  shipping_address  JSON,
  billing_address   JSON,
  notes             STRING(MAX),
  is_gift           BOOL NOT NULL DEFAULT (FALSE),
  gift_message      STRING(500),
  promo_codes       ARRAY<STRING(MAX)>,
  order_date        TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  paid_at           TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  shipped_at        TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  delivered_at      TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  cancelled_at      TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  cancellation_reason STRING(500),
  returned_at       TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  return_reason     STRING(500),
  metadata          JSON,
  CONSTRAINT CHK_orders_status CHECK (
    status IN ('pending','authorized','processing','shipped','delivered','cancelled','returned','refunded'))
) PRIMARY KEY (customer_id, order_id),
  INTERLEAVE IN PARENT customers ON DELETE CASCADE;

CREATE INDEX idx_orders_status ON orders(status, order_date DESC) STORING (total_amount, currency, customer_id);

CREATE INDEX idx_orders_date ON orders(order_date DESC) STORING (status, total_amount);

-- Order Items: interleaved in orders (grandchild of customers)
-- Demonstrates generated columns (STORED)
CREATE TABLE order_items (
  customer_id   STRING(36) NOT NULL,
  order_id      STRING(36) NOT NULL,
  line_number   INT64 NOT NULL,
  product_id    STRING(36) NOT NULL,
  product_name  STRING(200) NOT NULL,
  product_sku   STRING(50) NOT NULL,
  product_category STRING(100),
  quantity      INT64 NOT NULL,
  unit_price    NUMERIC NOT NULL,
  cost_price    NUMERIC,
  discount_pct  NUMERIC DEFAULT (0),
  tax_rate_pct  NUMERIC DEFAULT (0),
  weight_kg     FLOAT64,
  is_digital    BOOL NOT NULL DEFAULT (FALSE),
  subtotal      NUMERIC AS (quantity * unit_price *
    (CAST(1 AS NUMERIC) - IFNULL(discount_pct, CAST(0 AS NUMERIC)) / CAST(100 AS NUMERIC))) STORED,
  tax           NUMERIC AS (subtotal * IFNULL(tax_rate_pct, CAST(0 AS NUMERIC)) / CAST(100 AS NUMERIC)) STORED,
  total         NUMERIC AS (subtotal + tax) STORED,
  item_metadata JSON,
  created_at    TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (customer_id, order_id, line_number),
  INTERLEAVE IN PARENT orders ON DELETE CASCADE;

-- Payments: interleaved in orders with NO ACTION on delete
CREATE TABLE payments (
  customer_id     STRING(36) NOT NULL,
  order_id        STRING(36) NOT NULL,
  payment_id      STRING(36) NOT NULL,
  payment_method  STRING(30) NOT NULL,
  payment_type    STRING(20) NOT NULL DEFAULT ("one_time"),
  amount          NUMERIC NOT NULL,
  currency        STRING(3) NOT NULL DEFAULT ("USD"),
  gateway         STRING(50),
  gateway_response JSON,
  status          STRING(20) NOT NULL DEFAULT ("pending"),
  avs_check       STRING(10),
  cvv_check       STRING(10),
  processor_fee   NUMERIC DEFAULT (0.00),
  net_amount      NUMERIC AS (amount - IFNULL(processor_fee, CAST(0 AS NUMERIC))) STORED,
  initiated_at    TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  completed_at    TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  failure_code    STRING(50),
  failure_message STRING(500),
  refund_amount   NUMERIC DEFAULT (0.00),
  refunded_at     TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  refund_reason   STRING(500),
  CONSTRAINT CHK_payments_status CHECK (
    status IN ('pending','processing','completed','failed','refunded','partially_refunded'))
) PRIMARY KEY (customer_id, order_id, payment_id),
  INTERLEAVE IN PARENT orders ON DELETE NO ACTION;

CREATE INDEX idx_payments_txn ON payments(transaction_id) WHERE transaction_id IS NOT NULL;

CREATE INDEX idx_payments_gateway ON payments(gateway, status);

-- Products: standalone table with generated inventory column
CREATE TABLE products (
  product_id      STRING(36) NOT NULL,
  sku             STRING(50) NOT NULL,
  name            STRING(200) NOT NULL,
  description     STRING(MAX),
  short_description STRING(500),
  category        STRING(100),
  subcategory     STRING(100),
  brand           STRING(100),
  manufacturer    STRING(200),
  supplier_id     STRING(36),
  price           NUMERIC NOT NULL,
  compare_at_price NUMERIC,
  cost_price      NUMERIC,
  weight_kg       FLOAT64,
  dimensions      STRING(100),
  color           STRING(50),
  size            STRING(50),
  attributes      JSON,
  tags            ARRAY<STRING(MAX)>,
  images          ARRAY<STRING(MAX)>,
  inventory_count INT64 NOT NULL DEFAULT (0),
  inventory_reserved INT64 NOT NULL DEFAULT (0),
  inventory_available INT64 AS (inventory_count - inventory_reserved) STORED,
  reorder_point   INT64 DEFAULT (10),
  is_active       BOOL NOT NULL DEFAULT (TRUE),
  is_digital      BOOL NOT NULL DEFAULT (FALSE),
  requires_shipping BOOL NOT NULL DEFAULT (TRUE),
  tax_category    STRING(50),
  rating_avg      FLOAT64,
  review_count    INT64 DEFAULT (0),
  metadata        JSON,
  created_at      TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  updated_at      TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (product_id);

CREATE UNIQUE INDEX idx_products_sku ON products(sku);

CREATE INDEX idx_products_category ON products(category, subcategory, is_active) STORING (name, price, inventory_count);

CREATE NULL_FILTERED INDEX idx_products_active ON products(is_active, created_at DESC) WHERE is_active = TRUE;

CREATE INDEX idx_products_price ON products(price DESC) STORING (name, category);

-- Inventory Snapshots: interleaved in products
CREATE TABLE inventory_snapshots (
  product_id         STRING(36) NOT NULL,
  snapshot_date      DATE NOT NULL,
  quantity_on_hand   INT64 NOT NULL,
  quantity_reserved  INT64 NOT NULL DEFAULT (0),
  quantity_incoming  INT64 NOT NULL DEFAULT (0),
  quantity_damaged   INT64 NOT NULL DEFAULT (0),
  quantity_available INT64 AS (quantity_on_hand - quantity_reserved - quantity_damaged) STORED,
  warehouse_id       STRING(50),
  warehouse_zone     STRING(50),
  bin_location       STRING(50),
  inspected_by       STRING(100),
  notes              STRING(MAX),
  recorded_at        TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (product_id, snapshot_date),
  INTERLEAVE IN PARENT products ON DELETE CASCADE;

-- Customer Addresses: interleaved in customers
CREATE TABLE customer_addresses (
  customer_id   STRING(36) NOT NULL,
  address_type  STRING(20) NOT NULL,
  address_id    STRING(36) NOT NULL,
  label         STRING(100),
  recipient_name STRING(200) NOT NULL,
  phone         STRING(30),
  street_line1  STRING(200) NOT NULL,
  street_line2  STRING(200),
  city          STRING(100) NOT NULL,
  state         STRING(50),
  postal_code   STRING(20) NOT NULL,
  country       STRING(2) NOT NULL DEFAULT ("US"),
  is_default    BOOL NOT NULL DEFAULT (FALSE),
  is_verified   BOOL NOT NULL DEFAULT (FALSE),
  latitude      FLOAT64,
  longitude     FLOAT64,
  delivery_instructions STRING(500),
  validated_at  TIMESTAMP OPTIONS (allow_commit_timestamp = true),
  created_at    TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  CONSTRAINT CHK_address_type CHECK (address_type IN ('shipping', 'billing', 'both'))
) PRIMARY KEY (customer_id, address_type, address_id),
  INTERLEAVE IN PARENT customers ON DELETE CASCADE;

-- Customer Payment Methods: interleaved in customers
CREATE TABLE customer_payment_methods (
  customer_id     STRING(36) NOT NULL,
  method_id       STRING(36) NOT NULL,
  method_type     STRING(30) NOT NULL,
  display_name    STRING(100),
  is_default      BOOL NOT NULL DEFAULT (FALSE),
  last_four       STRING(4),
  expiry_month    INT64,
  expiry_year     INT64,
  card_brand      STRING(30),
  billing_address STRING(200),
  gateway_token   STRING(500),
  is_expired      BOOL NOT NULL DEFAULT (FALSE),
  created_at      TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  updated_at      TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (customer_id, method_id),
  INTERLEAVE IN PARENT customers ON DELETE CASCADE;

-- Reviews: standalone table with FK constraint
CREATE TABLE reviews (
  review_id    STRING(36) NOT NULL,
  product_id   STRING(36) NOT NULL,
  customer_id  STRING(36) NOT NULL,
  order_id     STRING(36),
  rating       INT64 NOT NULL,
  title        STRING(200),
  body         STRING(MAX),
  is_verified_purchase BOOL NOT NULL DEFAULT (FALSE),
  is_approved  BOOL NOT NULL DEFAULT (FALSE),
  helpful_count INT64 DEFAULT (0),
  images       ARRAY<STRING(MAX)>,
  created_at   TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  updated_at   TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  CONSTRAINT CHK_reviews_rating CHECK (rating >= 1 AND rating <= 5)
) PRIMARY KEY (review_id);

CREATE INDEX idx_reviews_product ON reviews(product_id, rating DESC) STORING (title, customer_id, helpful_count);

CREATE INDEX idx_reviews_customer ON reviews(customer_id, created_at DESC);

-- Audit Log: standalone table
CREATE TABLE audit_log (
  log_id        STRING(36) NOT NULL,
  entity_type   STRING(50) NOT NULL,
  entity_id     STRING(255) NOT NULL,
  action        STRING(30) NOT NULL,
  summary       STRING(500),
  old_values    JSON,
  new_values    JSON,
  changed_by    STRING(255),
  changed_by_ip STRING(45),
  user_agent    STRING(500),
  session_id    STRING(100),
  request_id    STRING(100),
  is_system_action BOOL NOT NULL DEFAULT (FALSE),
  changed_at    TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  CONSTRAINT CHK_audit_action CHECK (
    action IN ('create','update','delete','restore','archive','export','import','login','logout'))
) PRIMARY KEY (log_id);

CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id, changed_at DESC);

CREATE INDEX idx_audit_actor ON audit_log(changed_by, changed_at DESC) STORING (action, entity_type, summary);

CREATE INDEX idx_audit_timestamp ON audit_log(changed_at DESC);

-- Sample queries to verify the schema
-- 1. Total revenue by customer tier (aggregation + JOIN)
-- SELECT c.tier, COUNT(DISTINCT o.order_id) as order_count,
--        SUM(o.total_amount) as total_revenue
-- FROM customers c JOIN orders o ON c.customer_id = o.customer_id
-- GROUP BY c.tier ORDER BY total_revenue DESC;

-- 2. Top 10 products by revenue (generated column + aggregation)
-- SELECT oi.product_name, SUM(oi.total) as revenue
-- FROM order_items oi
-- GROUP BY oi.product_name ORDER BY revenue DESC LIMIT 10;

-- 3. Customer lifetime value with rank (window function)
-- SELECT customer_id, display_name, tier, lifetime_value,
--        RANK() OVER (ORDER BY lifetime_value DESC) as ltv_rank
-- FROM customers;

-- 4. Orders needing attention (expired payments)
-- SELECT o.order_id, o.total_amount, o.order_date,
--        p.status as payment_status, p.failure_message
-- FROM orders o JOIN payments p USING (customer_id, order_id)
-- WHERE o.status != 'delivered' AND p.status IN ('failed', 'pending')
-- ORDER BY o.order_date;
