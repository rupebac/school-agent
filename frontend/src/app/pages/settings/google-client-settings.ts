import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18n, TranslatePipe } from '../../core/i18n';

export interface GoogleClientStatus {
  clientId: string;
  hasSecret: boolean;
  configured: boolean;
  redirectUri: string;
  source: 'settings' | 'environment' | 'none';
}

@Component({
  selector: 'app-google-client-settings',
  imports: [FormsModule, TranslatePipe],
  templateUrl: './google-client-settings.html',
  styles: `
    :host { display: block; }
    h2 { font-size: 1.15rem; }
    label { display: grid; gap: 6px; margin: 16px 0; }
    input { width: 100%; min-width: 0; }
    .callback { display: flex; align-items: center; gap: 8px; }
    .callback input { flex: 1; font-family: monospace; }
    .confirm { display: flex; align-items: flex-start; }
    .confirm input { width: auto; margin-top: 4px; }
    li { margin: 8px 0; }
    .error { color: var(--danger, #b42318); }
  `,
})
export class GoogleClientSettings implements OnInit {
  private readonly http = inject(HttpClient);
  readonly i18n = inject(I18n);
  readonly saved = output<void>();
  readonly status = signal<GoogleClientStatus | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  clientId = '';
  clientSecret = '';
  confirmClientChange = false;

  ngOnInit(): void { this.load(); }

  load(): void {
    this.error.set(null);
    this.http.get<GoogleClientStatus>('/api/admin/google/client').subscribe({
      next: status => this.apply(status),
      error: () => this.error.set(this.i18n.t('set.googleClient.loadFailed')),
    });
  }

  clientChanging(): boolean {
    return !!this.status()?.configured && this.clientId.trim() !== this.status()?.clientId;
  }

  canSave(): boolean {
    const status = this.status();
    return !!status && !this.busy() && /^[A-Za-z0-9._-]+\.apps\.googleusercontent\.com$/.test(this.clientId.trim()) &&
      (!!this.clientSecret.trim() || (status.hasSecret && this.clientId.trim() === status.clientId)) &&
      (!this.clientChanging() || this.confirmClientChange);
  }

  save(): void {
    if (!this.canSave()) return;
    this.busy.set(true); this.error.set(null); this.message.set(null);
    this.http.put<GoogleClientStatus>('/api/admin/google/client', {
      clientId: this.clientId.trim(),
      clientSecret: this.clientSecret.trim() || null,
      confirmClientChange: this.confirmClientChange,
    }).subscribe({
      next: status => {
        this.apply(status); this.busy.set(false);
        this.message.set(this.i18n.t('set.googleClient.saved'));
        this.saved.emit();
      },
      error: () => {
        this.clientSecret = ''; this.busy.set(false);
        this.error.set(this.i18n.t('set.googleClient.saveFailed'));
      },
    });
  }

  async copyCallback(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.status()!.redirectUri);
      this.message.set(this.i18n.t('set.googleClient.copied'));
    } catch {
      this.message.set(this.i18n.t('set.googleClient.copyFailed'));
    }
  }

  private apply(status: GoogleClientStatus): void {
    this.status.set(status); this.clientId = status.clientId;
    this.clientSecret = ''; this.confirmClientChange = false;
  }
}
