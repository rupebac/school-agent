import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { I18n } from './i18n';

export interface GoogleStatus {
  clientConfigured: boolean;
  connected: boolean;
  calendarId: string;
  pollingEnabled: boolean;
  mailbox?: string | null;
  authorizeUrl: string | null;
}

export interface CalendarInfo {
  id: string;
  summary: string;
  timeZone: string | null;
}

export interface CalendarSetup {
  connected: boolean;
  configuredCalendarId: string;
  configuredIsPrimary: boolean;
  expectedName: string;
  schoolCalendarFound: CalendarInfo | null;
  calendars: CalendarInfo[];
}

/** Settings screen state for the Google connection (docs/FRONTEND.md §5). */
@Injectable({ providedIn: 'root' })
export class GoogleSetup {
  readonly status = signal<GoogleStatus | null>(null);
  readonly calendar = signal<CalendarSetup | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  private readonly i18n = inject(I18n);

  private activeKid: string | null | undefined;
  private generation = 0;

  constructor(private http: HttpClient) {}

  refresh(kidId: string | null = null): void {
    this.activeKid = kidId;
    const generation = ++this.generation;
    this.error.set(null);
    this.http.get<GoogleStatus>(`/api/google/status${kidId ? `?kid=${kidId}` : ''}`).subscribe({
      next: (status) => {
        if (this.activeKid !== kidId) return;
        if (generation !== this.generation) return;
        this.status.set(status);
        if (status.connected) {
          this.http.get<CalendarSetup>(`/api/google/calendar-setup${kidId ? `?kid=${kidId}` : ''}`).subscribe({
            next: (cal) => { if (this.activeKid === kidId && generation === this.generation) this.calendar.set(cal); },
            error: (err) => { if (this.activeKid === kidId && generation === this.generation) this.calendar.set(null); },
          });
        } else {
          this.calendar.set(null);
        }
      },
      error: (err) => { if (this.activeKid === kidId && generation === this.generation) this.error.set(err?.message ?? this.i18n.t('google.unreachable')); },
    });
  }

  ensureCalendar(kidId: string | null, shareWith: string[]): void {
    this.busy.set(true);
    this.error.set(null);
    this.http.post<CalendarSetup>(`/api/google/calendar-setup${kidId ? `?kid=${kidId}` : ''}`, { shareWith }).subscribe({
      next: (cal) => {
        if (this.activeKid !== kidId) return;
        this.calendar.set(cal);
        this.busy.set(false);
      },
      error: (err) => {
        if (this.activeKid !== kidId) return;
        this.error.set(err?.error?.message ?? err?.message ?? this.i18n.t('google.calFailed'));
        this.busy.set(false);
      },
    });
  }

  disconnect(kidId: string | null = null): void {
    this.busy.set(true);
    this.http.delete(`/api/google/connection${kidId ? `?kid=${kidId}` : ''}`).subscribe({
      next: () => {
        if (this.activeKid !== kidId) return;
        this.busy.set(false);
        this.refresh(kidId);
      },
      error: () => this.busy.set(false),
    });
  }
}
