import { Component, Injectable, inject, signal } from '@angular/core';
interface InstallPrompt extends Event { prompt(): Promise<{ outcome: string }>; }
@Injectable({ providedIn: 'root' })
export class Pwa {
  readonly prompt = signal<InstallPrompt | null>(null);
  readonly installed = signal(!!window.matchMedia?.('(display-mode: standalone)').matches || !!(navigator as Navigator & { standalone?: boolean }).standalone);
  readonly message = signal<string | null>(null);
  constructor() {
    window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); this.prompt.set(event as InstallPrompt); });
    window.addEventListener('appinstalled', () => { this.installed.set(true); this.prompt.set(null); });
  }
  async install(): Promise<void> {
    const event = this.prompt();
    if (!event) return;
    this.prompt.set(null);
    try { await event.prompt(); }
    catch { this.message.set('Open your browser menu and choose Install app or Add to Home Screen.'); }
  }
}
@Component({
  selector: 'app-install',
  template: `<section class="card"><h2>Install School Agent</h2>
    @if (pwa.installed()) { <p>School Agent is running as an installed app.</p> }
    @else {
      @if (pwa.prompt()) { <button type="button" (click)="pwa.install()">Install app</button> }
      <p><strong>iPhone / iPad:</strong> Open in Safari, tap Share, then Add to Home Screen. Keep Open as Web App enabled if shown.</p>
      <p><strong>Android:</strong> Open in Chrome, then use Install app or Add to Home screen in the browser menu.</p>
    }
    <p class="muted">Your selected child is remembered. Internet access is needed; notifications continue through WhatsApp.</p>
    @if (pwa.message()) { <p role="status">{{ pwa.message() }}</p> }
  </section>`,
  styles: ':host { display:block; margin-top:24px; } h2 { font-size:1.15rem; }',
})
export class InstallApp { readonly pwa = inject(Pwa); }
