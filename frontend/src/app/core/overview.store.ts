import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { I18n } from './i18n';
import { Cases, CaseSummary } from './cases.store';

export interface SeenBy {
  name: string;
  displayName: string;
  at: string;
}

export interface ParentAction {
  id: string;
  caseId: string;
  caseTitle: string | null;
  title: string;
  description: string | null;
  dueAt: string | null;
  assignedTo: string | null;
  assignedToName: string | null;
  status: string;
  externalUrl: string | null;
  externalLink?: { url: string; kind: 'FORM' | 'PORTAL'; provider?: string | null } | null;
  required?: boolean;
  score?: number | null;
  createdAt?: string | null;
  acknowledgedBy?: SeenBy[];
}

export interface RequirementDto {
  id: string;
  caseId: string;
  caseTitle: string | null;
  description: string;
  relevantOn: string | null;
  status: string;
  required: boolean;
}

export interface SchoolEventDto {
  id: string;
  caseId: string;
  caseTitle: string | null;
  title: string;
  eventType: string;
  startTime: string;
  endTime: string | null;
  allDay: boolean;
  location: string | null;
  status: string;
}

export interface AuditEntry {
  id: string;
  caseId: string | null;
  actor: string;
  action: string;
  detail: string | null;
  createdAt: string;
}

export interface ConnectorHealth {
  connector: string;
  status: string;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastError: string | null;
  consecutiveFailures: number;
}

export interface UserDto {
  id: string;
  name: string;
  displayName: string;
}

export function isActionClosed(a: { status: string }): boolean {
  return a.status === 'COMPLETED' || a.status === 'CANCELLED' || a.status === 'DONE';
}

export function isActionOverdue(a: { status: string; dueAt: string | null }): boolean {
  if (isActionClosed(a)) return false;
  if (a.status === 'OVERDUE') return true;
  if (!a.dueAt) return false;
  return new Date(a.dueAt).getTime() < Date.now();
}

export function isActionPending(a: { status: string; dueAt: string | null }): boolean {
  return !isActionClosed(a) && !isActionOverdue(a);
}

/** Other parents who opened the case — never the person looking at the list. */
export function otherViewers(
  acks: { name: string; displayName?: string }[] | undefined | null,
  meName: string | null | undefined,
): string | null {
  if (!acks?.length) return null;
  const me = (meName ?? '').toLowerCase();
  const names = acks
    .filter((a) => a.name.toLowerCase() !== me)
    .map((a) => a.displayName || a.name);
  return names.length ? names.join(', ') : null;
}

/** Stores for the Actions / Calendar / Audit screens and the health strip. */
@Injectable({ providedIn: 'root' })
export class ActionsStore {
  readonly actions = signal<ParentAction[]>([]);
  readonly users = signal<UserDto[]>([]);
  readonly loading = signal(true);
  private readonly cases = inject(Cases);

  constructor(private http: HttpClient) {}

  refresh(after?: () => void): void {
    this.loading.set(true);
    this.http.get<ParentAction[]>('/api/actions').subscribe({
      next: (a) => {
        this.actions.set(a);
        this.loading.set(false);
        after?.();
      },
      error: () => this.loading.set(false),
    });
    this.http.get<UserDto[]>('/api/users').subscribe((u) => this.users.set(u));
  }

  complete(id: string, after?: () => void): void {
    this.http.put<ParentAction>(`/api/actions/${id}/complete`, {}).subscribe(() => {
      this.refresh();
      this.cases.refresh();
      after?.();
    });
  }

  reopen(id: string, after?: () => void): void {
    this.http.put<ParentAction>(`/api/actions/${id}/reopen`, {}).subscribe(() => {
      this.refresh();
      this.cases.refresh();
      after?.();
    });
  }

  create(caseId: string, body: { title: string; description?: string | null; dueAt?: string | null }, after?: () => void): void {
    this.http.post<ParentAction>(`/api/actions/case/${caseId}`, body).subscribe({
      next: () => {
        this.refresh();
        this.cases.refresh();
        after?.();
      },
    });
  }

