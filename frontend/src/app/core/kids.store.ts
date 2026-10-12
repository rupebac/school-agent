import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';

export interface Kid {
  id: string;
  name: string;
  school: string | null;
  className: string | null;
  color: string;
  assignedAccounts: string[];
}

const STORAGE_KEY = 'schoolagent.activeKid';

/** Kid registry + the active-kid selector (null = all kids, except on Settings). */
@Injectable({ providedIn: 'root' })
export class KidsStore {
  readonly kids = signal<Kid[]>([]);
  readonly activeKidId = signal<string | null>(KidsStore.readStored());

  constructor(private http: HttpClient) {}

  refresh(onLoad?: (kids: Kid[]) => void): void {
    this.http.get<Kid[]>('/api/kids').subscribe((k) => {
      this.kids.set(k);
      const current = this.activeKidId();
      if (current && !k.some((x) => x.id === current)) this.select(null);
      onLoad?.(k);
    });
  }

  select(id: string | null): void {
    this.activeKidId.set(id);
    try {
      if (id) localStorage.setItem(STORAGE_KEY, id);
      else localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* private mode */
    }
  }

  /** Settings connectors need a concrete child — pick one if the header is on “all”. */
  ensureSelected(): string | null {
    if (this.activeKidId()) return this.activeKidId();
    const first = this.kids()[0]?.id ?? null;
    if (first) this.select(first);
    return first;
  }

  activeKid(): Kid | null {
    const id = this.activeKidId();
    return this.kids().find((k) => k.id === id) ?? null;
  }

  private static readStored(): string | null {
    try {
      return localStorage.getItem(STORAGE_KEY);
    } catch {
      return null;
    }
  }
}
