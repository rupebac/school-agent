import { Component, effect, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '../../core/i18n';
@Component({
  selector: 'app-school-email-settings', imports: [FormsModule, TranslatePipe],
  template: `
    <section class="card">
      <h2>{{ 'set.emailDomains.h' | t }}</h2>
      <p class="muted">{{ 'set.emailDomains.help' | t }}</p>
      <label>{{ 'set.emailDomains.label' | t }}
        <textarea rows="3" [(ngModel)]="domains" [disabled]="loading() || saving()" placeholder="school.example, schulmanager-online.de"></textarea>
      </label>
      <p class="muted">{{ 'set.emailDomains.emptyHelp' | t }}</p>
      <button type="button" (click)="save()" [disabled]="loading() || saving()">{{ (saving() ? 'set.emailDomains.saving' : 'set.emailDomains.save') | t }}</button>
      @if (message(); as msg) { <p role="status">{{ msg | t }}</p> }
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
        error: () => { if (version !== this.version) return; this.message.set('set.emailDomains.loadFailed'); },
      });
    });
  }
  save(): void {
    const kid = this.kid(), version = this.version;
    this.saving.set(true); this.message.set(null);
    this.http.put<{ domains: string[] }>('/api/google/email-settings', { domains: this.domains.split(/[,;\s]+/).filter(Boolean) }, { params: { kid } }).subscribe({
      next: config => { if (version !== this.version) return; this.domains = config.domains.join('\n'); this.message.set('set.emailDomains.saved'); this.saving.set(false); },
      error: err => { if (version !== this.version) return; this.message.set(err?.status === 400 ? 'set.emailDomains.invalid' : 'set.emailDomains.saveFailed'); this.saving.set(false); },
    });
  }
}
