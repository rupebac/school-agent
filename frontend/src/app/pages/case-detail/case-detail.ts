import { Component, effect, ElementRef, inject, input, signal, untracked, viewChild } from '@angular/core';
import { EmailThread } from './email-thread';
import { DocumentText } from '../../core/document-text';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Cases, CaseDetail, ParentActionDto, SourceReading, ChildRequirementDto, SchoolEventDto } from '../../core/cases.store';
import { ConsequencePipe, LabelPipe, consequenceTone, isHotScore, isMust, showStatusTag } from '../../core/labels';
import { I18n, LocDatePipe, TranslatePipe } from '../../core/i18n';
import { ActionsStore, RequirementsStore, isActionClosed } from '../../core/overview.store';
import { Icon } from '../../core/icon';
import { ImportanceBars } from '../../core/importance';
import { NavHistory } from '../../core/nav-history';
import { scoreOf } from '../../core/labels';

@Component({
  imports: [EmailThread, DocumentText, RouterLink, FormsModule, ConsequencePipe, LabelPipe, TranslatePipe, LocDatePipe, Icon, ImportanceBars],
  selector: 'app-case-detail-page',
  host: { '(document:click)': 'outsideMenu($event)' },
  styleUrl: './case-detail.css',
  templateUrl: './case-detail.html',
})
export class CaseDetailPage {
  readonly store = inject(Cases);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly nav = inject(NavHistory);
  readonly level = scoreOf;
  private readonly http = inject(HttpClient);
  private readonly i18n = inject(I18n);
  readonly actions = inject(ActionsStore);
  private readonly requirements = inject(RequirementsStore);
  readonly tone = consequenceTone;
  readonly showTag = showStatusTag;
  readonly mustDo = isMust;
  readonly hot = isHotScore;
  readonly adding = signal(false);
  readonly addingNeed = signal(false);
  readonly editingId = signal<string | null>(null);
  draftTitle = '';
  draftDue = '';
  draftNeed = '';
  draftNeedOn = '';
  editTitle = '';
  editDue = '';

  readonly id = input.required<string>();
  readonly detail = signal<CaseDetail | null>(null);
  readonly error = signal<string | null>(null);
  schoolMoney(minor: number | null, currency: string): string {
    return minor === null ? this.i18n.t('school.response.UNKNOWN') :
      new Intl.NumberFormat(this.i18n.lang(), { style: 'currency', currency }).format(minor / 100);
  }
  readonly reading = signal<SourceReading | null>(null);
  readonly readingId = signal<string | null>(null);
  private loadVersion = 0;
  private readerVersion = 0;
  readonly readingBusy = signal(false);
  readonly readerError = signal<string | null>(null);
  readonly summary = signal<{ text: string; sourceIds: string[]; partial: boolean } | null>(null);
  readonly summaryBusy = signal(false);
  readonly readerMode = signal<'summary' | 'translation' | 'original'>('translation');

