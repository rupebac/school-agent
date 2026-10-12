import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { I18n } from './i18n';

export interface WhatsappRecipient {
  name: string;
  displayName: string;
  number: string | null;
}

export interface WhatsappStatus {
  configured: boolean;
  apiUrl: string;
  phoneNumberId: string | null;
  wabaId: string | null;
  token: string | null;
  verifyToken: string | null;
  appSecret: string | null;
  hasToken: boolean;
  hasVerifyToken: boolean;
  hasAppSecret: boolean;
  frontendUrl: string;
  webhookUrl: string;
  lastWebhookAt: string | null;
  recipients: WhatsappRecipient[];
  templates?: WhatsappTemplate[];
  deliveries?: { recipient: string; status: string; createdAt: string; message: string; error: string | null; nextAttemptAt: string | null; tracked: boolean }[];
}

export interface WhatsappTemplate {
  name: string;
  language: string;
  status: string;
  error?: string | null;
}

@Injectable({ providedIn: 'root' })
export class WhatsappSetup {
  readonly status = signal<WhatsappStatus | null>(null);
  readonly approved = computed(() => this.status()?.templates?.filter(t => t.status === 'APPROVED').length ?? 0);
  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  private readonly i18n = inject(I18n);

  private activeKid: string | null | undefined;
  private generation = 0;

  constructor(private http: HttpClient) {}

  private q(kidId: string | null): string {
    return kidId ? `?kid=${kidId}` : '';
  }

  refresh(kidId: string | null = null, onLoad?: (s: WhatsappStatus) => void): void {
    this.activeKid = kidId;
    const generation = ++this.generation;
    this.http.get<WhatsappStatus>(`/api/whatsapp/status${this.q(kidId)}`).subscribe((s) => {
      if (this.activeKid !== kidId || generation !== this.generation) return;
      this.status.set(s);
      onLoad?.(s);
    });
  }

  save(
    kidId: string | null,
    form: {
      apiUrl: string;
      phoneNumberId: string;
      wabaId: string;
      token: string;
      verifyToken: string;
      appSecret: string;
      frontendUrl: string;
    },
    onLoad?: (s: WhatsappStatus) => void,
  ): void {
    this.busy.set(true);
    this.message.set(null);
    this.http
      .put<WhatsappStatus>(`/api/whatsapp/config${this.q(kidId)}`, {
        apiUrl: form.apiUrl || null,
        phoneNumberId: form.phoneNumberId || null,
        wabaId: form.wabaId || null,
        token: form.token,
        verifyToken: form.verifyToken,
        appSecret: form.appSecret,
        frontendUrl: form.frontendUrl || null,
      })
      .subscribe({
        next: (s) => {
        if (this.activeKid !== kidId) return;
          this.status.set(s);
          this.message.set(this.i18n.t('wa.saved'));
          this.busy.set(false);
          onLoad?.(s);
        },
        error: (err) => {
        if (this.activeKid !== kidId) return;
          this.message.set(this.i18n.t('wa.saveFailed', { err: err?.message ?? '' }));
          this.busy.set(false);
        },
      });
  }

  test(kidId: string | null, to: string): void {
    if (!to) {
      this.message.set(this.i18n.t('wa.selectAccount'));
      return;
    }
    this.busy.set(true);
    this.message.set(this.i18n.t('wa.sending'));
    this.http.post<{ ok: boolean; error?: string; to?: string }>(`/api/whatsapp/test${this.q(kidId)}`, { to }).subscribe({
      next: (r) => {
        if (this.activeKid !== kidId) return;
        this.message.set(
          r.ok
            ? this.i18n.t('wa.testOk', { to: r.to ?? '' })
            : `⚠️ ${r.error}`,
        );
        this.busy.set(false);
      },
      error: (err) => {
        if (this.activeKid !== kidId) return;
        this.message.set(this.i18n.t('wa.testFail', { err: err?.message ?? '' }));
        this.busy.set(false);
      },
    });
  }

  subscribeWaba(kidId: string | null): void {
    this.busy.set(true);
    this.message.set(this.i18n.t('wa.attaching'));
    this.http.post<{ ok: boolean; error?: string; wabaId?: string }>(`/api/whatsapp/subscribe-waba${this.q(kidId)}`, {}).subscribe({
      next: (r) => {
        if (this.activeKid !== kidId) return;
        this.message.set(
          r.ok
            ? this.i18n.t('wa.attachOk', { id: r.wabaId ?? '' })
            : `⚠️ ${r.error}`,
        );
        this.busy.set(false);
        this.refresh(kidId);
      },
      error: (err) => {
        if (this.activeKid !== kidId) return;
        this.message.set(this.i18n.t('wa.attachFail', { err: err?.message ?? '' }));
        this.busy.set(false);
      },
    });
  }

  syncTemplates(kidId: string | null): void {
    this.busy.set(true);
    this.message.set(this.i18n.t('wa.syncing'));
    this.http.post<{ ok: boolean; error?: string }>(`/api/whatsapp/templates/sync${this.q(kidId)}`, {}).subscribe({
      next: (r) => {
        if (this.activeKid !== kidId) return;
        this.message.set(r.ok ? this.i18n.t('wa.syncOk') : `⚠️ ${r.error}`);
        this.busy.set(false);
        this.refresh(kidId);
      },
      error: (err) => {
        if (this.activeKid !== kidId) return;
        this.message.set(this.i18n.t('wa.syncFail', { err: err?.message ?? '' }));
        this.busy.set(false);
      },
    });
  }
}
