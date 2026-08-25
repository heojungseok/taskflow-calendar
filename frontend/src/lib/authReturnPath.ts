const RETURN_PATH_KEY = 'taskflow:return-path';
const RETURN_PATH_TTL_MS = 15 * 60 * 1000;
const FALLBACK_PATH = '/projects';
const ALLOWED_PATHS = [
  /^\/projects(?:\/|$|[?#])/,
  /^\/tasks(?:\/|$|[?#])/,
  /^\/admin\/outbox(?:$|[?#])/,
];

interface ReturnPathRecord {
  path: string;
  createdAt: number;
}

function validPath(path: unknown): path is string {
  return typeof path === 'string'
    && !path.includes('\\')
    && ALLOWED_PATHS.some(pattern => pattern.test(path));
}

function parseReturnPath(raw: string | null, now: number): ReturnPathRecord | null {
  if (raw === null) return null;

  try {
    const record = JSON.parse(raw) as Partial<ReturnPathRecord>;
    const age = now - (record.createdAt ?? Number.NaN);
    return validPath(record.path)
      && typeof record.createdAt === 'number'
      && Number.isFinite(record.createdAt)
      && age >= 0
      && age <= RETURN_PATH_TTL_MS
      ? record as ReturnPathRecord
      : null;
  } catch {
    return null;
  }
}

export function currentReturnPath(): string {
  return window.location.pathname + window.location.search + window.location.hash;
}

export function saveReturnPath(path = currentReturnPath(), now = Date.now()): void {
  try {
    if (parseReturnPath(sessionStorage.getItem(RETURN_PATH_KEY), now)) return;
  } catch {
    // Storage access may be disabled; the login redirect must still continue.
  }

  if (!validPath(path)) return;

  try {
    sessionStorage.setItem(RETURN_PATH_KEY, JSON.stringify({ path, createdAt: now }));
  } catch {
    // Storage access may be disabled; the login redirect must still continue.
  }
}

export function consumeReturnPath(now = Date.now()): string {
  let raw: string | null = null;
  try {
    raw = sessionStorage.getItem(RETURN_PATH_KEY);
  } catch {
    // Fall back below.
  }
  try {
    sessionStorage.removeItem(RETURN_PATH_KEY);
  } catch {
    // Storage access may be disabled; navigation can still continue.
  }
  return parseReturnPath(raw, now)?.path ?? FALLBACK_PATH;
}

export function clearReturnPath(): void {
  try {
    sessionStorage.removeItem(RETURN_PATH_KEY);
  } catch {
    // Storage access may be disabled; navigation can still continue.
  }
}
