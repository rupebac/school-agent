import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';

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

  constructor(private http: HttpClient) {}

  refresh(from?: string, to?: string): void {
    const q = from && to ? `?from=${from}&to=${to}` : '';
    this.http.get<CalendarEntry[]>(`/api/school-calendar${q}`).subscribe((e) => this.entries.set(e));
  }

  importYear(year: number): void {
    this.http.post<{ ok: boolean; imported?: number; error?: string }>(
      `/api/admin/school-calendar/import?year=${year}`, {},
    ).subscribe((r) => this.refresh());
  }

  addEntry(entry: Partial<CalendarEntry>): void {
    this.http.post('/api/admin/school-calendar', entry).subscribe(() => this.refresh());
  }

  deleteEntry(id: string): void {
    this.http.delete(`/api/admin/school-calendar/${id}`).subscribe(() => this.refresh());
  }
}
