import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { Shell } from './layout/shell';
import { LoginPage } from './pages/login/login';
import { InboxPage } from './pages/inbox/inbox';
import { CaseDetailPage } from './pages/case-detail/case-detail';

export const routes: Routes = [
  { path: 'login', component: LoginPage },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      {
        // The inbox stays mounted while a case is open beside it (or over it on small screens).
        path: '',
        component: InboxPage,
        data: { section: 'inbox', title: 'nav.inbox', wide: true },
        children: [{ path: 'cases/:id', component: CaseDetailPage }],
      },
      { path: 'cases', pathMatch: 'full', redirectTo: '' },
      { path: 'actions', pathMatch: 'full', redirectTo: '' },
      { path: 'calendar', loadComponent: () => import('./pages/calendar/calendar').then(m => m.CalendarPage), data: { section: 'calendar', title: 'nav.calendarPage', wide: true } },
      { path: 'settings', data: { section: 'settings', title: 'nav.settings' }, loadComponent: () => import('./pages/settings/settings').then(m => m.SettingsPage) },
      { path: 'audit', loadComponent: () => import('./pages/audit/audit').then(m => m.AuditPage), data: { section: 'audit', title: 'nav.audit' } },
      { path: 'search', loadComponent: () => import('./pages/search/search').then(m => m.SearchPage), data: { section: 'search', title: 'nav.searchAria' } },
    ],
  },
  { path: '**', redirectTo: '' },
];
