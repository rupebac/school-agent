import { Component, computed, DestroyRef, effect, inject, OnInit, signal, TemplateRef, viewChild } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { Cases } from '../../core/cases.store';
import { KidsStore, Kid } from '../../core/kids.store';
import { ActionsStore, EventsStore, RequirementsStore } from '../../core/overview.store';
import { I18n, LocDatePipe, TranslatePipe } from '../../core/i18n';
import { Icon } from '../../core/icon';
import { ImportanceBars } from '../../core/importance';
import { NavHistory } from '../../core/nav-history';
import { ShellBar } from '../../core/shell-bar';
import {
  filterInbox, buildInbox, groupInbox, groupOf, splitByChild, daysUntil, SOON_DAYS,
  InboxItem, InboxLayout, InboxSection, canMarkDone, isClosedStatus,
  readInboxLayout, readInboxControls, resolveInboxControls, writeInboxControls, writeInboxLayout,
  readCollapsedSections, writeCollapsedSections,
} from './inbox.model';

interface LaneView {
  kid: Kid;
  sections: InboxSection[];
  stats: { overdue: number; soon: number; later: number };
}

const SOURCE_LABELS: Record<string, string> = {
  EMAIL: 'Email',
  PORTAL_LETTER: 'Elternportal',
  SCHULMANAGER_LETTER: 'Schulmanager', ELTERNPORTAL_RECORD: 'Elternportal', SCHULMANAGER_RECORD: 'Schulmanager',
  MANUAL_UPLOAD: 'Photo',
  ATTACHMENT: 'Attachment',
};

@Component({
  imports: [RouterLink, RouterOutlet, NgTemplateOutlet, TranslatePipe, LocDatePipe, Icon, ImportanceBars],
  selector: 'app-inbox-page',
  host: { '(document:keydown.escape)': 'onEscape($event)' },
  styleUrl: './inbox.css',
  templateUrl: './inbox.html',
})
export class InboxPage implements OnInit {
  readonly cases = inject(Cases);
  readonly actions = inject(ActionsStore);
  readonly events = inject(EventsStore);
  readonly requirements = inject(RequirementsStore);
  readonly kids = inject(KidsStore);
  readonly i18n = inject(I18n);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  readonly nav = inject(NavHistory);

  readonly controls = signal(readInboxControls());
  readonly collapsed = signal(readCollapsedSections());
  readonly layout = signal<InboxLayout>(readInboxLayout(InboxPage.isPhone() ? 'together' : 'lanes'));
  readonly compactBar = signal(typeof window !== 'undefined' && !!window.matchMedia?.('(max-width: 1440px)').matches);
  private readonly barTpl = viewChild.required<TemplateRef<unknown>>('barTpl');
  private readonly shellBar = inject(ShellBar);
  readonly levels = [1, 2, 3, 4, 5];
  readonly paneOpen = signal(false);
  readonly selectedId = signal<string | null>(null);
  private readonly now = signal(Date.now());

