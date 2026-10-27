import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SchoolCalendarStore } from './briefing.store';

describe('School holiday import', () => {
  let store: SchoolCalendarStore;
  let http: HttpTestingController;
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    store = TestBed.inject(SchoolCalendarStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('shows progress, prevents duplicate clicks and loads the entire requested year', () => {
    store.importYear(2027);
    store.importYear(2027);
    expect(store.importingYear()).toBe(2027);
    expect(store.importMessage()).toContain('2027');
    http.expectOne('/api/admin/school-calendar/import?year=2027').flush({ ok: true, imported: 7, alreadyPresent: 0 });
    http.expectOne('/api/school-calendar?from=2027-01-01&to=2027-12-31').flush([
      { id: 'synthetic', childId: null, name: 'Synthetic break', startDate: '2027-08-02', endDate: '2027-08-06', entryType: 'VACATION' },
    ]);
    expect(store.entries()).toHaveLength(1);
    expect(store.importingYear()).toBeNull();
    expect(store.importMessage()).toContain('7');
  });
  it('shows failure for both legacy ok-false responses and HTTP failures', () => {
    store.importYear(2027);
    http.expectOne('/api/admin/school-calendar/import?year=2027').flush({ ok: false });
    expect(store.importError()).toBe('cal.importFailed');
    store.importYear(2027);
    http.expectOne('/api/admin/school-calendar/import?year=2027').flush({}, { status: 502, statusText: 'Bad Gateway' });
    expect(store.importError()).toBe('cal.importFailed');
    expect(store.importingYear()).toBeNull();
    http.expectNone('/api/school-calendar');
  });
  it('reports already-imported data and preserves a later visible range', () => {
    store.refresh('2028-01-01', '2028-02-01');
    http.expectOne('/api/school-calendar?from=2028-01-01&to=2028-02-01').flush([]);
    store.importYear(2027);
    http.expectOne('/api/admin/school-calendar/import?year=2027').flush({ ok: true, imported: 0, alreadyPresent: 7 });
    http.expectOne('/api/school-calendar?from=2027-01-01&to=2028-02-01').flush([]);
    expect(store.importResult()?.alreadyPresent).toBe(7);
  });
  it('a slower old calendar request cannot overwrite the imported year', () => {
    store.refresh();
    const old = http.expectOne('/api/school-calendar');
    store.importYear(2027);
    http.expectOne('/api/admin/school-calendar/import?year=2027').flush({ ok: true, imported: 1, alreadyPresent: 0 });
    http.expectOne('/api/school-calendar?from=2027-01-01&to=2027-12-31').flush([{ id: 'new' }]);
    old.flush([{ id: 'old' }]);
    expect(store.entries()[0].id).toBe('new');
  });
});
