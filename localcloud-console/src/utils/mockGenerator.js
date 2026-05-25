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

/**
 * Generates a mock value for a single field/column.
 * @param {string} columnName Name of the column (case-insensitive)
 * @param {string} type Datatype of the column (e.g. STRING, INT64, BOOL, TIMESTAMP)
 * @returns {any} A generated mock value matching type and context
 */
export function generateMockValue(columnName, type = 'STRING') {
    const nameLower = columnName.toLowerCase();
    const cleanType = type.toUpperCase().split('(')[0].trim();

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
