/**
 * Standard Mock Data Generator Utility
 * Generates realistic mock data based on field/column names and database types.
 */

const FIRST_NAMES = ['John', 'Jane', 'Alex', 'Emily', 'Michael', 'Sarah', 'David', 'Jessica', 'Robert', 'Lisa'];
const LAST_NAMES = ['Smith', 'Doe', 'Johnson', 'Williams', 'Brown', 'Jones', 'Miller', 'Davis', 'Wilson', 'Anderson'];
const DOMAINS = ['gmail.com', 'yahoo.com', 'outlook.com', 'example.com', 'localcloud.dev'];
const STATUSES = ['ACTIVE', 'PENDING', 'SUSPENDED', 'CLOSED'];
const TIERS = ['STANDARD', 'BASIC', 'GOLD', 'SILVER'];
const COUNTRIES = ['US', 'GB', 'CA', 'DE', 'FR', 'IN', 'JP', 'AU'];
const CITIES = ['San Francisco', 'New York', 'London', 'Berlin', 'Paris', 'Mumbai', 'Tokyo', 'Sydney'];
const STREETS = ['Main St', 'Oak Ave', 'Pine Rd', 'Broadway', 'Market St', 'Elm St', 'Washington St'];

// Helper to generate a random element from an array
function pickRandom(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

// Helper to generate random digits
function randomDigits(length) {
    let result = '';
    for (let i = 0; i < length; i++) {
        result += Math.floor(Math.random() * 10);
    }
    return result;
}

function cleanDatabaseType(type = 'STRING') {
    const raw = String(type || 'STRING').toUpperCase().trim();
    const match = raw.match(/^(DOUBLE\s+PRECISION|[A-Z0-9_]+)(?:\s*\([^)]*\))?/);
    return match ? match[1].replace(/\s+/g, ' ') : raw.split(/\s+/)[0];
}

/**
 * Generates a mock value for a single field/column.
 * @param {string} columnName Name of the column (case-insensitive)
 * @param {string} type Datatype of the column (e.g. STRING, INT64, BOOL, TIMESTAMP)
 * @returns {any} A generated mock value matching type and context
 */
