import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

if (new URLSearchParams(location.search).get('launch') === 'home') {
  let target = '/';
  try { target = localStorage.getItem('schoolagent.lastPage') || '/'; } catch { /* private mode */ }
  history.replaceState(null, '', /^\/(?:cases\/[a-f0-9-]+)?$/.test(target) ? target : '/');
}
if ('serviceWorker' in navigator && window.isSecureContext) {
  window.addEventListener('load', () => { void navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' }).catch(() => {}); });
}

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
