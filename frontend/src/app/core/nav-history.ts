import { computed, inject, Injectable, signal } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/**
 * Remembers the last page that was not a letter, so closing a letter returns to
 * where it was opened (inbox with its filter, calendar month/day, search query).
 */
@Injectable({ providedIn: 'root' })
export class NavHistory {
  private readonly router = inject(Router);
  readonly returnUrl = signal('/');
  readonly returnTree = computed(() => this.router.parseUrl(this.returnUrl()));
  readonly returnLabel = computed(() => {
    const url = this.returnUrl();
    if (url.startsWith('/calendar')) return 'nav.calendarPage';
    if (url.startsWith('/search')) return 'nav.searchAria';
    if (url.startsWith('/audit')) return 'nav.audit';
    return 'nav.inbox';
  });

  constructor() {
    this.router.events.pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd)).subscribe((e) => {
      const url = e.urlAfterRedirects;
      if (!url.startsWith('/cases/') && !url.startsWith('/login')) this.returnUrl.set(url);
    });
  }
}
