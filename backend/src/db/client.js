/**
 * Postgres connection (pg Pool) wrapped by Drizzle. The whole backend is
 * DB-agnostic via DATABASE_URL — Railway injects it in prod; locally it points
 * at Docker Postgres or any dev DB (PHASE2.md §7).
 */
import pg from 'pg';
import { drizzle } from 'drizzle-orm/node-postgres';
import * as schema from './schema.js';

const { Pool } = pg;

const connectionString = process.env.DATABASE_URL;
if (!connectionString) {
  throw new Error('DATABASE_URL is required. Copy .env.example and fill it in.');
}

// Railway's managed Postgres requires SSL; local Docker does not. Enable SSL
// unless DATABASE_URL is clearly a localhost connection.
const isLocal = /@(localhost|127\.0\.0\.1|postgres)[:/]/.test(connectionString);

export const pool = new Pool({
  connectionString,
  ssl: isLocal ? false : { rejectUnauthorized: false },
});

export const db = drizzle(pool, { schema });
