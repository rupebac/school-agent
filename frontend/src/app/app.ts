import { Pwa } from './core/pwa';
import { Component, inject } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly pwa = inject(Pwa);
  constructor() {
    inject(Router).events.subscribe(event => {
      if (event instanceof NavigationEnd && /^\/(?:cases\/[a-f0-9-]+)?$/.test(event.urlAfterRedirects)) {
        try { localStorage.setItem('schoolagent.lastPage', event.urlAfterRedirects); } catch { /* private mode */ }
      }
    });
  }
}
