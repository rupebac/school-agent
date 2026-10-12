import { Component, computed, effect, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Cases, CaseSummary } from '../../core/cases.store';
import { Auth } from '../../core/auth';
import { KidsStore } from '../../core/kids.store';
import { SchoolCalendarStore } from '../../core/briefing.store';
import { ActionsStore, EventsStore, RequirementsStore, isActionClosed, isActionOverdue } from '../../core/overview.store';
import { I18n, LocDatePipe, TranslatePipe } from '../../core/i18n';
import { Icon } from '../../core/icon';
import { isClosedStatus } from '../inbox/inbox.model';
import { CellLayout, layoutCell, shortLabel } from './calendar.model';
import { AgendaItem, CalMark, DayCell, cellAgenda, dayKey, isoDate, monthCells, monthRange, startOfMonth } from '../inbox/inbox-cal';

/** One dated school matter, for the agenda lists. */
interface Upcoming {
  caseId: string;
  title: string;
  day: string;
  when: string;
  detail: string;
  childId: string | null;
  color: string | null;
}

interface Chip {
  item: AgendaItem;
  label: string;
  /** A couple of words for the narrow phone grid. */
  short: string;
  score: number;
  /** Importance 4–5: labelled on phones instead of a bare dot. */
  hot: boolean;
  time: string | null;
  color: string;
  due: boolean;
  late: boolean;
  exam: boolean;
}

@Component({
  imports: [RouterLink, TranslatePipe, LocDatePipe, Icon],
  selector: 'app-calendar-page',
  styleUrl: './calendar.css',
  templateUrl: './calendar.html',
})
export class CalendarPage implements OnInit {
  readonly cases = inject(Cases);
  readonly actions = inject(ActionsStore);
  readonly events = inject(EventsStore);
  readonly requirements = inject(RequirementsStore);
  readonly schoolCalendar = inject(SchoolCalendarStore);
  readonly kids = inject(KidsStore);
  readonly auth = inject(Auth);
  readonly i18n = inject(I18n);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly cursor = signal(startOfMonth(new Date()));
  readonly selected = signal<string>(dayKey(new Date().toISOString()));
  readonly show = signal({ events: true, exams: true, holidays: true, finished: false });

  constructor() {
    effect(() => this.cases.setKid(this.kids.activeKidId()));
  }

  readonly legendKids = computed(() => {
    const active = this.kids.activeKid();
    return active ? [active] : this.kids.kids();
  });

  readonly caseMap = computed(() => new Map(this.cases.cases().map(c => [c.id, c])));
  private openCase(caseId: string): CaseSummary | undefined {
    const c = this.caseMap().get(caseId);
    if (!c) return undefined;
    return this.show().finished || (!isClosedStatus(c.status) && !c.relevance?.archived) ? c : undefined;
  }

  private readonly visibleEvents = computed(() => this.events.events().filter(e => !!this.openCase(e.caseId) && (e.eventType === 'EXAM' ? this.show().exams : this.show().events)));
  private readonly actionMarks = computed((): CalMark[] =>
    this.actions.actions()
      .filter(a => this.show().events && a.dueAt && !isActionClosed(a) && this.openCase(a.caseId) && !this.caseMap().get(a.caseId)?.informationOnly)
      .map(a => ({ id: a.id, caseId: a.caseId, title: a.title, day: dayKey(a.dueAt!) })),
  );
  private readonly requirementMarks = computed((): CalMark[] =>
    this.requirements.requirements()
      .filter(r => this.show().events && r.status === 'OPEN' && !!r.relevantOn && this.openCase(r.caseId) && !this.caseMap().get(r.caseId)?.informationOnly)
      .map(r => ({ id: r.id, caseId: r.caseId, title: r.description, day: r.relevantOn! })),
  );
  private readonly holidays = computed(() => {
    const active = this.kids.activeKidId();
    return this.schoolCalendar.entries()
      .filter(h => !h.childId || !active || h.childId === active)
      .map(h => ({ ...h, name: !active && h.childId ? `${this.kids.kids().find(k => k.id === h.childId)?.name ?? ''}: ${h.name}` : h.name }));
  });

  readonly cells = computed(() =>
    monthCells(this.cursor(), this.visibleEvents(), this.holidays(), this.actionMarks(), this.requirementMarks()),
  );