export function generateMockValue(columnName, type = 'STRING') {
    const nameLower = columnName.toLowerCase();
    const rawType = type.toUpperCase().trim();

    // Handle ARRAY<INNER_TYPE> — generate an array of 2-3 mock values of the inner type.
    const arrayMatch = rawType.match(/^ARRAY\s*<(.+?)>(?:\s|$)/);
    if (arrayMatch) {
        const innerType = cleanDatabaseType(arrayMatch[1]);
        const count = 2 + Math.floor(Math.random() * 2); // 2 or 3
        const items = [];
        for (let i = 0; i < count; i++) {
            items.push(generateMockValue(columnName + '_' + i, innerType));
        }
        return items;
    }

    const cleanType = cleanDatabaseType(rawType);

    // Type-specific values come first so numeric key columns like customer_id
    // do not receive UUID strings.
    switch (cleanType) {
        case 'BOOL':
        case 'BOOLEAN':
            return Math.random() > 0.5;

        case 'INT64':
        case 'INT':
        case 'INTEGER':
        case 'BIGINT':
        case 'SMALLINT':
            return Math.floor(Math.random() * 1000) + 1;

        case 'FLOAT64':
        case 'FLOAT':
        case 'DOUBLE':
        case 'DOUBLE PRECISION':
        case 'REAL':
        case 'NUMERIC':
        case 'DECIMAL':
            return parseFloat((Math.random() * 100).toFixed(4));

        case 'DATE':
            const d = new Date();
            d.setDate(d.getDate() - Math.floor(Math.random() * 365));
            return d.toISOString().split('T')[0];

        case 'TIMESTAMP':
        case 'DATETIME':
        case 'TIME':
            const ts = new Date();
            ts.setMinutes(ts.getMinutes() - Math.floor(Math.random() * 10000));
            return ts.toISOString();

        case 'JSON':
        case 'JSONB':
            return JSON.stringify({
                created_by: 'localcloud-generator',
                version: 1.0,
                status: 'verified'
            });
    }

    // 1. Context-based matching using field names
    if (nameLower.includes('email')) {
        const first = pickRandom(FIRST_NAMES).toLowerCase();
        const last = pickRandom(LAST_NAMES).toLowerCase();
        return `${first}.${last}@${pickRandom(DOMAINS)}`;
    }
    if (nameLower.includes('first_name') || nameLower.includes('firstname')) {
        return pickRandom(FIRST_NAMES);
    }
    if (nameLower.includes('last_name') || nameLower.includes('lastname')) {
        return pickRandom(LAST_NAMES);
    }
    if (nameLower.includes('name')) {
        return `${pickRandom(FIRST_NAMES)} ${pickRandom(LAST_NAMES)}`;
    }
    if (nameLower.includes('phone') || nameLower.includes('tel')) {
        return `+1-555-01${randomDigits(2)}-${randomDigits(4)}`;
    }
    if (nameLower.includes('uuid') || nameLower.includes('guid') || nameLower === 'id' || nameLower.endsWith('_id') || nameLower.endsWith('id')) {
        // Simple random UUIDv4 generator
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }
    if (nameLower.includes('status')) {
        return pickRandom(STATUSES);
    }
    if (nameLower.includes('tier')) {
        return pickRandom(TIERS);
    }
    if (nameLower.includes('country') || nameLower.includes('nationality')) {
        return pickRandom(COUNTRIES);
    }
    if (nameLower.includes('city')) {
        return pickRandom(CITIES);
    }
    if (nameLower.includes('street') || nameLower.includes('address')) {
        return `${Math.floor(Math.random() * 900) + 100} ${pickRandom(STREETS)}`;
    }
    if (nameLower.includes('zip') || nameLower.includes('postal')) {
        return randomDigits(5);
    }
    if (nameLower.includes('gender')) {
        return pickRandom(['MALE', 'FEMALE', 'OTHER']);
    }
    if (nameLower.includes('age')) {
        return Math.floor(Math.random() * 60) + 18;
    }
    if (nameLower.includes('price') || nameLower.includes('amount') || nameLower.includes('balance')) {
        return parseFloat((Math.random() * 500 + 5).toFixed(2));
    }

    // 2. Type-based fallbacks
    switch (cleanType) {
        case 'BYTES':
            // Generate simple random hex bytes
            let hex = '';
            for (let i = 0; i < 16; i++) {
                hex += Math.floor(Math.random() * 256).toString(16).padStart(2, '0');
            }
            return hex;

        case 'STRING':
        default:
            // Generate a random word/short sentence
            const words = ['cloud', 'emulator', 'database', 'service', 'storage', 'secret', 'topic', 'task', 'gateway', 'project'];
            return pickRandom(words) + '-' + randomDigits(3);
    }
}

/**
 * Generates an object representing a full mock row for a table.
 * @param {Array<{name: string, type: string}>} columns List of column definitions with name and type
 * @returns {Object} A row object mapping column names to generated mock values
 */
export function generateMockRow(columns) {
    const row = {};
    for (const col of columns) {
        row[col.name] = generateMockValue(col.name, col.type);
    }
    return row;
}

const EVENT_TYPES = ['user.login', 'user.logout', 'user.signup', 'user.profile.updated', 'user.deleted',
    'order.created', 'order.updated', 'order.cancelled', 'order.shipped', 'order.delivered', 'order.refunded',
    'payment.succeeded', 'payment.failed', 'payment.refunded', 'payment.authorized',
    'product.created', 'product.updated', 'product.deleted', 'product.stock.low',
    'invoice.generated', 'invoice.sent', 'invoice.paid', 'invoice.overdue',
    'notification.email.sent', 'notification.sms.sent', 'notification.push.sent',
    'cart.abandoned', 'cart.updated', 'cart.checked_out',
    'review.created', 'review.updated', 'review.flagged',
    'session.started', 'session.ended', 'page.viewed', 'button.clicked',
    'api.request', 'api.error', 'api.rate_limited',
    'system.health_check', 'system.alert', 'system.config.changed'];

const SOURCES = ['auth-service', 'order-service', 'payment-service', 'notification-service',
    'api-gateway', 'web-app', 'mobile-app', 'admin-panel', 'cron-job', 'webhook'];

const REGIONS = ['us-central1', 'us-east1', 'us-west1', 'europe-west1', 'europe-west4',
    'asia-east1', 'asia-northeast1', 'australia-southeast1'];