  readonly moreOpen = signal(false);
  private readonly moreButton = viewChild<ElementRef<HTMLButtonElement>>('moreButton');
  toggleMore(): void {
    if (this.moreOpen()) { this.closeMore(); return; }
    this.moreOpen.set(true);
    setTimeout(() => this.moreButton()?.nativeElement.parentElement?.querySelector<HTMLButtonElement>('[role="menuitemcheckbox"]')?.focus());
  }
  closeMore(restoreFocus = true): void {
    this.moreOpen.set(false);
    if (restoreFocus) this.moreButton()?.nativeElement.focus();
  }
  outsideMenu(event: MouseEvent): void {
    if (this.moreOpen() && !(event.target as Element)?.closest('.matter-menu')) this.closeMore(false);
  }
  menuFocusOut(event: FocusEvent): void {
    if (!(event.relatedTarget as Element)?.closest('.matter-menu')) this.closeMore(false);
  }
  menuKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      // Only swallow Escape while the menu is open; otherwise it closes the letter.
      if (this.moreOpen()) { event.preventDefault(); this.closeMore(); }
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const buttons = [...(this.moreButton()?.nativeElement.parentElement?.querySelectorAll<HTMLButtonElement>('[role="menuitemcheckbox"]') ?? [])];
    if (!buttons.length) return;
    const current = buttons.indexOf(document.activeElement as HTMLButtonElement);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowUp' ? -1 : 1) + buttons.length) % buttons.length;
    buttons[next].focus();
  }

  classify(d: CaseDetail): void { this.closeMore(); this.store.classify(d.id, !d.informationOnly, () => this.load(d.id)); }
  markRead(d: CaseDetail): void { this.store.markRead(d.id, !d.read, d.revision, () => this.load(d.id)); }
  undo(d: CaseDetail): void { this.store.undo(d.id, () => this.load(d.id)); }
  extendedSummary(): void {
    this.readerMode.set('summary');
    if (this.summary() || this.summaryBusy()) return;
    this.summaryBusy.set(true); this.readerError.set(null);
    const id = this.id();
    const version = this.loadVersion;
    this.http.get<{ text: string; sourceIds: string[]; partial: boolean }>(`/api/cases/${id}/summary`, { params: { lang: this.i18n.lang() } }).subscribe({
      next: r => { if (id !== this.id() || version !== this.loadVersion) return; this.summary.set(r); this.summaryBusy.set(false); },
      error: err => { if (version !== this.loadVersion) return; this.summaryBusy.set(false); this.readerError.set(err?.error?.detail ?? 'Summary is temporarily unavailable. The source documents remain available.'); },
    });
  }

  constructor() {
    this.actions.refresh();
    effect(() => {
      const id = this.id();
      this.i18n.lang();
      untracked(() => this.load(id));
    });
  }

  hasEmail(d: CaseDetail): boolean { return d.sources.some(s => s.sourceType === 'EMAIL'); }

  openActions(d: CaseDetail): ParentActionDto[] {
    return d.actions.filter((a) => !isActionClosed(a));
  }

  completeAction(a: ParentActionDto): void {
    this.actions.complete(a.id, () => this.load(this.id()));
  }

  openRequirements(d: CaseDetail): ChildRequirementDto[] {
    return d.requirements.filter((r) => r.status !== 'DONE' && r.status !== 'CANCELLED');
  }

  openEvents(d: CaseDetail): SchoolEventDto[] {
    return d.events.filter((e) => e.status !== 'CANCELLED');
  }

  completeRequirement(r: ChildRequirementDto): void {
    this.requirements.complete(r.id, () => this.load(this.id()));
  }

  toggleMute(d: CaseDetail): void {
    this.closeMore();
    this.store.mute(this.id(), !d.muted, () => this.load(this.id()));
  }

  startAdd(): void {
    this.adding.set(true);
    this.addingNeed.set(false);
    this.draftTitle = '';
    this.draftDue = '';
    this.cancelEdit();
  }

  cancelAdd(): void {
    this.adding.set(false);
  }

  saveAdd(caseId: string): void {
    const title = this.draftTitle.trim();
    if (!title) return;
    const dueAt = this.draftDue ? new Date(this.draftDue + 'T12:00:00').toISOString() : null;
    this.actions.create(caseId, { title, dueAt }, () => {
      this.adding.set(false);
      this.load(this.id());
    });
  }

  startEdit(a: ParentActionDto): void {
    this.editingId.set(a.id);
    this.editTitle = a.title;
    this.editDue = a.dueAt ? a.dueAt.slice(0, 10) : '';
    this.adding.set(false);
    this.addingNeed.set(false);
  }

  cancelEdit(): void {
    this.editingId.set(null);
  }

  saveEdit(a: ParentActionDto): void {
    const title = this.editTitle.trim();
    if (!title) return;
    const dueAt = this.editDue ? new Date(this.editDue + 'T12:00:00').toISOString() : null;
    this.actions.update(a.id, { title, dueAt }, () => {
      this.editingId.set(null);
      this.load(this.id());
    });
  }

  removeAction(a: ParentActionDto): void {
    if (!confirm(this.i18n.t('inbox.confirmDelete'))) return;
    this.actions.remove(a.id, () => this.load(this.id()));
  }

  startAddNeed(): void {
    this.addingNeed.set(true);
    this.adding.set(false);
    this.draftNeed = '';
    this.draftNeedOn = '';
    this.cancelEdit();
  }

  cancelAddNeed(): void {
    this.addingNeed.set(false);
  }

  saveAddNeed(caseId: string): void {
    const description = this.draftNeed.trim();
    if (!description) return;
    const relevantOn = this.draftNeedOn || null;
    this.requirements.create(caseId, { description, relevantOn }, () => {
      this.addingNeed.set(false);
      this.load(this.id());
    });
  }

  startEditNeed(r: ChildRequirementDto): void {
    this.editingId.set(r.id);
    this.editTitle = r.description;
    this.editDue = r.relevantOn ?? '';
    this.adding.set(false);
    this.addingNeed.set(false);
  }

  saveEditNeed(r: ChildRequirementDto): void {
    const description = this.editTitle.trim();
    if (!description) return;
    const relevantOn = this.editDue || null;
    this.requirements.update(r.id, { description, relevantOn }, () => {
      this.editingId.set(null);
      this.load(this.id());
    });
  }

  removeNeed(r: ChildRequirementDto): void {
    if (!confirm(this.i18n.t('inbox.confirmDeleteNeed'))) return;
    this.requirements.remove(r.id, () => this.load(this.id()));
  }

  canCompleteCase(d: CaseDetail): boolean {
    if (d.schoolUpdatePending) return false;
    if (d.status === 'COMPLETED' || d.status === 'CANCELLED') return false;
    if (d.informationOnly) return false;
    return d.status === 'ACTION_REQUIRED' || d.status === 'WAITING_FOR_PARENT'
      || d.status === 'INFORMATION_ONLY' || d.status === 'NEEDS_REVIEW';
  }

  completeCase(): void {
    this.store.complete(this.id(), () => {
      this.actions.refresh();
      this.load(this.id());
    }, this.detail()?.revision);
  }

  feedback(type: string): void {
    this.http.post(`/api/cases/${this.id()}/feedback`, { type }).subscribe({
      next: () => this.load(this.id()),
      error: () => this.error.set(this.i18n.t('case.feedbackFailed')),
    });
  }

  load(id: string): void {
    this.moreOpen.set(false);
    const version = ++this.loadVersion;
    this.readerVersion++; this.readingBusy.set(false); this.summaryBusy.set(false);
    this.error.set(null);
    this.summary.set(null);
    this.reading.set(null);
    this.readingId.set(null);
    this.store.load(id).subscribe({
      next: (d) => {
        if (version !== this.loadVersion) return;
        this.detail.set(d);

      },
      error: (err) => this.error.set(err?.status === 404 ? this.i18n.t('case.notFound') : (err?.message ?? this.i18n.t('case.loadFailed'))),
    });
  }

  readSource(sourceId: string): void {
    const version = ++this.readerVersion;
    this.readerMode.set('translation');
    this.readerError.set(null);
    this.readingId.set(sourceId);
    this.readingBusy.set(true);
    this.reading.set(null);
    // The reader sits above the source list, below the instructions; bring it into view.
    setTimeout(() => this.host.nativeElement.querySelector('.reader-tabs')?.scrollIntoView?.({ block: 'start', behavior: 'smooth' }));
    this.store.readSource(this.id(), sourceId, this.i18n.lang()).subscribe({
      next: (r) => {
        if (version !== this.readerVersion) return;
        this.reading.set(r);
        this.readingBusy.set(false);
      },
      error: () => {
        if (version !== this.readerVersion) return;
        this.readingBusy.set(false);
        this.readerError.set(this.i18n.t('case.readFailed'));
      },
    });
  }

  downloadOriginal(sourceId: string): void {
    this.http.get(`/api/cases/${this.id()}/sources/${sourceId}/original`, { responseType: 'blob', observe: 'response' }).subscribe({
      next: response => {
        if (!response.body) return;
        const url = URL.createObjectURL(response.body);
        const anchor = document.createElement('a'); anchor.href = url;
        anchor.download = response.headers.get('Content-Disposition')?.match(/filename="([^"]+)"/)?.[1] ?? 'school-document';
        anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      error: () => this.readerError.set('Original document is unavailable.'),
    });
  }

  closeReading(): void {
    this.readerVersion++;
    this.reading.set(null);
    this.readingId.set(null);
    this.readingBusy.set(false);
  }

  langName(code: string): string {
    return this.i18n.t(code === 'es' ? 'nav.langEs' : 'nav.langEn');
  }

  reprocess(): void {
    this.http.post(`/api/reason/reprocess`, { caseId: this.id() }).subscribe({
      next: () => this.load(this.id()),
      error: () => this.error.set(this.i18n.t('case.rerunFailed')),
    });
  }
}