  /** Empty cells that complete the last week row. */
  readonly trailing = computed(() => Array.from({ length: (7 - (this.cells().length % 7)) % 7 }, (_, i) => i));

  readonly monthLabel = computed(() => {
    this.i18n.lang();
    return this.cursor().toLocaleDateString(this.i18n.locale(), { month: 'long', year: 'numeric', timeZone: 'Europe/Berlin' });
  });

  offName(cell: DayCell): string | null {
    return this.show().holidays ? cell.offName : null;
  }

  chips(cell: DayCell): Chip[] {
    const lateIds = new Set(this.actions.actions().filter(a => this.show().events && isActionOverdue(a)).map(a => a.id));
    return cellAgenda(cell, this.show()).map(item => {
      const c = this.caseMap().get(item.caseId);
      const ev = cell.events.find(e => e.id === item.id);
      const due = cell.actions.some(a => a.id === item.id);
      const label = c?.title ?? item.title;
      // An event's own title (“Class photo”, “Wandertag Andechs”) is usually the best short name.
      const eventTitle = cell.events.find(e => e.caseId === item.caseId)?.title;
      const score = c?.score ?? 0;
      return {
        item,
        label,
        short: shortLabel(!due && eventTitle ? eventTitle : label),
        score,
        hot: score >= 4,
        time: ev && !ev.allDay && dayKey(ev.startTime) === cell.key ? this.time(ev.startTime) : null,
        color: c?.childColor || 'var(--muted)',
        due,
        exam: ev?.eventType === 'EXAM',
        late: due && lateIds.has(item.id),
      };
    });
  }

  /** What each day shows on desktop and phone, computed once per render. */
  readonly layouts = computed(() => {
    const map = new Map<string, CellLayout<Chip>>();
    for (const cell of this.cells()) if (cell.inMonth) map.set(cell.key, layoutCell(this.chips(cell)));
    return map;
  });

  layout(cell: DayCell): CellLayout<Chip> {
    return this.layouts().get(cell.key) ?? layoutCell<Chip>([]);
  }

  cellLabel(cell: DayCell): string {
    return [cell.key, this.offName(cell), ...this.chips(cell).map(c => c.label)].filter(Boolean).join(' · ');
  }

  readonly selectedCell = computed(() => this.cells().find(c => c.key === this.selected()) ?? null);

  /** Everything on the selected day, with time, place and what kind of entry it is. */
  readonly dayAgenda = computed(() => {
    const cell = this.selectedCell();
    if (!cell) return [];
    return cellAgenda(cell, this.show()).map(item => {
      const c = this.caseMap().get(item.caseId);
      const details = [
        ...cell.events.filter(e => e.caseId === item.caseId).map(e => [e.title, e.allDay ? '' : this.time(e.startTime), e.location].filter(Boolean).join(' · ')),
        ...cell.actions.filter(a => a.caseId === item.caseId).map(a => this.i18n.t('cal.dueLine', { title: a.title })),
        ...cell.requirements.filter(r => r.caseId === item.caseId).map(r => this.i18n.t('cal.bringLine', { title: r.title })),
      ];
      return { caseId: item.caseId, title: c?.title ?? item.title, detail: details.join(' · '), color: c?.childColor ?? null, child: c?.childName ?? null };
    });
  });

  readonly overdue = computed((): Upcoming[] =>
    this.actions.actions()
      .filter(a => this.show().events && isActionOverdue(a) && !!this.openCase(a.caseId) && !isClosedStatus(this.caseMap().get(a.caseId)!.status) && !this.caseMap().get(a.caseId)?.informationOnly)
      .map(a => this.upcoming(a.caseId, a.dueAt ?? '', a.title)),
  );