const USER_AGENTS = ['Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
    'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)',
    'localcloud-sdk/1.0'];

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = Math.random() * 16 | 0;
        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
}

/**
 * Generates a mock Pub/Sub message payload for a given topic.
 * @param {string} topicName Topic name to contextualize the mock event
 * @returns {Object} Mock message with data (JSON string) and attributes
 */
export function generateMockPubSubMessage(topicName) {
    const topic = (topicName || 'events').toLowerCase().replace(/[-_]/g, ' ');
    const event = pickRandom(EVENT_TYPES);
    const source = pickRandom(SOURCES);
    const region = pickRandom(REGIONS);

    const payload = {
        event,
        id: uuid(),
        timestamp: new Date(Date.now() - Math.floor(Math.random() * 86400000 * 7)).toISOString(),
        source,
        region,
        metadata: {
            traceId: uuid().substring(0, 16),
            userAgent: pickRandom(USER_AGENTS),
            version: `${Math.floor(Math.random() * 3) + 1}.${Math.floor(Math.random() * 10)}.${Math.floor(Math.random() * 20)}`
        }
    };

    if (topic.includes('user') || event.startsWith('user.')) {
        payload.userId = uuid();
        payload.email = `${pickRandom(FIRST_NAMES).toLowerCase()}.${pickRandom(LAST_NAMES).toLowerCase()}@${pickRandom(DOMAINS)}`;
        payload.country = pickRandom(COUNTRIES);
    }

    if (topic.includes('order') || event.startsWith('order.')) {
        payload.orderId = uuid();
        payload.amount = parseFloat((Math.random() * 500 + 10).toFixed(2));
        payload.currency = pickRandom(['USD', 'EUR', 'GBP', 'JPY', 'INR']);
        payload.itemCount = Math.floor(Math.random() * 10) + 1;
    }

    if (topic.includes('payment') || event.startsWith('payment.')) {
        payload.paymentId = uuid();
        payload.amount = parseFloat((Math.random() * 1000 + 5).toFixed(2));
        payload.currency = pickRandom(['USD', 'EUR', 'GBP']);
        payload.method = pickRandom(['credit_card', 'debit_card', 'paypal', 'bank_transfer', 'crypto']);
    }

    if (topic.includes('notification') || event.startsWith('notification.')) {
        payload.channel = pickRandom(['email', 'sms', 'push', 'in_app']);
        payload.recipient = `${pickRandom(FIRST_NAMES).toLowerCase()}.${pickRandom(LAST_NAMES).toLowerCase()}@${pickRandom(DOMAINS)}`;
        payload.template = pickRandom(['welcome', 'reset_password', 'order_confirm', 'shipping_update', 'promo']);
    }

    if (topic.includes('analytics') || topic.includes('tracking') || event.startsWith('session.') || event.startsWith('page.') || event.startsWith('button.')) {
        payload.pageUrl = `https://example.com/${pickRandom(['home', 'products', 'checkout', 'account', 'settings', 'help'])}`;
        payload.sessionId = uuid().substring(0, 16);
        payload.durationMs = Math.floor(Math.random() * 30000) + 100;
    }

    if (topic.includes('api') || event.startsWith('api.')) {
        payload.endpoint = `/api/v${Math.floor(Math.random() * 3) + 1}/${pickRandom(['users', 'orders', 'products', 'payments', 'auth'])}`;
        payload.method = pickRandom(['GET', 'POST', 'PUT', 'DELETE', 'PATCH']);
        payload.statusCode = pickRandom([200, 201, 400, 401, 403, 404, 500, 502, 503]);
        payload.latencyMs = Math.floor(Math.random() * 2000) + 10;
    }

    const attributes = {
        'event-type': event,
        'source': source,
        'region': region,
        'content-type': 'application/json',
        'generated-by': 'localcloud-mock-generator'
    };

    return {
        data: JSON.stringify(payload, null, 2),
        attributes
    };
}

/**
 * Generates multiple mock Pub/Sub messages for a topic.
 * @param {string} topicName Topic name
 * @param {number} count Number of messages to generate
 * @returns {Array<{data: string, attributes: Object}>}
 */
export function generateMockPubSubMessages(topicName, count = 1) {
    const messages = [];
    for (let i = 0; i < count; i++) {
        messages.push(generateMockPubSubMessage(topicName));
    }
    return messages;
}
