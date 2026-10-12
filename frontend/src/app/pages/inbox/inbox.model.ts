import { CaseSummary } from '../../core/cases.store';
import { ParentAction, SchoolEventDto, isActionClosed, isActionOverdue } from '../../core/overview.store';
import { isMust, scoreOf } from '../../core/labels';

export interface InboxControls { info: boolean; done: boolean; archived: boolean; minScore: number; }
export const DEFAULT_INBOX_CONTROLS: InboxControls = { info: true, done: false, archived: false, minScore: 0 };
export const INBOX_CONTROLS_KEY = 'schoolagent.inboxControls';
export const INBOX_SECTIONS_KEY = 'schoolagent.inboxCollapsedSections';

export function parseMinimumScore(raw: unknown): number {
  const n = typeof raw === 'number' ? raw : typeof raw === 'string' && /^[0-5]$/.test(raw) ? Number(raw) : 0;
  return Number.isInteger(n) && n >= 0 && n <= 5 ? n : 0;
}

export function readInboxControls(): InboxControls {
  try {
    const raw = localStorage.getItem(INBOX_CONTROLS_KEY);
    if (raw !== null) {
      const v = JSON.parse(raw);
      return { info: v?.info !== false, done: v?.done === true, archived: v?.archived === true, minScore: parseMinimumScore(v?.minScore) };
    }
    // Preserve visibility choices from the former category/history controls.
    const history = JSON.parse(localStorage.getItem('schoolagent.inboxHistory') ?? '{}');
    const view = localStorage.getItem('schoolagent.inboxView');
    return { ...DEFAULT_INBOX_CONTROLS, done: history?.done === true || view === 'done', archived: history?.archived === true || view === 'archived' };
  } catch { return { ...DEFAULT_INBOX_CONTROLS }; }
}

export function writeInboxControls(controls: InboxControls): void {
  try { localStorage.setItem(INBOX_CONTROLS_KEY, JSON.stringify(controls)); } catch { /* private mode */ }
}

export function resolveInboxControls(query: { info: string | null; done: string | null; archived: string | null; minScore: string | null; view?: string | null }): InboxControls {
  const stored = readInboxControls();
  return {
    info: query.info === null ? stored.info : query.info !== '0',
    done: query.done === null ? (query.view === 'done' || stored.done) : query.done === '1',
    archived: query.archived === null ? (query.view === 'archived' || stored.archived) : query.archived === '1',
    minScore: query.minScore === null ? stored.minScore : parseMinimumScore(query.minScore),
  };
}

export function readCollapsedSections(): Set<string> {
  try {
    const v = JSON.parse(localStorage.getItem(INBOX_SECTIONS_KEY) ?? '[]');
    return new Set(Array.isArray(v) ? v.filter(k => typeof k === 'string' && k.length <= 160).slice(0, 500) : []);
  } catch { return new Set(); }
}

export function writeCollapsedSections(sections: Set<string>): void {
  try { localStorage.setItem(INBOX_SECTIONS_KEY, JSON.stringify([...sections])); } catch { /* private mode */ }
}

export interface InboxItem {
  archived?: boolean;
  expiresAt?: string | null;
  informationOnly?: boolean;
  read?: boolean;
  revision?: string;
  canUndo?: boolean;
  childColor?: string | null;
  sourceTypes?: string[];
  sourceDate?: string | null;
  sourceDateLabel?: string | null;
  latestChange?: string | null;
  caseId: string;
  title: string;
  summary: string | null;
  score: number;
  status: string;
  childId?: string | null;
  childName: string | null;
  action: ParentAction | null;
  actionLabel?: string | null;
  event: SchoolEventDto | null;
  overdue: boolean;
  optional: boolean;
}

const CLOSED = new Set(['COMPLETED', 'CANCELLED']);

export function isClosedStatus(status: string): boolean {
  return CLOSED.has(status);
}

const MARKABLE = new Set(['ACTION_REQUIRED', 'WAITING_FOR_PARENT', 'INFORMATION_ONLY', 'NEEDS_REVIEW']);

