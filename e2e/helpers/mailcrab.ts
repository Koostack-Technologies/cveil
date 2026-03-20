/**
 * MailCrab HTTP API client for e2e tests.
 *
 * MailCrab is a lightweight SMTP trap that captures all outbound emails
 * and exposes them via a REST API. This helper provides typed access to
 * that API for asserting on verification emails during e2e tests.
 *
 * @see https://github.com/tweedegolf/mailcrab
 */

const MAILCRAB_URL = process.env.MAILCRAB_URL ?? 'http://localhost:1080';

// Keycloak inside Docker uses the container hostname; tests run on the host.
const KEYCLOAK_INTERNAL = process.env.KEYCLOAK_INTERNAL_HOST ?? 'keycloak:8080';
const KEYCLOAK_EXTERNAL = process.env.KEYCLOAK_EXTERNAL_HOST ?? 'localhost:8180';

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

export interface MailCrabMessage {
  id: string;
  from: { name: string; email: string };
  to: { name: string | null; email: string }[];
  subject: string;
  time: number;
}

export interface MailCrabMessageDetail extends MailCrabMessage {
  text: string;
  html: string;
}

/* ------------------------------------------------------------------ */
/*  API methods                                                        */
/* ------------------------------------------------------------------ */

/** Fetch all captured messages. */
export async function getMessages(): Promise<MailCrabMessage[]> {
  const res = await fetch(`${MAILCRAB_URL}/api/messages`);
  if (!res.ok) throw new Error(`MailCrab GET /api/messages failed: ${res.status}`);
  return res.json();
}

/** Fetch a single message with full body content. */
export async function getMessageById(id: string): Promise<MailCrabMessageDetail> {
  const res = await fetch(`${MAILCRAB_URL}/api/message/${id}`);
  if (!res.ok) throw new Error(`MailCrab GET /api/message/${id} failed: ${res.status}`);
  return res.json();
}

/** Return all messages whose recipient list includes the given email. */
export async function getMessagesForRecipient(email: string): Promise<MailCrabMessage[]> {
  const all = await getMessages();
  return all.filter((m) => m.to.some((r) => r.email.toLowerCase() === email.toLowerCase()));
}

/**
 * Poll MailCrab until at least one message arrives for `recipient`.
 * Returns the first matching message (with full body).
 */
export async function waitForMessage(
  recipient: string,
  opts: { timeout?: number; interval?: number } = {},
): Promise<MailCrabMessageDetail> {
  const timeout = opts.timeout ?? 30_000;
  const interval = opts.interval ?? 1_000;
  const deadline = Date.now() + timeout;

  while (Date.now() < deadline) {
    const msgs = await getMessagesForRecipient(recipient);
    if (msgs.length > 0) {
      return getMessageById(msgs[0].id);
    }
    await new Promise((r) => setTimeout(r, interval));
  }

  throw new Error(`No email for ${recipient} within ${timeout}ms`);
}

/**
 * Extract the verification link from a verification email body.
 * Supports both:
 *   - Direct app callback links (/verify-email/callback?key=...)
 *   - Legacy Keycloak action token links (/realms/.../action-token?key=...)
 */
export function extractVerificationLink(message: MailCrabMessageDetail): string {
  const body = message.html || message.text;

  // 1. App callback link (href in HTML)
  const callbackHref = body.match(/href="([^"]*verify-email\/callback\?key=[^"]*)"/i);
  if (callbackHref) return rewriteUrl(callbackHref[1]);

  // 2. Keycloak action token link (href in HTML)
  const kcHref = body.match(/href="([^"]*realms[^"]*action[^"]*)"/i);
  if (kcHref) return rewriteUrl(kcHref[1]);

  // 3. Bare URL fallback (plain text)
  const callbackUrl = body.match(/(https?:\/\/[^\s]*verify-email\/callback\?key=[^\s]*)/i);
  if (callbackUrl) return rewriteUrl(callbackUrl[1]);

  const kcUrl = body.match(/(https?:\/\/[^\s]*realms[^\s]*action[^\s]*)/i);
  if (kcUrl) return rewriteUrl(kcUrl[1]);

  throw new Error('No verification link found in email body');
}

/**
 * Rewrite Docker-internal Keycloak URLs to host-accessible URLs.
 * e.g. http://keycloak:8080/realms/... → http://localhost:8180/realms/...
 */
export function rewriteUrl(url: string): string {
  return url.replace(KEYCLOAK_INTERNAL, KEYCLOAK_EXTERNAL);
}

/** Delete all captured messages. */
export async function deleteAllMessages(): Promise<void> {
  const res = await fetch(`${MAILCRAB_URL}/api/delete-all`, { method: 'POST' });
  if (!res.ok) throw new Error(`MailCrab POST /api/delete-all failed: ${res.status}`);
}
