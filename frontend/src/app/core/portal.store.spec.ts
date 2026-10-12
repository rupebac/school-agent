import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { I18n } from './i18n';
import { ES } from './i18n.es';
import { PortalSetup } from './portal.store';

describe('PortalSetup sync results', () => {
  let http: HttpTestingController;
  let store: PortalSetup;
  let i18n: I18n;
  const status = { configured: true, url: 'https://school.example', username: null, password: null, lastSync: null };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(PortalSetup);
    i18n = TestBed.inject(I18n);
    i18n.setLang('en');
    store.refresh('child');
    http.expectOne('/api/school-connection/status?kid=child').flush(status);
  });
  afterEach(() => http.verify());

  it('keeps native section results visible after status refresh', () => {
    store.sync('child');
    http.expectOne('/api/school-connection/sync?kid=child').flush({ ok: true, briefsSeen: 2, ingested: 1, recordsSeen: 5, recordsChanged: 3, failed: 1 });
    http.expectOne('/api/school-connection/status?kid=child').flush(status);
    expect(store.message()).toContain('5 school records checked, 3 updated.');
    expect(store.message()).toContain('1 letters or record sections');
    expect(store.busy()).toBe(false);
  });

  it('uses the active language for record counts and partial failures', () => {
    i18n.applyPack('es', ES);
    store.sync('child');
    http.expectOne('/api/school-connection/sync?kid=child').flush({ ok: true, briefsSeen: 2, ingested: 1, recordsSeen: 5, recordsChanged: 3, failed: 1 });
    http.expectOne('/api/school-connection/status?kid=child').flush(status);
    expect(store.message()).toContain('5 registros escolares revisados, 3 actualizados.');
    expect(store.message()).toContain('se reintentará');
    expect(store.message()).not.toContain('school records checked');
  });
});
