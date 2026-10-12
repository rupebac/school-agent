import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { I18n } from './i18n';

export interface Me {
  name: string;
  displayName: string;
  role: string;
  whatsappNumber: string | null;
  language: string;
  briefingEnabled?: boolean;
  briefingTime?: string;
  weeklyEnabled?: boolean;
  weeklyTime?: string;
  instantEnabled?: boolean;
}

/**
 * Session auth: server-side JSESSIONID + optional 14-day remember-me cookie
 * (persistent_logins). The browser handles both cookies and the XSRF token
 * automatically; the app only tracks who is signed in.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  readonly me = signal<Me | null>(null);
  private restoring = false;
  private readonly i18n = inject(I18n);

  constructor(private http: HttpClient) {}

  private applyMe(m: Me): void {
    this.me.set(m);
    this.i18n.setLang(m.language);
  }

  get isAdmin(): boolean {
    return this.me()?.role === 'ADMIN';
  }

  /** Restores an existing session (or remember-me cookie) on app boot. */
  restore(): Observable<Me> {
    this.restoring = true;
    return this.http.get<Me>('/api/auth/me').pipe(tap({ next: (m) => this.applyMe(m), finalize: () => (this.restoring = false) }));
  }

  login(user: string, password: string, rememberMe: boolean): Observable<Me> {
    return this.http
      .post<never>('/api/auth/login', { username: user, password, rememberMe }, { responseType: 'text' as never })
      .pipe(
        tap(() => {
          this.http.get<Me>('/api/auth/me').subscribe((m) => this.applyMe(m));
        }),
      ) as Observable<Me>;
  }

  logout(): void {
    try { localStorage.removeItem('schoolagent.lastPage'); } catch { /* private mode */ }
    this.http.post('/api/auth/logout', {}).subscribe({
      complete: () => this.me.set(null),
    });
  }
}
