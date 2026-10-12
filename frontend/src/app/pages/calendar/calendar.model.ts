/** Pure helpers for the calendar grid (no Angular), so they are easy to test. */

/** Words that say nothing on their own at the start of a label. */
const FILLER = new Set([
  'the', 'a', 'an', 'your', 'our', 'new', 'reminder', 'info', 'information', 're', 'fwd', 'aw', 'wg',
  'der', 'die', 'das', 'ein', 'eine', 'erinnerung', 'neu', 'neue', 'neuer',
]);

/** Small words that should not end a two-word label (“Bring an …”, “Trip to …”). */
const LINKS = new Set([
  'to', 'for', 'on', 'in', 'at', 'of', 'and', 'or', 'with', 'by', 'from', 'a', 'an', 'the',
  'zu', 'zum', 'zur', 'für', 'am', 'im', 'an', 'und', 'oder', 'mit', 'von', 'vom', 'bis',
]);

const clean = (word: string) => word.replace(/^[^\p{L}\p{N}€]+|[^\p{L}\p{N}€]+$/gu, '');

/**
 * A two-word-at-most label for the narrow phone grid, from text we already have:
 * the phrase before any separator, without leading filler; one long word is enough,
 * a short first word takes the next meaningful word (“Early pickup”, “Class photo”).
 */
export function shortLabel(title: string): string {
  // Mail-style prefixes (“Re:”, “Fwd:”, “Reminder:”) come before the real subject.
  const subject = title.replace(/^\s*((re|fwd?|aw|wg|reminder|erinnerung|info|update)\s*:\s*)+/i, '');
  const phrase = subject.split(/\s[—–:|(]\s?|:\s|,\s|\s-\s/)[0] ?? subject;
  const words = phrase.split(/\s+/).map(clean).filter(Boolean);
  while (words.length > 1 && FILLER.has(words[0].toLowerCase())) words.shift();
  const first = words[0];
  if (!first) return clean(title) || title;
  if (first.length >= 8) return first;
  const next = words.slice(1).find((w) => !LINKS.has(w.toLowerCase()));
  return next ? `${first} ${next}` : first;
}

export interface GridEntry {
  score: number;
  hot: boolean;
}

export interface CellLayout<T> {
  /** Desktop chips, most important first. */
  shown: T[];
  /** Entries not shown as desktop chips. */
  more: number;
  /** Phone: important entries that keep a short label. */
  labels: T[];
  /** Phone: the rest as coloured dots. */
  dots: T[];
  /** Phone: entries neither labelled nor dotted. */
  phoneMore: number;
}

/**
 * Decides what a day cell shows. Entries are ordered by importance before anything is
 * cut, so an urgent entry can never be the one that falls off a crowded day, and every
 * cut leaves a visible “+N”.
 */
export function layoutCell<T extends GridEntry>(entries: T[], desk = 3, phoneLabels = 2, phoneDots = 3): CellLayout<T> {
  const sorted = [...entries].sort((a, b) => b.score - a.score);
  const labels = sorted.filter((e) => e.hot).slice(0, phoneLabels);
  const dots = sorted.filter((e) => !labels.includes(e)).slice(0, phoneDots);
  return {
    shown: sorted.slice(0, desk),
    more: Math.max(0, sorted.length - desk),
    labels,
    dots,
    phoneMore: sorted.length - labels.length - dots.length,
  };
}
