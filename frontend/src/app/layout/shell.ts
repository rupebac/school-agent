import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, ActivatedRouteSnapshot, NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { Auth } from '../core/auth';
import { HealthStore } from '../core/overview.store';
import { KidsStore } from '../core/kids.store';
import { Cases } from '../core/cases.store';
import { LabelPipe } from '../core/labels';
import { I18n, TranslatePipe } from '../core/i18n';
import { Icon } from '../core/icon';
import { ChatWidget } from './chat-widget';
import { NavHistory } from '../core/nav-history';
import { ShellBar } from '../core/shell-bar';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';

type Section = 'inbox' | 'calendar' | 'settings' | 'audit' | 'search' | 'other';

@Component({
  imports: [RouterOutlet, RouterLink, NgTemplateOutlet, LabelPipe, TranslatePipe, ChatWidget, Icon],
  selector: 'app-shell',
  styleUrl: './shell.css',
  templateUrl: './shell.html',
})
export class Shell implements OnInit {
  readonly auth = inject(Auth);
  readonly i18n = inject(I18n);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  readonly health = inject(HealthStore);
  readonly bar = inject(ShellBar);
  readonly kids = inject(KidsStore);
  private readonly cases = inject(Cases);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private searchTimer?: ReturnType<typeof setTimeout>;
  readonly searchText = signal('');
  readonly section = signal<Section>('other');
  readonly title = signal('nav.inbox');
  readonly wide = signal(false);
  readonly photoBusy = signal(false);
  readonly photoError = signal<string | null>(null);
  readonly snapMenu = signal(false);

  /** Open matters that still need a parent, for the rail badge. */
  readonly needsUs = computed(() => this.cases.cases().filter(c => !c.informationOnly && Cases.needsAction(c)
    && c.status !== 'COMPLETED' && c.status !== 'CANCELLED').length);

  /** Worst connector state for the rail dot. */
  readonly healthState = computed(() => {
    const list = this.health.connectors();
    if (!list.length) return 'unknown';
    if (list.some(c => c.status === 'DOWN')) return 'down';
    if (list.some(c => c.status === 'DEGRADED')) return 'warn';
    return 'ok';
  });
  readonly healthLabel = computed(() => {
    this.i18n.lang();
    const problems = this.health.problems();
    if (problems.length) return problems.map(c => this.health.tooltip(c)).join('\n');
    return this.i18n.t(this.healthState() === 'unknown' ? 'health.unknown' : 'health.allOk');
  });

  /** Where a new photo goes without asking: the selected child, or the only child. Undefined = ask. */
  readonly snapKid = computed((): string | null | undefined => {
    const active = this.kids.activeKidId();
    if (active) return active;
    const list = this.kids.kids();
    if (list.length === 1) return list[0].id;
    if (list.length === 0) return null;
    return undefined;
  });

  readonly initial = computed(() => (this.auth.me()?.displayName || this.auth.me()?.name || '?').charAt(0).toUpperCase());

  constructor() {
    inject(NavHistory); // start remembering where letters are opened from
    this.destroyRef.onDestroy(() => clearTimeout(this.searchTimer));
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.searchText.set(params.get('q') ?? '');
    });
    this.router.events.pipe(filter(e => e instanceof NavigationEnd), takeUntilDestroyed()).subscribe(() => this.readRoute());
    this.readRoute();
  }

  ngOnInit(): void {
    this.health.refresh();
    this.kids.refresh(() => this.cases.setKid(this.kids.activeKidId()));
  }

  private readRoute(): void {
    let snap: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let data: Record<string, unknown> = {};
    while (snap) { data = { ...data, ...snap.data }; snap = snap.firstChild; }
    this.section.set((data['section'] as Section) ?? 'other');
    this.title.set((data['title'] as string) ?? 'nav.brand');
    this.wide.set(!!data['wide']);
  }

  pickKid(kidId: string | null): void {
    this.kids.select(kidId);
    this.cases.setKid(kidId);
  }

  changeLanguage(event: Event): void {
    const language = (event.target as HTMLSelectElement).value;
    this.i18n.setLang(language);
    this.http.post<{ language: string }>('/api/account/language', { language }).subscribe({
      next: (r) => {
        const me = this.auth.me();
        if (me) this.auth.me.set({ ...me, language: r.language });
        this.i18n.setLang(r.language);
      },
    });
  }

  queueSearch(event: Event): void {
    this.searchText.set((event.target as HTMLInputElement).value);
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.search(), 300);
  }

  search(): void {
    clearTimeout(this.searchTimer);
    const value = this.searchText().trim();
    if (value.length >= 2 || this.router.url.startsWith('/search')) {
      void this.router.navigate(['/search'], {
        queryParams: { q: value }, replaceUrl: this.router.url.startsWith('/search'),
      });
    }
  }

  /** Photo or PDF of a paper letter → a new case for that child, then open it. */
  uploadPhoto(event: Event, kidId: string | null | undefined): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    this.snapMenu.set(false);
    if (!file || this.photoBusy()) return;
    this.photoBusy.set(true);
    this.photoError.set(null);
    const body = new FormData();
    body.append('file', file, file.name);
    const url = kidId ? `/api/cases/photo?kid=${kidId}` : '/api/cases/photo';
    this.http.post<{ id: string }>(url, body).subscribe({
      next: (c) => {
        this.photoBusy.set(false);
        this.cases.refresh();
        void this.router.navigate(['/cases', c.id]);
      },
      error: () => {
        this.photoBusy.set(false);
        this.photoError.set(this.i18n.t('inbox.photoFailed'));
      },
    });
  }

  logout(): void {
    this.auth.logout();
  }
}
