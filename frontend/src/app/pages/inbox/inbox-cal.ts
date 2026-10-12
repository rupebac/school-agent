import { CalendarEntry } from '../../core/briefing.store';
import { SchoolEventDto } from '../../core/overview.store';

export function dayKey(iso: string): string {
  return new Date(iso).toLocaleDateString('en-CA', { timeZone: 'Europe/Berlin' });
}

export function startOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1);
}

export function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export interface CalMark {
  id: string;
  caseId: string;
  title: string;
  day: string;
}

export interface DayCell {
  key: string;
  day: number;
  events: SchoolEventDto[];
  actions: CalMark[];
  requirements: CalMark[];
  today: boolean;
  inMonth: boolean;
  weekend: boolean;
  offName: string | null;
  offType: string | null;
}

function covers(entry: CalendarEntry, key: string): boolean {
  return entry.startDate <= key && key <= entry.endDate;
}

function offFor(key: string, holidays: CalendarEntry[]): CalendarEntry | null {
  const hits = holidays.filter((h) => covers(h, key));
  if (!hits.length) return null;
  return { ...hits[0], name: [...new Set(hits.map(h => h.name))].join(' · ') };
}

function marksOn(key: string, marks: CalMark[]): CalMark[] {
  return uniqueByCase(marks.filter((m) => m.day === key));
}

function uniqueByCase<T extends { caseId: string }>(items: T[]): T[] {
  const seen = new Set<string>();
  return items.filter((item) => {
    if (seen.has(item.caseId)) return false;
    seen.add(item.caseId);
    return true;
  });
}

export type AgendaItem = { kind: string; title: string; caseId: string; id: string };

export type CalShow = { events: boolean; exams?: boolean };

/** One school matter per day — not every extracted event/action paraphrase. */
export function collapseDayAgenda(items: AgendaItem[]): AgendaItem[] {
  const rank: Record<string, number> = { action: 2, event: 1, requirement: 0 };
  const byCase = new Map<string, AgendaItem>();
  for (const item of items) {
    const prev = byCase.get(item.caseId);
    if (!prev || (rank[item.kind] ?? 0) > (rank[prev.kind] ?? 0)) byCase.set(item.caseId, item);
  }
  return [...byCase.values()];
}

export function cellAgenda(
  cell: Pick<DayCell, 'events' | 'actions' | 'requirements'>,
  show: CalShow,
): AgendaItem[] {
  if (!show.events && !show.exams) return [];
  const items: AgendaItem[] = [];
  for (const e of cell.events.filter(e => e.eventType === 'EXAM' ? (show.exams ?? show.events) : show.events)) items.push({ kind: 'event', title: e.title, caseId: e.caseId, id: e.id });
  for (const a of (show.events ? cell.actions : [])) items.push({ kind: 'action', title: a.title, caseId: a.caseId, id: a.id });
  for (const r of (show.events ? cell.requirements : [])) items.push({ kind: 'requirement', title: r.title, caseId: r.caseId, id: r.id });
  return collapseDayAgenda(items).map((item) => ({ ...item, kind: 'event' }));
}

export function monthCells(
  month: Date,
  events: SchoolEventDto[],
  holidays: CalendarEntry[] = [],
  actions: CalMark[] = [],
  requirements: CalMark[] = [],
): DayCell[] {
  const year = month.getFullYear();
  const m = month.getMonth();
  const first = new Date(year, m, 1);
  const startWeekday = (first.getDay() + 6) % 7;
  const daysInMonth = new Date(year, m + 1, 0).getDate();
  const todayKey = new Date().toLocaleDateString('en-CA', { timeZone: 'Europe/Berlin' });
  const byDay = new Map<string, SchoolEventDto[]>();
  for (const e of events) {
    if (e.status === 'CANCELLED') continue;
    const k = dayKey(e.startTime);
    const list = byDay.get(k) ?? [];
    list.push(e);
    byDay.set(k, list);
  }
  const cells: DayCell[] = [];
  for (let i = 0; i < startWeekday; i++) {
    cells.push({
      key: `pad-${i}`,
      day: 0,
      events: [],
      actions: [],
      requirements: [],
      today: false,
      inMonth: false,
      weekend: false,
      offName: null,
      offType: null,
    });
  }
  for (let day = 1; day <= daysInMonth; day++) {
    const date = new Date(year, m, day);
    const key = isoDate(date);
    const dow = date.getDay();
    const off = offFor(key, holidays);
    cells.push({
      key,
      day,
      events: events.filter(e => e.status !== 'CANCELLED' && dayKey(e.startTime) <= key && dayKey(e.endTime ?? e.startTime) >= key),
      actions: marksOn(key, actions),
      requirements: marksOn(key, requirements),
      today: key === todayKey,
      inMonth: true,
      weekend: dow === 0 || dow === 6,
      offName: off?.name ?? null,
      offType: off?.entryType ?? null,
    });
  }
  return cells;
}

export function monthRange(cursor: Date): { from: Date; to: Date } {
  const from = new Date(cursor.getFullYear(), cursor.getMonth(), 1);
  const to = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1);
  const inboxFrom = new Date(Date.now() - 7 * 24 * 3600 * 1000);
  const inboxTo = new Date(Date.now() + 90 * 24 * 3600 * 1000);
  return {
    from: from < inboxFrom ? from : inboxFrom,
    to: to > inboxTo ? to : inboxTo,
  };
}
