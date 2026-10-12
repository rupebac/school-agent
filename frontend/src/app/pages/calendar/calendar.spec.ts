import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { CalendarPage } from './calendar';
import { CaseSummary } from '../../core/cases.store';
import { NavHistory } from '../../core/nav-history';
import { layoutCell, shortLabel } from './calendar.model';

function setup(query: Record<string, string>) {
  TestBed.configureTestingModule({
    imports: [CalendarPage],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } },
    ],
  });
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(CalendarPage);
  fixture.detectChanges();
  return { page: fixture.componentInstance, navigate };
}

describe('CalendarPage', () => {
  beforeEach(() => localStorage.clear());

  it('restores the month and day from the URL, so Back returns to the same view', () => {
    const { page } = setup({ month: '2026-10', day: '2026-10-07' });
    expect(page.cursor().getMonth()).toBe(9);
    expect(page.selected()).toBe('2026-10-07');
    expect(page.monthLabel()).toContain('October');
  });

  it('a WhatsApp plan link selects its child after the authorized child list loads', () => {
    const { page } = setup({ kid: 'taylor', month: '2026-10', day: '2026-10-05' });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/kids').flush([
      { id: 'jamie', name: 'Jamie', school: null, className: null, color: '#4776aa', assignedAccounts: [] },
      { id: 'taylor', name: 'Taylor', school: null, className: null, color: '#8a5ba0', assignedAccounts: [] },
    ]);
    expect(page.kids.activeKidId()).toBe('taylor');
    expect(localStorage.getItem('schoolagent.activeKid')).toBe('taylor');
    expect(page.selected()).toBe('2026-10-05');
  });

  it('the Exams filter works independently and consistently in the grid, selected day and coming up', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-11T12:00:00Z'));
    try {
      const { page } = setup({ month: '2026-10', day: '2026-10-20' });
      page.kids.kids.set([{ id: 'kid', name: 'Jamie', school: null, className: '7C', color: '#4776aa', assignedAccounts: [] }]);
      const summary = (id: string, title: string): CaseSummary => ({ id, title, childId: 'kid', childName: 'Jamie', childColor: '#4776aa',
        summary: '', category: 'EVENT', status: 'INFORMATION_ONLY', importance: 'IMMEDIATE', score: 4, informationOnly: true,
        investigationComplete: true, openedAt: null, closedAt: null, updatedAt: null, openActions: 0 });
      page.cases.cases.set([summary('exam', 'Math exam'), summary('trip', 'Class trip')]);
      page.events.events.set(['exam', 'trip'].map(id => ({ id, caseId: id, title: id === 'exam' ? 'Math exam' : 'Class trip', caseTitle: null,
        eventType: id === 'exam' ? 'EXAM' : 'SCHOOL_EVENT', startTime: '2026-10-19T22:00:00Z', endTime: null, allDay: true, location: null, status: 'PLANNED' })));
      page.toggle('events');
      expect(page.chips(page.selectedCell()!).map(c => c.label)).toEqual(['Math exam']);
      expect(page.dayAgenda().map(a => a.title)).toEqual(['Math exam']);
      expect(page.comingByKid()[0].items.map(a => a.title)).toEqual(['Math exam']);
      page.toggle('exams');
      expect(page.chips(page.selectedCell()!)).toEqual([]);
      expect(page.dayAgenda()).toEqual([]);
      expect(page.comingByKid()).toEqual([]);
      page.toggle('events');
      expect(page.dayAgenda().map(a => a.title)).toEqual(['Class trip']);
      expect(page.comingByKid()[0].items.map(a => a.title)).toEqual(['Class trip']);
    } finally { vi.useRealTimers(); }
  });

  it('never describes a day outside the month on screen', () => {
    const { page, navigate } = setup({ month: '2026-10', day: '2026-09-29' });
    expect(page.selected().startsWith('2026-10')).toBe(true);
    page.next();
    expect(page.cursor().getMonth()).toBe(10);
    expect(page.selected().startsWith('2026-11')).toBe(true);
    expect(navigate).toHaveBeenLastCalledWith([], expect.objectContaining({
      queryParams: { month: '2026-11', day: page.selected() }, replaceUrl: true,
    }));
  });
});

@Component({ template: '' })
class Blank {}

describe('NavHistory', () => {
  it('returns from a letter to the page it was opened from', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'calendar', component: Blank }, { path: 'cases/:id', component: Blank }, { path: '', component: Blank }])],
    });
    const nav = TestBed.inject(NavHistory);
    const router = TestBed.inject(Router);
    expect(nav.returnUrl()).toBe('/');
    await router.navigateByUrl('/calendar?month=2026-10&day=2026-10-07');
    await router.navigateByUrl('/cases/a');
    await router.navigateByUrl('/cases/b');
    expect(nav.returnUrl()).toBe('/calendar?month=2026-10&day=2026-10-07');
    expect(nav.returnLabel()).toBe('nav.calendarPage');
  });
});

describe('calendar cell layout', () => {
  const entry = (id: string, score: number) => ({ id, score, hot: score >= 4 });

  it('keeps an urgent entry visible on a crowded day, however late it was added', () => {
    const view = layoutCell([entry('a', 2), entry('b', 1), entry('c', 3), entry('urgent', 5)]);
    expect(view.shown.map(e => e.id)).toEqual(['urgent', 'c', 'a']);
    expect(view.more).toBe(1);
    expect(view.labels.map(e => e.id)).toEqual(['urgent']);
    expect(view.dots.map(e => e.id)).toEqual(['c', 'a', 'b']);
    expect(view.phoneMore).toBe(0);
  });

  it('always leaves a visible +N when a phone cell cannot show everything', () => {
    const view = layoutCell([entry('a', 5), entry('b', 4), entry('c', 4), entry('d', 2), entry('e', 2), entry('f', 1), entry('g', 1)]);
    expect(view.labels).toHaveLength(2);
    expect(view.dots).toHaveLength(3);
    expect(view.labels.length + view.dots.length + view.phoneMore).toBe(7);
    expect(view.phoneMore).toBe(2);
  });

  it('makes short labels from existing text without cutting meaning off', () => {
    expect(shortLabel('Early pickup on Friday')).toBe('Early pickup');
    expect(shortLabel('Schullandheim — registration and deposit')).toBe('Schullandheim');
    expect(shortLabel('Wandertag to Andechs — sign slip and pay bus fare')).toBe('Wandertag');
    expect(shortLabel('Class photo day')).toBe('Class photo');
    expect(shortLabel('Bring an empty jam jar for lantern crafts')).toBe('Bring empty');
    expect(shortLabel('Reminder: Elternabend 7b')).toBe('Elternabend');
    expect(shortLabel('Fwd: Re: Early pickup')).toBe('Early pickup');
    expect(shortLabel('The new timetable')).toBe('timetable');
    expect(shortLabel('Re: Swimming lessons')).toBe('Swimming');
    expect(shortLabel('Lunch fees for October are overdue')).toBe('Lunch fees');
    expect(shortLabel('')).toBe('');
  });
});