export function canMarkDone(item: InboxItem): boolean {
  if (CLOSED.has(item.status)) return false;
  if (item.informationOnly) return true;
  if (item.action) return true;
  return MARKABLE.has(item.status);
}

export function pickOpenAction(actions: ParentAction[]): ParentAction | null {
  const open = actions.filter((a) => !isActionClosed(a));
  if (!open.length) return null;
  return [...open].sort((a, b) => {
    const must = Number(isMust(b.required)) - Number(isMust(a.required));
    if (must) return must;
    const overdue = Number(isActionOverdue(b)) - Number(isActionOverdue(a));
    if (overdue) return overdue;
    const da = a.dueAt ? new Date(a.dueAt).getTime() : Number.POSITIVE_INFINITY;
    const db = b.dueAt ? new Date(b.dueAt).getTime() : Number.POSITIVE_INFINITY;
    return da - db;
  })[0];
}

export function pickComingEvent(events: SchoolEventDto[], now: number): SchoolEventDto | null {
  const upcoming = events
    .filter((e) => e.status !== 'CANCELLED')
    .filter((e) => new Date(e.startTime).getTime() >= now - 6 * 3600 * 1000)
    .sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime());
  return upcoming[0] ?? null;
}

export function buildInbox(
  cases: CaseSummary[],
  actions: ParentAction[],
  events: SchoolEventDto[],
  now = Date.now(),
): InboxItem[] {
  const actionsByCase = groupBy(actions, (a) => a.caseId);
  const eventsByCase = groupBy(events, (e) => e.caseId);
  return cases.map((c) => {
    const action = c.informationOnly ? null : pickOpenAction(actionsByCase.get(c.id) ?? []);
    const event = pickComingEvent(eventsByCase.get(c.id) ?? [], now);
    return {
      archived: c.relevance ? Date.parse(c.relevance.expiresAt) <= now : false,
      expiresAt: c.relevance?.expiresAt,
      informationOnly: c.informationOnly ?? (c.status === 'INFORMATION_ONLY'),
      read: c.read, revision: c.revision, canUndo: c.canUndo, childColor: c.childColor, sourceTypes: c.sourceTypes, sourceDate: c.sourceDate, sourceDateLabel: c.sourceDateLabel, latestChange: c.latestChange,
      caseId: c.id,
      title: c.inboxCopy?.title ?? c.title,
      summary: c.inboxCopy ? c.inboxCopy.description : c.summary,
      actionLabel: action ? (c.inboxCopy?.actions[action.id] ?? action.title) : null,
      score: c.score,
      status: c.status,
      childId: c.childId,
      childName: c.childName,
      action,
      event,
      overdue: action ? isActionOverdue(action) : false,
      optional: action ? !isMust(action.required) : false,
    };
  });
}

/** Visibility toggles and minimum importance apply to active and history rows alike. */
export function filterInbox(items: InboxItem[], controls: InboxControls = DEFAULT_INBOX_CONTROLS): InboxItem[] {
  return items.filter(i => {
    if (!controls.info && i.informationOnly) return false;
    if ((scoreOf(i.score) ?? 0) < controls.minScore) return false;
    // Expired read information belongs to Archived once, never to both sections.
    if (i.archived) return controls.archived;
    if (CLOSED.has(i.status) || (i.informationOnly && i.read)) return controls.done;
    return true;
  });
}

function groupBy<T>(list: T[], key: (item: T) => string): Map<string, T[]> {
  const map = new Map<string, T[]>();
  for (const item of list) {
    const k = key(item);
    const bucket = map.get(k);
    if (bucket) bucket.push(item);
    else map.set(k, [item]);
  }
  return map;
}

/** Lane sections, in display order. */
export type InboxGroup = 'overdue' | 'soon' | 'later' | 'undated' | 'info' | 'done' | 'archived';

