import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Cases } from './cases.store';
import { PortalSetup } from './portal.store';

describe('child context', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }));
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('ignores an older inbox response after switching to an empty child', () => {
    const cases = TestBed.inject(Cases); const http = TestBed.inject(HttpTestingController);
    cases.setKid('jamie'); const first = http.expectOne('/api/cases?kid=jamie');
    cases.setKid('sofia'); const second = http.expectOne('/api/cases?kid=sofia');
    second.flush([]); first.flush([{ id: 'jamie-letter' }]);
    expect(cases.cases()).toEqual([]); expect(cases.loading()).toBe(false);
  });
  it('never fills one child’s connector editor with the previous child’s response', () => {
    const portal = TestBed.inject(PortalSetup); const http = TestBed.inject(HttpTestingController);
    const loaded: string[] = [];
    portal.refresh('jamie', s => loaded.push(s.username!)); const first = http.expectOne('/api/school-connection/status?kid=jamie');
    portal.refresh('sofia', s => loaded.push(s.username!)); const second = http.expectOne('/api/school-connection/status?kid=sofia');
    second.flush({ configured: true, username: 'sofia-parent' }); first.flush({ configured: true, username: 'jamie-parent' });
    expect(portal.status()?.username).toBe('sofia-parent'); expect(loaded).toEqual(['sofia-parent']);
  });
  it('discards Schulmanager child discovery when the selected child changes', () => {
    const portal = TestBed.inject(PortalSetup); const http = TestBed.inject(HttpTestingController);
    portal.refresh('taylor'); http.expectOne('/api/school-connection/status?kid=taylor').flush({ configured: false });
    const loaded: string[] = [];
    portal.discover('taylor', { provider: 'SCHULMANAGER', username: 'parent', password: 'secret' }, identity => loaded.push(identity.students[0].name));
    const discovery = http.expectOne('/api/school-connection/discover?kid=taylor');
    portal.refresh('jamie'); http.expectOne('/api/school-connection/status?kid=jamie').flush({ provider: 'ELTERNPORTAL', configured: true });
    discovery.flush({ ok: true, identity: { institutionId: '1', students: [{ id: '7', name: 'Taylor' }] } });
    expect(loaded).toEqual([]); expect(portal.status()?.provider).toBe('ELTERNPORTAL');
  });

});
