import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { I18n } from './i18n';

export interface CalendarEntry {
  id: string;
  childId: string | null;
  name: string;
  startDate: string;
  endDate: string;
  entryType: string;
}

@Injectable({ providedIn: 'root' })
export class SchoolCalendarStore {
  readonly entries = signal<CalendarEntry[]>([]);

  readonly importingYear = signal<number | null>(null);
  readonly importResult = signal<{ year: number; imported: number; alreadyPresent: number } | null>(null);
  readonly importError = signal<string | null>(null);
  private readonly i18n = inject(I18n);
  private range: { from: string; to: string } | null = null;
  private refreshVersion = 0;
  readonly importMessage = computed(() => {
    const error = this.importError();
    if (error) return this.i18n.t(error);
    const year = this.importingYear();
    if (year !== null) return this.i18n.t('cal.importing', { year });
    const result = this.importResult();
    return result ? this.i18n.t('cal.imported', result) : '';
  });

  constructor(private http: HttpClient) {}

  refresh(from?: string, to?: string): void {
    if (from && to) this.range = { from, to };
    const q = this.range ? `?from=${this.range.from}&to=${this.range.to}` : '';
    const version = ++this.refreshVersion;
    this.http.get<CalendarEntry[]>(`/api/school-calendar${q}`).subscribe({
      next: e => { if (version === this.refreshVersion) this.entries.set(e); },
      error: () => { if (version === this.refreshVersion) this.importError.set('cal.refreshFailed'); },
    });
  }

  importYear(year: number): void {
    if (this.importingYear() !== null) return;
    this.importingYear.set(year);
    this.importResult.set(null);
    this.importError.set(null);
    this.http.post<{ ok: boolean; imported?: number; alreadyPresent?: number }>(
      `/api/admin/school-calendar/import?year=${year}`, {},
    ).pipe(finalize(() => this.importingYear.set(null))).subscribe({
      next: r => {
        if (!r.ok) { this.importError.set('cal.importFailed'); return; }
        this.importResult.set({ year, imported: r.imported ?? 0, alreadyPresent: r.alreadyPresent ?? 0 });
        // Include the entire imported year instead of silently reverting to the default three months.
        const from = `${year}-01-01`, to = `${year}-12-31`;
        this.refresh(this.range && this.range.from < from ? this.range.from : from,
          this.range && this.range.to > to ? this.range.to : to);
      },
      error: () => this.importError.set('cal.importFailed'),
    });
  }

  addEntry(entry: Partial<CalendarEntry>): void {
    this.http.post('/api/admin/school-calendar', entry).subscribe(() => this.refresh());
  }

  deleteEntry(id: string): void {
    this.http.delete(`/api/admin/school-calendar/${id}`).subscribe(() => this.refresh());
  }
}