  /** The next dated matters per child (one entry per case), from today on. */
  readonly comingByKid = computed(() => {
    const today = dayKey(new Date().toISOString());
    const byCase = new Map<string, Upcoming>();
    const consider = (u: Upcoming) => {
      if (u.day < today) return;
      const prev = byCase.get(u.caseId);
      if (!prev || u.day < prev.day) byCase.set(u.caseId, u);
    };
    for (const e of this.visibleEvents()) {
      if (e.status === 'CANCELLED' || isClosedStatus(this.caseMap().get(e.caseId)!.status)) continue;
      consider(this.upcoming(e.caseId, e.startTime, [e.allDay ? '' : this.time(e.startTime), e.location].filter(Boolean).join(' · ')));
    }
    for (const a of this.actions.actions()) {
      if (!this.show().events || !a.dueAt || isActionClosed(a) || !this.openCase(a.caseId) || isClosedStatus(this.caseMap().get(a.caseId)!.status)) continue;
      if (this.caseMap().get(a.caseId)?.informationOnly) continue;
      consider(this.upcoming(a.caseId, a.dueAt, this.i18n.t('cal.dueLine', { title: a.title })));
    }
    const sorted = [...byCase.values()].sort((a, b) => a.day.localeCompare(b.day));
    const active = this.kids.activeKid();
    const kids = active ? [active] : this.kids.kids();
    const groups = kids.map(k => ({ id: k.id, name: k.name, color: k.color, items: sorted.filter(u => u.childId === k.id).slice(0, 5) }));
    const shared = sorted.filter(u => !u.childId || !kids.some(k => k.id === u.childId)).slice(0, 5);
    if (shared.length) groups.push({ id: 'shared', name: this.i18n.t('inbox.bothKids'), color: '#64748b', items: shared });
    return groups.filter(g => g.items.length);
  });

  private upcoming(caseId: string, iso: string, detail: string): Upcoming {
    const c = this.caseMap().get(caseId);
    const day = iso.length === 10 ? iso : dayKey(iso);
    return { caseId, title: c?.title ?? '', day, when: iso, detail, childId: c?.childId ?? null, color: c?.childColor ?? null };
  }

  private time(iso: string): string {
    return new Date(iso).toLocaleTimeString(this.i18n.locale(), { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Berlin' });
  }

  ngOnInit(): void {
    // Month and day live in the URL, so Back and closing a letter return to the same view.
    const q = this.route.snapshot.queryParamMap;
    const kid = q.get('kid');
    if (kid) this.kids.refresh(list => {
      if (list.some(k => k.id === kid)) this.kids.select(kid);
    });
    const month = /^(\d{4})-(\d{2})$/.exec(q.get('month') ?? '');
    if (month) this.cursor.set(new Date(+month[1], +month[2] - 1, 1));
    const day = q.get('day');
    if (day && /^\d{4}-\d{2}-\d{2}$/.test(day)) this.selected.set(day);
    this.keepSelectionInMonth();
    this.cases.refresh();
    this.actions.refresh();
    this.requirements.refresh();
    this.load();
  }

  prev(): void { this.shift(-1); }
  next(): void { this.shift(1); }

  today(): void {
    this.cursor.set(startOfMonth(new Date()));
    this.selected.set(dayKey(new Date().toISOString()));
    this.load();
    this.remember();
  }

  pick(key: string): void {
    this.selected.set(key);
    this.remember();
  }

  toggle(key: 'events' | 'exams' | 'holidays' | 'finished'): void {
    this.show.update(s => ({ ...s, [key]: !s[key] }));
  }

  importHolidays(): void {
    this.schoolCalendar.importYear(this.cursor().getFullYear());
  }

  private shift(delta: number): void {
    const d = this.cursor();
    this.cursor.set(new Date(d.getFullYear(), d.getMonth() + delta, 1));
    this.keepSelectionInMonth();
    this.load();
    this.remember();
  }

  /** The day panel must describe the month on screen: today if it is in it, else the 1st. */
  private keepSelectionInMonth(): void {
    const prefix = isoDate(this.cursor()).slice(0, 7);
    if (this.selected().startsWith(prefix)) return;
    const today = dayKey(new Date().toISOString());
    this.selected.set(today.startsWith(prefix) ? today : `${prefix}-01`);
  }

  private remember(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { month: isoDate(this.cursor()).slice(0, 7), day: this.selected() },
      replaceUrl: true,
    });
  }

  private load(): void {
    const { from, to } = monthRange(this.cursor());
    this.events.refresh(from, to);
    const start = new Date(this.cursor().getFullYear(), this.cursor().getMonth(), 1);
    const end = new Date(this.cursor().getFullYear(), this.cursor().getMonth() + 1, 0);
    this.schoolCalendar.refresh(isoDate(start), isoDate(end));
  }
}
