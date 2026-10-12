import { Component, effect, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { DocumentText } from '../../core/document-text';
import { I18n, LocDatePipe } from '../../core/i18n';
import { SourceReading } from '../../core/cases.store';

interface Message { id: string; subject: string | null; sender: string | null; recipients: string | null; sentAt: string | null; receivedAt: string | null; forwardedBy: string[]; body: string | null; notice: string | null; }
interface TextResult { text: string; partial: boolean; }
@Component({
  selector: 'app-email-thread',
  imports: [FormsModule, DocumentText, LocDatePipe],
  templateUrl: './email-thread.html',
  styleUrl: './email-thread.css',
})
export class EmailThread {
  readonly caseId = input.required<string>();
  private readonly http = inject(HttpClient);
  private readonly i18n = inject(I18n);
  readonly messages = signal<Message[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly summaries = signal<Record<string, TextResult>>({});
  readonly translations = signal<Record<string, SourceReading>>({});
  readonly working = signal<Record<string, boolean>>({});
  readonly draft = signal<TextResult | null>(null);
  readonly draftBusy = signal(false);
  readonly copied = signal(false);
  replyEnabled = false;
  instructions = '';
  draftText = '';
  private version = 0;

  constructor() {
    effect(() => {
      const id = this.caseId(); this.i18n.lang();
      const version = ++this.version;
      this.busy.set(true); this.error.set(null); this.messages.set([]);
      this.summaries.set({}); this.translations.set({}); this.working.set({});
      this.draft.set(null); this.draftBusy.set(false); this.replyEnabled = false;
      this.instructions = ''; this.draftText = ''; this.copied.set(false);
      this.http.get<Message[]>(`/api/cases/${id}/email-thread`).subscribe({
        next: value => { if (version !== this.version) return; this.messages.set(value); this.busy.set(false); },
        error: () => { if (version !== this.version) return; this.busy.set(false); this.error.set('Email thread could not be loaded. Please reopen this event to try again.'); },
      });
    });
  }
  summarize(id: string): void {
    if (this.summaries()[id] || this.working()[id]) return;
    const version = this.version;
    this.working.update(v => ({ ...v, [id]: true })); this.error.set(null);
    this.http.get<TextResult>(`/api/cases/${this.caseId()}/email-thread/${id}/summary`, { params: { lang: this.i18n.lang() } }).subscribe({
      next: value => { if (version !== this.version) return; this.summaries.update(v => ({ ...v, [id]: value })); this.working.update(v => ({ ...v, [id]: false })); },
      error: () => { if (version !== this.version) return; this.working.update(v => ({ ...v, [id]: false })); this.error.set('Summary unavailable. The original message is still available.'); },
    });
  }
  translate(id: string): void {
    const key = `translate:${id}`;
    if (this.translations()[id] || this.working()[key]) return;
    const version = this.version;
    this.working.update(v => ({ ...v, [key]: true })); this.error.set(null);
    this.http.get<SourceReading>(`/api/cases/${this.caseId()}/sources/${id}`, { params: { lang: this.i18n.lang() } }).subscribe({
      next: value => { if (version !== this.version) return; this.translations.update(v => ({ ...v, [id]: value })); this.working.update(v => ({ ...v, [key]: false })); },
      error: () => { if (version !== this.version) return; this.working.update(v => ({ ...v, [key]: false })); this.error.set('Translation unavailable. The original message is still available.'); },
    });
  }
  prepareDraft(): void {
    if (!this.instructions.trim() || this.draftBusy()) return;
    const version = this.version;
    this.draftBusy.set(true); this.error.set(null); this.copied.set(false); this.draft.set(null);
    this.http.post<TextResult>(`/api/cases/${this.caseId()}/email-thread/reply-draft`, { instructions: this.instructions }).subscribe({
      next: value => { if (version !== this.version) return; this.draft.set(value); this.draftText = value.text; this.draftBusy.set(false); },
      error: () => { if (version !== this.version) return; this.draftBusy.set(false); this.error.set('Could not prepare the reply. Please try again.'); },
    });
  }
  async copy(): Promise<void> {
    try { await navigator.clipboard.writeText(this.draftText); this.copied.set(true); }
    catch { this.error.set('Clipboard unavailable. Select the draft text and copy it manually.'); }
  }
}