  update(id: string, body: { title?: string; description?: string | null; dueAt?: string | null }, after?: () => void): void {
    this.http.put<ParentAction>(`/api/actions/${id}`, body).subscribe({
      next: () => {
        this.refresh();
        after?.();
      },
    });
  }

  remove(id: string, after?: () => void): void {
    this.http.delete(`/api/actions/${id}`).subscribe({
      next: () => {
        this.refresh();
        this.cases.refresh();
        after?.();
      },
    });
  }
}

@Injectable({ providedIn: 'root' })
export class RequirementsStore {
  readonly requirements = signal<RequirementDto[]>([]);
  readonly loading = signal(true);
  private readonly cases = inject(Cases);

  constructor(private http: HttpClient) {}

  refresh(): void {
    this.loading.set(true);
    this.http.get<RequirementDto[]>('/api/requirements').subscribe({
      next: (r) => {
        this.requirements.set(r);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  complete(id: string, after?: () => void): void {
    this.http.put<RequirementDto>(`/api/requirements/${id}/complete`, {}).subscribe({
      next: () => {
        this.refresh();
        after?.();
      },
    });
  }

  create(caseId: string, body: { description: string; relevantOn?: string | null }, after?: () => void): void {
    this.http.post<RequirementDto>(`/api/requirements/case/${caseId}`, body).subscribe({
      next: () => {
        this.refresh();
        this.cases.refresh();
        after?.();
      },
    });
  }

  update(id: string, body: { description?: string; relevantOn?: string | null }, after?: () => void): void {
    this.http.put<RequirementDto>(`/api/requirements/${id}`, body).subscribe({
      next: () => {
        this.refresh();
        after?.();
      },
    });
  }

  remove(id: string, after?: () => void): void {
    this.http.delete(`/api/requirements/${id}`).subscribe({
      next: () => {
        this.refresh();
        this.cases.refresh();
        after?.();
      },
    });
  }
}

@Injectable({ providedIn: 'root' })
export class EventsStore {
  readonly events = signal<SchoolEventDto[]>([]);
  readonly loading = signal(true);

  constructor(private http: HttpClient) {}

  refresh(from?: Date, to?: Date): void {
    this.loading.set(true);
    const start = from ?? new Date(Date.now() - 7 * 24 * 3600 * 1000);
    const end = to ?? new Date(Date.now() + 90 * 24 * 3600 * 1000);
    const params = [`from=${encodeURIComponent(start.toISOString())}`, `to=${encodeURIComponent(end.toISOString())}`];
    this.http.get<SchoolEventDto[]>(`/api/events?${params.join('&')}`).subscribe({
      next: (e) => {
        this.events.set(e);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}

@Injectable({ providedIn: 'root' })
export class AuditStore {
  readonly entries = signal<AuditEntry[]>([]);

  constructor(private http: HttpClient) {}

  refresh(): void {
    this.http.get<AuditEntry[]>('/api/audit').subscribe((e) => this.entries.set(e));
  }
}

@Injectable({ providedIn: 'root' })
export class HealthStore {
  readonly connectors = signal<ConnectorHealth[]>([]);
  private readonly i18n = inject(I18n);

  constructor(private http: HttpClient) {}

  refresh(): void {
    this.http.get<ConnectorHealth[]>('/api/health/connectors').subscribe((c) => this.connectors.set(c));
  }

  lastSuccess(c: ConnectorHealth): string {
    if (!c.lastSuccessAt) return this.i18n.t('health.never');
    const mins = Math.round((Date.now() - new Date(c.lastSuccessAt).getTime()) / 60000);
    return mins < 1 ? this.i18n.t('health.justNow') : this.i18n.t('health.minAgo', { n: mins });
  }

  tooltip(c: ConnectorHealth): string {
    const err = c.lastError ? ` — ${c.lastError}` : '';
    return `${c.connector}: ${c.status}${this.lastSuccess(c)}${err}`;
  }

  problems(): ConnectorHealth[] {
    return this.connectors().filter((c) => c.status === 'DOWN' || c.status === 'DEGRADED');
  }
}

export type { CaseSummary };
