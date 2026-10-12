import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { I18n } from './i18n';

export interface Preferences {
  briefingEnabled: boolean;
  briefingTime: string;
  weeklyEnabled: boolean;
  weeklyTime: string;
  instantEnabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class PreferencesStore {
  readonly prefs = signal<Preferences | null>(null);
  readonly message = signal<string | null>(null);
  private readonly i18n = inject(I18n);

  constructor(private http: HttpClient) {}

  loadFromMe(me: {
    briefingEnabled?: boolean;
    briefingTime?: string;
    weeklyEnabled?: boolean;
    weeklyTime?: string;
    instantEnabled?: boolean;
  } | null): void {
    this.prefs.set({
      briefingEnabled: me?.briefingEnabled ?? true,
      briefingTime: me?.briefingTime ?? '19:00',
      weeklyEnabled: me?.weeklyEnabled ?? true,
      weeklyTime: me?.weeklyTime ?? '10:00',
      instantEnabled: me?.instantEnabled ?? true,
    });
  }

  save(prefs: Preferences): void {
    this.http.put('/api/account/preferences', prefs).subscribe({
      next: () => {
        this.prefs.set(prefs);
        this.message.set(this.i18n.t('set.briefing.saved'));
        setTimeout(() => this.message.set(null), 3000);
      },
      error: (err) => this.message.set('⚠️ ' + (err?.error?.message ?? err?.error ?? this.i18n.t('set.saveFailed'))),
    });
  }
}
