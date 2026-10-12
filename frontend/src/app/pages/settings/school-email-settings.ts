import { Component, effect, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
@Component({
  selector: 'app-school-email-settings', imports: [FormsModule],
  template: `
    <section class="card">
      <h2>School email domains</h2>
      <p class="muted">Include the school’s domain and any platform sender domains. Original senders and recipients inside forwarded messages are checked too.</p>
      <label>Domains, separated by commas or new lines
        <textarea rows="3" [(ngModel)]="domains" [disabled]="loading() || saving()" placeholder="school.example, schulmanager-online.de"></textarea>
      </label>
      <p class="muted">Leave empty to accept all mail in this child’s connected inbox. Both parents can forward to that inbox; duplicate original messages are combined. Replies belong together when their message references or Gmail thread match.</p>
      <button type="button" (click)="save()" [disabled]="loading() || saving()">{{ saving() ? 'Saving…' : 'Save email domains' }}</button>
      @if (message()) { <p role="status">{{ message() }}</p> }
    </section>`,
  styles: `:host { display:block; margin-top:16px; } h2 { font-size:1.15rem; } textarea { display:block; width:100%; margin:8px 0; resize:vertical; }`,
})
export class SchoolEmailSettings {
  readonly kid = input.required<string>();
  private readonly http = inject(HttpClient);
  domains = '';
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly message = signal<string | null>(null);
  private version = 0;
  constructor() {
    effect(() => {
      const kid = this.kid(), version = ++this.version;
      this.domains = ''; this.loading.set(true); this.saving.set(false); this.message.set(null);
      this.http.get<{ domains: string[] }>('/api/google/email-settings', { params: { kid } }).subscribe({
        next: config => { if (version !== this.version) return; this.domains = config.domains.join('\n'); this.loading.set(false); },
        error: () => { if (version !== this.version) return; this.message.set('Email settings could not be loaded. Reopen this panel to try again.'); },
      });
    });
  }
  save(): void {
    const kid = this.kid(), version = this.version;
    this.saving.set(true); this.message.set(null);
    this.http.put<{ domains: string[] }>('/api/google/email-settings', { domains: this.domains.split(/[,;\s]+/).filter(Boolean) }, { params: { kid } }).subscribe({
      next: config => { if (version !== this.version) return; this.domains = config.domains.join('\n'); this.message.set('Email domains saved.'); this.saving.set(false); },
      error: err => { if (version !== this.version) return; this.message.set(err?.error?.detail || 'Could not save email domains.'); this.saving.set(false); },
    });
  }
}