  constructor() {
    effect(() => this.cases.setKid(this.kids.activeKidId()));
    // Wide screens share the shell header; narrower screens keep controls above the list.
    effect(() => this.shellBar.template.set(this.compactBar() ? null : this.barTpl()));
    const media = typeof window !== 'undefined' ? window.matchMedia?.('(max-width: 1440px)') : undefined;
    const onMedia = (e: MediaQueryListEvent) => this.compactBar.set(e.matches);
    media?.addEventListener?.('change', onMedia);
    const timer = setInterval(() => this.now.set(Date.now()), 60000);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      media?.removeEventListener?.('change', onMedia);
      this.shellBar.template.set(null);
    });
    this.router.events.pipe(filter(e => e instanceof NavigationEnd), takeUntilDestroyed()).subscribe(() => this.readSelection());
    this.readSelection();
  }

  readonly items = computed(() =>
    buildInbox(this.cases.cases(), this.actions.actions(), this.events.events(), this.now()),
  );
  readonly shown = computed(() => filterInbox(this.items(), this.controls()));
  readonly emptyKey = computed(() => this.controls().info && !this.controls().minScore && !this.controls().done && !this.controls().archived ? 'inbox.emptyAll' : 'inbox.emptyFiltered');
  readonly nothingIngested = computed(() => this.cases.cases().length === 0);
  readonly loading = computed(() => this.cases.loading() || this.actions.loading() || this.events.loading());
  /** Lanes only make sense when several children are shown together. */
  readonly canSplit = computed(() => !this.kids.activeKidId() && this.kids.kids().length > 1);
  readonly laneKids = computed((): Kid[] => {
    const active = this.kids.activeKid();
    return active ? [active] : this.kids.kids();
  });
  readonly useLanes = computed(() => this.laneKids().length > 0 && (this.layout() === 'lanes' || !this.canSplit()));

  readonly split = computed(() => splitByChild(this.shown(), this.laneKids().map(k => k.id)));
  readonly lanes = computed((): LaneView[] => {
    const all = splitByChild(this.shown(), this.laneKids().map(k => k.id)).lanes;
    return this.laneKids().map(kid => {
      const open = all.get(kid.id) ?? [];
      const groups = open.map(i => groupOf(i));
      return {
        kid,
        sections: groupInbox(this.split().lanes.get(kid.id) ?? []),
        stats: {
          overdue: groups.filter(g => g === 'overdue').length,
          soon: groups.filter(g => g === 'soon').length,
          later: groups.filter(g => g === 'later').length,
        },
      };
    });
  });
  readonly sharedSections = computed(() => groupInbox(this.split().shared));
  readonly togetherSections = computed(() => groupInbox(this.shown()));

  ngOnInit(): void {
    this.cases.refresh();
    this.actions.refresh();
    this.requirements.refresh();
    this.events.refresh();
    this.route.queryParamMap.subscribe((q) => {
      const selected = resolveInboxControls({ info: q.get('info'), done: q.get('done'), archived: q.get('archived'), minScore: q.get('minScore'), view: q.get('view') });
      this.controls.set(selected);
      writeInboxControls(selected);
    });
  }

  private readSelection(): void {
    this.selectedId.set(this.router.url.match(/\/cases\/([^/?#]+)/)?.[1] ?? null);
  }

  toggleVisibility(key: 'info' | 'done' | 'archived'): void {
    this.controls.update(c => ({ ...c, [key]: !c[key] }));
    this.saveControls();
  }

  setMinimumScore(score: number): void {
    this.controls.update(c => ({ ...c, minScore: c.minScore === score ? 0 : score }));
    this.saveControls();
  }

  private saveControls(): void {
    const c = this.controls();
    writeInboxControls(c);
    void this.router.navigate([], { relativeTo: this.route, queryParams: {
      view: null, info: c.info ? '1' : '0', done: c.done ? '1' : '0', archived: c.archived ? '1' : '0', minScore: c.minScore,
    }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  sectionId(scope: string, group: string): string { return `inbox-section-${scope}-${group}`; }

  isCollapsed(scope: string, group: string): boolean { return this.collapsed().has(this.sectionId(scope, group)); }

  toggleSection(scope: string, group: string): void {
    const key = this.sectionId(scope, group);
    this.collapsed.update(previous => {
      const next = new Set(previous);
      if (next.has(key)) next.delete(key); else next.add(key);
      writeCollapsedSections(next);
      return next;
    });
  }

  setLayout(layout: InboxLayout): void {
    this.layout.set(layout);
    writeInboxLayout(layout);
  }

  private static isPhone(): boolean {
    return typeof window !== 'undefined' && !!window.matchMedia?.('(max-width: 760px)').matches;
  }

  /** Menus handle Escape first; otherwise close the open letter. */
  onEscape(event: Event): void {
    if (event.defaultPrevented) return;
    if ((event.target as Element | null)?.closest?.('.chat-panel, input, textarea, select')) return;
    this.closePane();
  }

  closePane(): void {
    if (this.paneOpen()) void this.router.navigateByUrl(this.nav.returnUrl());
  }

  kidOf(row: InboxItem): Kid | undefined {
    return this.kids.kids().find(k => k.id === row.childId);
  }

  kidLine(kid: Kid): string {
    return [kid.className, kid.school].filter(Boolean).join(' · ');
  }

  kindOf(row: InboxItem): string {
    if (row.overdue) return 'late';
    if (row.status === 'NEEDS_REVIEW' || row.status === 'FAILED') return 'review';
    if (row.informationOnly) return 'info';
    if (row.action) return 'todo';
    if (row.event) return 'event';
    return 'info';
  }

  kindIcon(row: InboxItem): string {
    return { late: 'alert', review: 'alert', info: 'info', todo: 'check', event: 'calendar' }[this.kindOf(row)] ?? 'info';
  }

  sourceLabel(type: string): string {
    return SOURCE_LABELS[type] ?? type;
  }

  /** Keep the actual date visible even within the coming week. */
  due(row: InboxItem): { text: string; tone: string } | null {
    if (groupOf(row) === 'done') return null;
    const date = row.action?.dueAt;
    if (!date) return null;
    const n = daysUntil(date);

    return {
      text: this.dateLabel(date),
      tone: row.overdue ? 'red' : n <= 3 ? 'amber' : '',
    };
  }

  private dateLabel(date: string): string {
    const days = daysUntil(date);
    if (days >= -1 && days <= SOON_DAYS) {
      return new Intl.RelativeTimeFormat(this.i18n.locale(), { numeric: 'auto' }).format(days, 'day');
    }
    const when = new Date(date);
    const opts = { year: 'numeric' as const, timeZone: 'Europe/Berlin' };
    return when.toLocaleDateString(this.i18n.locale(), {
      weekday: 'short', day: 'numeric', month: 'short',
      ...(when.toLocaleDateString('en', opts) !== new Date().toLocaleDateString('en', opts) ? { year: 'numeric' as const } : {}),
      timeZone: 'Europe/Berlin',
    });
  }

  /** One date block: distinguish the occasion from its deadline without repeating the same day. */
  eventWhen(row: InboxItem): string | null {
    if (!row.event) return null;
    const separateDay = !this.due(row) || !row.action?.dueAt || daysUntil(row.event.startTime) !== daysUntil(row.action.dueAt);
    const date = separateDay ? this.dateLabel(row.event.startTime) : '';
    const time = row.event.allDay ? '' : new Date(row.event.startTime).toLocaleTimeString(this.i18n.locale(), {
      hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Berlin',
    });
    const when = [date, time].filter(Boolean).join(' · ');
    return when || null;
  }

  undo(row: InboxItem): void { this.cases.undo(row.caseId, () => { this.actions.refresh(); this.requirements.refresh(); }); }

  done(row: InboxItem): void {
    if (row.informationOnly) this.cases.markRead(row.caseId, !row.read, row.revision);
    else this.cases.complete(row.caseId, () => { this.actions.refresh(); this.requirements.refresh(); }, row.revision);
  }

  showDone(row: InboxItem): boolean {
    return canMarkDone(row);
  }

  isClosed(row: InboxItem): boolean {
    return isClosedStatus(row.status);
  }

  groupKey(group: string): string {
    return `inbox.group.${group}`;
  }
}
