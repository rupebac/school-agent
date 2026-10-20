import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { InboxPage } from './inbox';
import { Cases } from '../../core/cases.store';
import { ActionsStore } from '../../core/overview.store';

const rows = [
  { id: 'one', title: 'First letter', status: 'ACTION_REQUIRED', informationOnly: false, score: 3, openActions: 1 },
  { id: 'two', title: 'Second letter', status: 'ACTION_REQUIRED', informationOnly: false, score: 3, openActions: 1 },
];

describe('Inbox background refresh', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ imports: [InboxPage], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  });

  function setup() {
    const fixture = TestBed.createComponent(InboxPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.match(() => true).forEach(request => request.flush(request.request.url === '/api/cases' ? rows : []));
    fixture.detectChanges();
    return { fixture, http, cards: () => [...fixture.nativeElement.querySelectorAll('article.item-card')] as HTMLElement[] };
  }

  it('uses the resolved portal or form destination and hides unverified homepage links', () => {
    const { fixture } = setup();
    const overview = TestBed.inject(ActionsStore);
    const action = { id: 'consent', caseId: 'one', caseTitle: 'Data sharing', title: 'Update consent', description: null,
      dueAt: null, status: 'OPEN', assignedTo: null, assignedToName: null, externalUrl: 'https://school.example/' };
    overview.actions.set([{ ...action, externalLink: { url: 'https://school.eltern-portal.org/meldungen/datenfreigabe', kind: 'PORTAL' } }]);
    fixture.detectChanges();
    const link = () => fixture.nativeElement.querySelector('a.pill.link') as HTMLAnchorElement | null;
    expect(link()?.textContent).toContain('Open Elternportal');
    expect(link()?.href).toBe('https://school.eltern-portal.org/meldungen/datenfreigabe');
    expect(link()?.title).toContain('Sign in if prompted');
    overview.actions.set([{ ...action, externalLink: null }]);
    fixture.detectChanges();
    expect(link()).toBeNull();
    overview.actions.set([{ ...action, externalLink: { url: 'https://forms.example/register?id=42', kind: 'FORM' } }]);
    fixture.detectChanges();
    expect(link()?.textContent).toContain('Open form');
    expect(link()?.href).toBe('https://forms.example/register?id=42');
  });

  it('uses relative dates within seven Berlin days and explicit dates beyond that', () => {
    const { fixture } = setup();
    const page = fixture.componentInstance;
    const row = { ...page.items()[0], action: {
      id: 'book', caseId: 'one', caseTitle: 'Class book', title: 'Buy the book', description: null,
      dueAt: '2026-10-04T22:00:00Z', status: 'OPEN', assignedTo: null, assignedToName: null, externalUrl: null,
    } };
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-09-29T10:00:00Z'));
      expect(page.due(row)?.text).toBe('in 6 days');
      vi.setSystemTime(new Date('2026-10-04T21:00:00Z'));
      expect(page.due(row)?.text).toBe('tomorrow');
      vi.setSystemTime(new Date('2026-10-04T22:30:00Z'));
      expect(page.due(row)?.text).toBe('today');
      vi.setSystemTime(new Date('2026-10-06T10:00:00Z'));
      expect(page.due(row)?.text).toBe('yesterday');
      vi.setSystemTime(new Date('2026-09-27T10:00:00Z'));
      expect(page.due(row)?.text).toContain('Mon');
      expect(page.due(row)?.text).toContain('5 Oct');
    } finally { vi.useRealTimers(); }
  });

  it('keeps event dates in the date block and only adds a second day for a different deadline', () => {
    const { fixture } = setup();
    const page = fixture.componentInstance;
    const row = { ...page.items()[0], event: {
      id: 'visit', caseId: 'one', caseTitle: 'Counselling', title: 'Counselling', eventType: 'PARENT_EVENT',
      startTime: '2026-10-12T13:00:00Z', endTime: null, allDay: false, location: null, status: 'PLANNED',
    }, action: null };
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-09-29T10:00:00Z'));
      expect(page.due(row)).toBeNull();
      expect(page.eventWhen(row)).toContain('12 Oct');
      expect(page.eventWhen(row)).toContain('15:00');
      const action = { id: 'reply', caseId: 'one', caseTitle: null, title: 'Reply', description: null,
        dueAt: '2026-10-11T22:00:00Z', status: 'OPEN', assignedTo: null, assignedToName: null, externalUrl: null };
      expect(page.eventWhen({ ...row, action })).toBe('15:00');
      expect(page.eventWhen({ ...row, action: { ...action, dueAt: '2026-10-04T22:00:00Z' } })).toContain('12 Oct');
    } finally { vi.useRealTimers(); }
  });

  it('retains the same card nodes through Done and independently delayed refreshes', async () => {
    const { fixture, http, cards } = setup();
    const card = (title: string) => cards().find(c => c.textContent?.includes(title));
    const original = cards();
    const second = card('Second letter');
    fixture.componentInstance.done(fixture.componentInstance.shown()[0]);
    http.expectOne('/api/cases/one/complete').flush({});
    await fixture.whenStable();
    expect(cards()).toEqual(original);
    http.expectOne('/api/cases').flush([{ ...rows[0], status: 'COMPLETED', openActions: 0, canUndo: true }, rows[1]]);
    await fixture.whenStable();
    // Done disappears from Active; the untouched letter keeps its existing node.
    expect(card('Second letter')).toBe(second);
    // The actions request is still in flight; it must not blank the feed either.
    expect(cards()).toHaveLength(1);
    http.match(() => true).forEach(request => request.flush([]));
    await fixture.whenStable();
    expect(card('Second letter')).toBe(second);
    expect(card('First letter')).toBeUndefined();
    fixture.componentInstance.controls.update(c => ({ ...c, done: true }));
    await fixture.whenStable();
    expect(card('First letter')?.textContent).toContain('Undo Done');
    expect(card('Second letter')).toBe(second);
    http.verify();
  });

  it('blocks Done while a school correction is pending and sends the displayed revision after review', () => {
    const { fixture, http } = setup();
    const cases = TestBed.inject(Cases);
    cases.cases.update(list => list.map(c => ({ ...c, schoolUpdatePending: true, revision: 'new-revision' })));
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('button.op') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.title).toContain('school changed');
    cases.cases.update(list => list.map(c => ({ ...c, schoolUpdatePending: false })));
    fixture.detectChanges();
    button.click();
    const request = http.expectOne('/api/cases/one/complete');
    expect(request.request.body).toEqual({ revision: 'new-revision' });
    request.flush({ detail: 'School information changed' }, { status: 409, statusText: 'Conflict' });
    expect(cases.error()).toBe('School information changed');
  });

  it('collapses and reopens sections without remounting cards or sharing collapse state across children', async () => {
    const { fixture, cards } = setup();
    const original = cards();
    const page = fixture.componentInstance;
    page.toggleSection('together', 'undated');
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('#inbox-section-together-undated').hidden).toBe(true);
    expect(fixture.nativeElement.querySelector('.section-toggle').getAttribute('aria-expanded')).toBe('false');
    expect(page.isCollapsed('jamie', 'undated')).toBe(false);
    expect(cards()).toEqual(original);
    page.toggleSection('together', 'undated');
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('#inbox-section-together-undated').hidden).toBe(false);
    expect(cards()).toEqual(original);
  });

  it('minimum importance can be selected and cleared while preserving other visibility choices', async () => {
    const { fixture } = setup();
    const page = fixture.componentInstance;
    page.controls.update(c => ({ ...c, info: false, done: true }));
    page.setMinimumScore(4);
    await fixture.whenStable();
    expect(page.shown()).toHaveLength(0);
    expect(page.controls()).toEqual({ info: false, done: true, archived: false, minScore: 4 });
    page.setMinimumScore(4);
    await fixture.whenStable();
    expect(page.shown()).toHaveLength(2);
    expect(page.controls().minScore).toBe(0);
  });

  it('keeps loaded cards on a refresh error, but clears them when switching children', async () => {
    const { fixture, http, cards } = setup();
    const original = cards();
    const store = TestBed.inject(Cases);
    store.refresh();
    http.expectOne('/api/cases').flush({}, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(cards()).toEqual(original);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
    store.setKid('other-child');
    await fixture.whenStable();
    expect(cards()).toHaveLength(0);
    http.expectOne('/api/cases?kid=other-child').flush([]);
    http.verify();
  });

  it('lets a menu handle Escape before it closes the letter', () => {
    const { fixture } = setup();
    const page = fixture.componentInstance;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    page.paneOpen.set(true);
    const handled = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true });
    handled.preventDefault(); // e.g. the letter's More menu closed itself
    page.onEscape(handled);
    expect(navigate).not.toHaveBeenCalled();

    expect(navigate).not.toHaveBeenCalled();
    page.onEscape(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(navigate).toHaveBeenCalledWith('/');
  });
});
