/** Turn Angular HttpErrorResponse (and Spring problem JSON) into a readable line. */
export function httpErrorMessage(err: unknown, fallback = 'Request failed'): string {
  const e = err as { error?: unknown; message?: string; status?: number } | null;
  const body = e?.error;
  if (typeof body === 'string' && body.trim()) return body;
  if (body && typeof body === 'object') {
    const o = body as Record<string, unknown>;
    for (const key of ['detail', 'message', 'error', 'title']) {
      const v = o[key];
      if (typeof v === 'string' && v.trim() && v !== 'Bad Request') return v;
    }
  }
  if (typeof e?.message === 'string' && e.message && !e.message.startsWith('Http failure')) return e.message;
  if (e?.status === 403) return 'Not allowed (try signing in again).';
  if (e?.status === 400) return 'Invalid request.';
  return fallback;
}