export const INBOX_GROUPS: InboxGroup[] = ['overdue', 'soon', 'later', 'undated', 'info', 'done', 'archived'];

/** “Due within 7 days” is measured in Berlin calendar days. */
export const SOON_DAYS = 7;

/** The date that places an item: its open action's deadline, otherwise its next event. */
export function itemDate(item: InboxItem): string | null {
  return item.action?.dueAt ?? item.event?.startTime ?? null;
}

function berlinDay(ms: number): number {
  return Date.parse(new Date(ms).toLocaleDateString('en-CA', { timeZone: 'Europe/Berlin' }) + 'T00:00:00Z');
}

/** Calendar days from today (Berlin) to the given instant; negative when past. */
export function daysUntil(iso: string, now = Date.now()): number {
  return Math.round((berlinDay(new Date(iso).getTime()) - berlinDay(now)) / 86400000);
}

export function groupOf(item: InboxItem, now = Date.now()): InboxGroup {
  if (item.archived) return 'archived';
  if (isClosedStatus(item.status) || (item.informationOnly && item.read)) return 'done';
  if (item.overdue) return 'overdue';
  const date = itemDate(item);
  if (date) return daysUntil(date, now) <= SOON_DAYS ? 'soon' : 'later';
  return item.informationOnly ? 'info' : 'undated';
}

export interface InboxSection {
  group: InboxGroup;
  items: InboxItem[];
}

function byDate(a: InboxItem, b: InboxItem): number {
  const da = itemDate(a), db = itemDate(b);
  const diff = (da ? new Date(da).getTime() : Infinity) - (db ? new Date(db).getTime() : Infinity);
  return diff || (scoreOf(b.score) ?? 0) - (scoreOf(a.score) ?? 0);
}

function byImportance(a: InboxItem, b: InboxItem): number {
  const score = (scoreOf(b.score) ?? 0) - (scoreOf(a.score) ?? 0);
  if (score) return score;
  return (b.sourceDate ? Date.parse(b.sourceDate) : 0) - (a.sourceDate ? Date.parse(a.sourceDate) : 0);
}

/** Splits already-filtered rows into lane sections; empty sections are dropped. */
export function groupInbox(items: InboxItem[], now = Date.now()): InboxSection[] {
  const buckets = new Map<InboxGroup, InboxItem[]>(INBOX_GROUPS.map((g) => [g, []]));
  for (const item of items) buckets.get(groupOf(item, now))!.push(item);
  return INBOX_GROUPS
    .map((group) => {
      const list = buckets.get(group)!;
      const sorted = group === 'done' ? list : [...list].sort(group === 'undated' || group === 'info' ? byImportance : byDate);
      return { group, items: sorted };
    })
    .filter((section) => section.items.length > 0);
}

/** One list per child in the given order; rows without a child are shared “both kids” rows. */
export function splitByChild(items: InboxItem[], childIds: string[]): { lanes: Map<string, InboxItem[]>; shared: InboxItem[] } {
  const lanes = new Map<string, InboxItem[]>(childIds.map((id) => [id, []]));
  const shared: InboxItem[] = [];
  for (const item of items) {
    const lane = item.childId ? lanes.get(item.childId) : undefined;
    if (lane) lane.push(item);
    else shared.push(item);
  }
  return { lanes, shared };
}

export type InboxLayout = 'lanes' | 'together';

export const INBOX_LAYOUT_KEY = 'schoolagent.inboxLayout';

/** The stored choice, else the fallback (phones start with Together, where urgent rows of every child come first). */
export function readInboxLayout(fallback: InboxLayout = 'lanes'): InboxLayout {
  try {
    const stored = localStorage.getItem(INBOX_LAYOUT_KEY);
    return stored === 'lanes' || stored === 'together' ? stored : fallback;
  } catch {
    return fallback;
  }
}

export function writeInboxLayout(layout: InboxLayout): void {
  try {
    localStorage.setItem(INBOX_LAYOUT_KEY, layout);
  } catch {
    /* private mode */
  }
}
