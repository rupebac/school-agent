import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { I18n } from './i18n';

export type SchoolProvider = 'ELTERNPORTAL' | 'SCHULMANAGER';
export interface SchoolIdentity { institutionId: string | null; schoolName: string | null; students: { id: string; name: string; className?: string | null }[]; }
export interface SchoolConnectionConfig { provider: SchoolProvider; url?: string; username: string; password?: string | null; studentId?: string | null; institutionId?: string | null; studentName?: string | null; }
export interface PortalStatus {
  provider?: SchoolProvider | null;
  hasPassword?: boolean;
  studentId?: string | null;
  institutionId?: string | null;
  studentName?: string | null;
  configured: boolean;
  url: string | null;
  username: string | null;
  password: string | null;
  lastSync: string | null;
  sections?: { kind: string; status: string; records: number; message: string | null; checkedAt: string; lastSuccessAt?: string | null }[];
}

export interface PortalTestResult {
  ok: boolean;
  briefsFound?: number;
  error?: string;
}

export interface PortalSyncResult {
  ok: boolean;
  briefsSeen?: number;
  recordsSeen?: number;
  recordsChanged?: number;
  ingested?: number;
  failed?: number;
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class PortalSetup {
  readonly status = signal<PortalStatus | null>(null);
  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  private readonly i18n = inject(I18n);

  private activeKid: string | null | undefined;
  private generation = 0;

  constructor(private http: HttpClient) {}

  refresh(kidId: string | null = null, onLoad?: (s: PortalStatus) => void): void {
    this.activeKid = kidId;
    this.message.set(null);
    const generation = ++this.generation;
    this.http.get<PortalStatus>(`/api/school-connection/status${kidId ? `?kid=${kidId}` : ''}`).subscribe((s) => {
      if (this.activeKid !== kidId || generation !== this.generation) return;
      this.status.set(s);
      onLoad?.(s);
    });
  }

  save(kidId: string | null, config: SchoolConnectionConfig, onLoad?: (s: PortalStatus) => void): void {
    const generation = this.generation;
    this.busy.set(true);
    this.message.set(null);
    this.http.put<PortalStatus>(`/api/school-connection/config${kidId ? `?kid=${kidId}` : ''}`, config).subscribe({
      next: (s) => {
        if (this.activeKid !== kidId || generation !== this.generation) return;
        this.status.set(s);
        this.message.set(this.i18n.t('portal.saved'));
        this.busy.set(false);
        onLoad?.(s);
      },
      error: (err) => {
        if (this.activeKid !== kidId || generation !== this.generation) return;
        this.message.set(err?.error?.message ?? this.i18n.t('portal.saveFailed'));
        this.busy.set(false);
      },
    });
  }

  discover(kidId: string | null, config: SchoolConnectionConfig, onLoad: (identity: SchoolIdentity) => void): void {
    const generation = this.generation;
    this.busy.set(true); this.message.set(null);
    this.http.post<{ ok: boolean; identity?: SchoolIdentity; error?: string }>(`/api/school-connection/discover?kid=${kidId}`, config).subscribe({
      next: result => {
        if (this.activeKid !== kidId || generation !== this.generation) return;
        this.busy.set(false);
        if (result.ok && result.identity) { onLoad(result.identity); this.message.set('Choose the matching child, then save the connection.'); }
        else this.message.set(result.error ?? 'Could not find children in this account.');
      },
      error: () => { if (this.activeKid !== kidId || generation !== this.generation) return; this.busy.set(false); this.message.set('Could not test these credentials.'); },
    });
  }

  test(kidId: string | null = null): void {
    const generation = this.generation;
    this.busy.set(true);
    this.message.set(null);
    this.http.post<PortalTestResult>(`/api/school-connection/test${kidId ? `?kid=${kidId}` : ''}`, {}).subscribe({
      next: (r) => {
        if (this.activeKid !== kidId || generation !== this.generation) return;
        this.message.set(r.ok ? this.i18n.t('portal.loginOk', { n: r.briefsFound ?? 0 }) : `⚠️ ${r.error}`);
        this.busy.set(false);
      },
      error: (err) => {
        if (this.activeKid !== kidId || generation !== this.generation) return;
        this.message.set(this.i18n.t('portal.testFailed', { err: err?.message ?? '' }));
        this.busy.set(false);
      },
    });
  }

  sync(kidId: string | null = null): void {
    const generation = this.generation;
    this.busy.set(true);
    this.message.set(this.i18n.t('portal.syncing'));
    this.http.post<PortalSyncResult>(`/api/school-connection/sync${kidId ? `?kid=${kidId}` : ''}`, {}).subscribe({
      next: (r) => {
        if (this.activeKid !== kidId || generation !== this.generation) return;
        this.message.set(
          r.ok
            ? this.i18n.t('portal.synced', { seen: r.briefsSeen ?? 0, ingested: r.ingested ?? 0 }) + (r.recordsSeen ? ' ' + this.i18n.t('portal.recordsSynced', { seen: r.recordsSeen, changed: r.recordsChanged ?? 0 }) : '') + (r.failed ? ' ' + this.i18n.t('portal.itemsFailed', { failed: r.failed }) : '')
            : `⚠️ ${r.error}`,
        );
        this.busy.set(false);
        const message = this.message();
        this.refresh(kidId, () => this.message.set(message));
      },
      error: (err) => {
        if (this.activeKid !== kidId || generation !== this.generation) return;
        this.message.set(this.i18n.t('portal.syncFailed', { err: err?.message ?? '' }));
        this.busy.set(false);
      },
    });
  }
}
