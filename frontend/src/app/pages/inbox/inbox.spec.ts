import { Cases, CaseSummary } from '../../core/cases.store';
import { ParentAction, SchoolEventDto, otherViewers } from '../../core/overview.store';
import {
  buildInbox, filterInbox, pickComingEvent, pickOpenAction,
  DEFAULT_INBOX_CONTROLS, INBOX_CONTROLS_KEY, readInboxControls, writeInboxControls, resolveInboxControls,
  INBOX_SECTIONS_KEY, readCollapsedSections, writeCollapsedSections,
  daysUntil, groupInbox, groupOf, splitByChild, INBOX_LAYOUT_KEY, readInboxLayout, writeInboxLayout,
} from './inbox.model';
import { isFyi } from '../../core/labels';
import { monthCells, collapseDayAgenda, cellAgenda } from './inbox-cal';

const trip: CaseSummary = {
  id: 'c1',
  childId: 'k1',
  childName: 'Jamie',
  childColor: null,
  title: 'School trip',
  summary: 'Register by Monday.',
  category: 'TRIP',
  status: 'ACTION_REQUIRED',
  importance: 'IMMEDIATE',
  score: 5,
  investigationComplete: true,
  openedAt: '2026-09-01T00:00:00Z',
  closedAt: null,
  updatedAt: null,
  openActions: 1,
};

const newsletter: CaseSummary = {
  ...trip,
  id: 'c2',
  title: 'Weekly note',
  summary: 'Photos are online.',
  status: 'INFORMATION_ONLY',
  importance: 'SILENT',
  score: 1,
  openActions: 0,
};

const concert: CaseSummary = {
  ...trip,
  id: 'c3',
  title: 'Autumn concert',
  summary: 'Aula, 18:00.',
  status: 'INFORMATION_ONLY',
  importance: 'DIGEST',
  score: 2,
  openActions: 0,
};

const action: ParentAction = {
  id: 'a1',
  caseId: 'c1',
  caseTitle: 'School trip',
  title: 'Confirm participation',
  description: null,
  dueAt: '2099-09-29T00:00:00Z',
  assignedTo: null,
  assignedToName: null,
  status: 'OPEN',
  externalUrl: null,
  required: true,
  score: 5,
};

const event: SchoolEventDto = {
  id: 'e1',
  caseId: 'c3',
  caseTitle: 'Autumn concert',
  title: 'Concert',
  eventType: 'SCHOOL_EVENT',
  startTime: '2099-10-12T16:00:00Z',
  endTime: null,
  allDay: false,
  location: 'Aula',
  status: 'PLANNED',
};

describe('inbox', () => {
  it('Info toggles information without hiding actionable matters', () => {
    const items = buildInbox([trip, newsletter, concert], [action], [event]);
    expect(filterInbox(items).map(i => i.caseId)).toEqual(['c1', 'c2', 'c3']);
    expect(filterInbox(items, { ...DEFAULT_INBOX_CONTROLS, info: false }).map(i => i.caseId)).toEqual(['c1']);
  });

  it('marks FYI rows that have no must-do', () => {
    const items = buildInbox([trip, newsletter, concert], [action], [event]);
    expect(items.find((i) => i.caseId === 'c1')).toEqual(expect.objectContaining({ optional: false }));
    expect(isFyi(items.find((i) => i.caseId === 'c1')!)).toBe(false);
    expect(isFyi(items.find((i) => i.caseId === 'c2')!)).toBe(true);
    expect(isFyi(items.find((i) => i.caseId === 'c3')!)).toBe(true);
  });

  it('picks the must-do action over an optional one', () => {
    const optional: ParentAction = { ...action, id: 'a2', required: false, title: 'Bring cake' };
    expect(pickOpenAction([optional, action])?.id).toBe('a1');
  });

  it('ignores cancelled events', () => {
    const cancelled = { ...event, status: 'CANCELLED' };
    expect(pickComingEvent([cancelled], Date.now())).toBeNull();
  });

  it('hides completed cases by default and includes them with Done', () => {
    const closed = { ...concert, status: 'COMPLETED' as const, openActions: 0 };
    const items = buildInbox([closed], [], [event]);
    expect(filterInbox(items)).toEqual([]);
    expect(filterInbox(items, { ...DEFAULT_INBOX_CONTROLS, done: true })).toHaveLength(1);
  });

  it('hides your own name from the viewed tag', () => {
    expect(
      otherViewers(
        [
          { name: 'morgan', displayName: 'Morgan' },
          { name: 'casey', displayName: 'Casey' },
        ],
        'morgan',
      ),
    ).toBe('Casey');
    expect(otherViewers([{ name: 'morgan', displayName: 'Morgan' }], 'morgan')).toBeNull();
  });


});

describe('month grid', () => {
  it('marks weekends and paints a vacation across its dates', () => {
    const cells = monthCells(new Date(2026, 9, 1), [], [
      {
        id: 'h1',
        childId: null,
        name: 'Herbstferien',
        startDate: '2026-10-31',
        endDate: '2026-11-06',
        entryType: 'VACATION',
      },
    ]);
    const sat = cells.find((c) => c.key === '2026-10-03');
    const herbst = cells.find((c) => c.key === '2026-10-31');
    expect(sat?.weekend).toBe(true);
    expect(sat?.offName).toBeNull();
    expect(herbst?.offName).toBe('Herbstferien');
    expect(herbst?.offType).toBe('VACATION');
  });

  it('puts events on the Berlin day', () => {
    const concertDay: SchoolEventDto = { ...event, startTime: '2026-10-12T16:00:00Z' };
    const cells = monthCells(new Date(2026, 9, 1), [concertDay], [], [
      { id: 'a9', caseId: 'c1', title: 'Atlas order', day: '2026-10-12' },
    ]);
    const day = cells.find((c) => c.key === '2026-10-12');
    expect(day?.events.map((e) => e.id)).toEqual(['e1']);
    expect(day?.actions.map((a) => a.title)).toEqual(['Atlas order']);
  });

  it('shows one mark per case even when the model paraphrased the same matter', () => {
    const deadline: SchoolEventDto = { ...event, id: 'e2', caseId: 'c9', title: 'Deadline for Weltatlas', startTime: '2026-09-22T22:00:00Z' };
    const payment: SchoolEventDto = { ...event, id: 'e3', caseId: 'c9', title: 'Payment for Weltatlas', startTime: '2026-09-22T22:00:00Z', eventType: 'PAYMENT' };
    const cells = monthCells(
      new Date(2026, 8, 1),
      [deadline, payment],
      [],
      [
        { id: 'a1', caseId: 'c9', title: 'Answer Weltatlas survey', day: '2026-09-23' },
        { id: 'a2', caseId: 'c9', title: 'Answer the Weltatlas survey (35.50 EUR)', day: '2026-09-23' },
      ],
    );
    const day = cells.find((c) => c.key === '2026-09-23');
    expect(
      cellAgenda(day!, { events: true }).map((i) => i.kind),
    ).toEqual(['event']);
    expect(
      collapseDayAgenda([
        { kind: 'event', id: 'e2', caseId: 'c9', title: 'Deadline for Weltatlas' },
        { kind: 'event', id: 'e3', caseId: 'c9', title: 'Payment for Weltatlas' },
        { kind: 'action', id: 'a1', caseId: 'c9', title: 'Answer Weltatlas survey' },
        { kind: 'action', id: 'a2', caseId: 'c9', title: 'Answer the Weltatlas survey (35.50 EUR)' },
      ]),
    ).toEqual([{ kind: 'action', id: 'a1', caseId: 'c9', title: 'Answer Weltatlas survey' }]);
  });

  it('uses one dot when an event and a to-do belong to the same case', () => {
    const firstDay: SchoolEventDto = {
      ...event,
      id: 'e15',
      caseId: 'c-letter',
      title: 'First day of school 5th grade',
      startTime: '2026-09-14T22:00:00Z',
    };
    const cells = monthCells(new Date(2026, 8, 1), [firstDay], [], [
      { id: 'a15', caseId: 'c-letter', title: 'Bring child to first school day', day: '2026-09-15' },
    ]);
    const day = cells.find((c) => c.key === '2026-09-15');
    expect(cellAgenda(day!, { events: true })).toEqual([
      { kind: 'event', id: 'a15', caseId: 'c-letter', title: 'Bring child to first school day' },
    ]);
  });

  it('skips cancelled events', () => {
    const cancelled: SchoolEventDto = { ...event, status: 'CANCELLED', startTime: '2026-10-12T16:00:00Z' };
    const cells = monthCells(new Date(2026, 9, 1), [cancelled]);
    expect(cells.find((c) => c.key === '2026-10-12')?.events).toEqual([]);
  });
});

describe('family inbox redesign', () => {
  it('independent history toggles include Done and Archived alongside filtered active items exactly once', () => {
    const expired = { ...newsletter, id: 'expired', read: true, relevance: { expiresAt: '2026-09-01T00:00:00Z', archived: true, assessment: 'UNKNOWN' as const, confidence: null, reason: 'Unknown' } };
    const done = { ...trip, id: 'closed', status: 'COMPLETED' };
    const items = buildInbox([trip, newsletter, done, expired], [action], []);
    const ids = (history: { done: boolean; archived: boolean }) => filterInbox(items, { ...DEFAULT_INBOX_CONTROLS, ...history }).map(i => i.caseId).sort();
    expect(ids({ done: false, archived: false })).toEqual(['c1', 'c2']);
    expect(ids({ done: true, archived: false })).toEqual(['c1', 'c2', 'closed']);
    expect(ids({ done: false, archived: true })).toEqual(['c1', 'c2', 'expired']);
    expect(ids({ done: true, archived: true })).toEqual(['c1', 'c2', 'closed', 'expired']);
    expect(filterInbox(items, { ...DEFAULT_INBOX_CONTROLS, info: false, done: true, archived: true }).map(i => i.caseId).sort()).toEqual(['c1', 'closed']);
  });
  it('remembers toggles and importance, honors explicit URL flags, and migrates history links', () => {
    localStorage.clear();
    expect(readInboxControls()).toEqual(DEFAULT_INBOX_CONTROLS);
    const controls = { info: false, done: true, archived: true, minScore: 4 };
    writeInboxControls(controls);
    expect(readInboxControls()).toEqual(controls);
    expect(resolveInboxControls({ info: null, done: null, archived: null, minScore: null })).toEqual(controls);
    expect(resolveInboxControls({ info: '1', done: '0', archived: '0', minScore: '0' })).toEqual(DEFAULT_INBOX_CONTROLS);
    expect(resolveInboxControls({ info: null, done: null, archived: null, minScore: '99' }).minScore).toBe(0);
    localStorage.clear();
    localStorage.setItem('schoolagent.inboxHistory', '{"done":true,"archived":true}');
    expect(readInboxControls()).toEqual({ ...DEFAULT_INBOX_CONTROLS, done: true, archived: true });
    localStorage.clear();
    expect(resolveInboxControls({ info: null, done: null, archived: null, minScore: null, view: 'archived' }).archived).toBe(true);
    localStorage.setItem(INBOX_CONTROLS_KEY, 'bad json');
    expect(readInboxControls()).toEqual(DEFAULT_INBOX_CONTROLS);
    localStorage.clear();
  });
  it('minimum importance applies to both active and historical content with missing scores kept only when unfiltered', () => {
    const rows = buildInbox([
      { ...trip, id: 'five', score: 5 }, { ...trip, id: 'four', score: 4 },
      { ...trip, id: 'three', score: 3 }, { ...trip, id: 'unset', score: 0 },
      { ...trip, id: 'done', score: 2, status: 'COMPLETED' },
      { ...newsletter, id: 'info', score: 5, informationOnly: true },
    ], [], []);
    expect(filterInbox(rows, { ...DEFAULT_INBOX_CONTROLS, minScore: 4 }).map(i => i.caseId)).toEqual(['five', 'four', 'info']);
    expect(filterInbox(rows, { ...DEFAULT_INBOX_CONTROLS, done: true, info: false, minScore: 4 }).map(i => i.caseId)).toEqual(['five', 'four']);
    expect(filterInbox(rows, { ...DEFAULT_INBOX_CONTROLS, done: true, minScore: 3 }).map(i => i.caseId)).not.toContain('done');
    expect(filterInbox(rows, { ...DEFAULT_INBOX_CONTROLS, done: true })).toHaveLength(6);
  });
  it('remembers independent collapsed sections and recovers from invalid storage', () => {
    localStorage.removeItem(INBOX_SECTIONS_KEY);
    writeCollapsedSections(new Set(['inbox-section-jamie-soon', 'inbox-section-taylor-info']));
    expect([...readCollapsedSections()]).toEqual(['inbox-section-jamie-soon', 'inbox-section-taylor-info']);
    localStorage.setItem(INBOX_SECTIONS_KEY, '{}');
    expect(readCollapsedSections().size).toBe(0);
    localStorage.removeItem(INBOX_SECTIONS_KEY);
  });
  it('Info correction hides stale action data and can still have a calendar event', () => {
    const info = { ...trip, informationOnly: true, read: false };
    const items = buildInbox([info], [action], [{ ...event, caseId: trip.id }]);
    expect(items[0].action).toBeNull();
    expect(filterInbox(items)).toHaveLength(1);
  });
  it('personal read appears only in Done while retaining its event data', () => {
    const info = { ...newsletter, informationOnly: true, read: true };
    const items = buildInbox([info], [], [{ ...event, caseId: info.id }]);
    expect(filterInbox(items, { ...DEFAULT_INBOX_CONTROLS, done: true })).toHaveLength(1);
    expect(filterInbox(items)).toHaveLength(0);
    expect(items[0].event).not.toBeNull();
  });
  it('expired information and high importance undated actions are only visible in Archived', () => {
    const now = Date.parse('2026-10-03T12:00:00Z');
    const relevance = { expiresAt: '2026-10-02T22:00:00Z', archived: true, assessment: 'UNKNOWN' as const, confidence: 0.2, reason: 'No period stated.' };
    const expired = { ...newsletter, informationOnly: true, relevance };
    const undated = { ...trip, id: 'undated', relevance };
    expect(Cases.needsAction(undated)).toBe(false);
    expect(Cases.isActive(undated)).toBe(false);
    const items = buildInbox([expired, undated], [{ ...action, caseId: 'undated', dueAt: null }], [], now);
    expect(filterInbox(items)).toEqual([]);
    expect(filterInbox(items, { ...DEFAULT_INBOX_CONTROLS, done: true })).toEqual([]);
    expect(filterInbox(items, { ...DEFAULT_INBOX_CONTROLS, archived: true })).toHaveLength(2);
    expect(groupInbox(items, now).map(s => s.group)).toEqual(['archived']);
    expect(items[1].action?.status).toBe('OPEN');
  });
  it('expiry boundary uses the saved instant even if the last server flag was not archived', () => {
    const relevance = { expiresAt: '2026-10-25T23:00:00Z', archived: false, assessment: 'ESTIMATED' as const, confidence: 0.95, reason: 'Ends 25 October.' };
    const info = { ...newsletter, relevance };
    expect(buildInbox([info], [], [], Date.parse('2026-10-25T22:59:59Z'))[0].archived).toBe(false);
    expect(buildInbox([info], [], [], Date.parse('2026-10-25T23:00:00Z'))[0].archived).toBe(true);
  });
  it('includes an event on every day of its range', () => {
    const cells = monthCells(new Date(2026, 8, 1), [{ ...event, startTime: '2026-09-10T08:00:00Z', endTime: '2026-09-12T12:00:00Z' }]);
    expect(cells.filter(c => c.events.length).map(c => c.day)).toEqual([10, 11, 12]);
  });
});

describe('child lanes', () => {
  const now = Date.parse('2026-09-29T08:00:00Z');
  const due = (iso: string, extra: Partial<ParentAction> = {}): ParentAction => ({ ...action, id: 'a-' + iso, dueAt: iso, ...extra });

  it('counts Berlin calendar days, not 24-hour blocks', () => {
    expect(daysUntil('2026-09-29T21:30:00Z', now)).toBe(0);
    expect(daysUntil('2026-09-29T22:30:00Z', now)).toBe(1);
    expect(daysUntil('2026-09-25T10:00:00Z', now)).toBe(-4);
  });

  it('sorts rows into overdue, this week, later, undated, info and done sections', () => {
    const cases: CaseSummary[] = [
      { ...trip, id: 'late' }, { ...trip, id: 'soon' }, { ...trip, id: 'edge' }, { ...trip, id: 'later' },
      { ...trip, id: 'review', status: 'NEEDS_REVIEW', openActions: 0 },
      { ...newsletter, id: 'info', informationOnly: true },
      { ...newsletter, id: 'read', informationOnly: true, read: true },
      { ...trip, id: 'closed', status: 'COMPLETED', openActions: 0 },
    ];
    const actions = [
      due('2026-09-25T10:00:00Z', { caseId: 'late' }),
      due('2026-10-01T10:00:00Z', { caseId: 'soon' }),
      due('2026-10-06T10:00:00Z', { caseId: 'edge' }),
      due('2026-10-16T10:00:00Z', { caseId: 'later' }),
    ];
    const items = buildInbox(cases, actions, [], now).map((i) => ({ ...i, overdue: i.caseId === 'late' }));
    expect(items.map((i) => groupOf(i, now))).toEqual(['overdue', 'soon', 'soon', 'later', 'undated', 'info', 'done', 'done']);
    expect(groupInbox(items, now).map((s) => [s.group, s.items.map((i) => i.caseId)])).toEqual([
      ['overdue', ['late']], ['soon', ['soon', 'edge']], ['later', ['later']], ['undated', ['review']],
      ['info', ['info']], ['done', ['read', 'closed']],
    ]);
  });

  it('orders a section by date, then by importance', () => {
    const cases = [{ ...trip, id: 'b', score: 2 }, { ...trip, id: 'a', score: 5 }, { ...trip, id: 'c', score: 3 }];
    const actions = [due('2026-10-02T10:00:00Z', { caseId: 'b' }), due('2026-10-02T10:00:00Z', { caseId: 'a' }), due('2026-10-01T10:00:00Z', { caseId: 'c' })];
    const soon = groupInbox(buildInbox(cases, actions, [], now).map((i) => ({ ...i, overdue: false })), now)[0];
    expect(soon.items.map((i) => i.caseId)).toEqual(['c', 'a', 'b']);
  });

  it('gives each child a lane and keeps family rows separate', () => {
    const items = buildInbox([
      { ...trip, id: 'x', childId: 'jamie' },
      { ...trip, id: 'y', childId: 'mia' },
      { ...trip, id: 'z', childId: null },
      { ...trip, id: 'gone', childId: 'removed-child' },
    ], [], [], now);
    const { lanes, shared } = splitByChild(items, ['jamie', 'mia']);
    expect([...lanes.keys()]).toEqual(['jamie', 'mia']);
    expect(lanes.get('jamie')!.map((i) => i.caseId)).toEqual(['x']);
    expect(lanes.get('mia')!.map((i) => i.caseId)).toEqual(['y']);
    expect(shared.map((i) => i.caseId)).toEqual(['z', 'gone']);
  });

  it('remembers By child / Together and otherwise uses the screen default', () => {
    localStorage.removeItem(INBOX_LAYOUT_KEY);
    expect(readInboxLayout()).toBe('lanes');
    expect(readInboxLayout('together')).toBe('together');
    writeInboxLayout('lanes');
    expect(readInboxLayout('together')).toBe('lanes');
    writeInboxLayout('together');
    expect(readInboxLayout()).toBe('together');
    localStorage.removeItem(INBOX_LAYOUT_KEY);
  });
});

describe('persisted inbox copy', () => {
  it('uses the dedicated title and action while allowing no extra description', () => {
    const c = { ...trip, inboxCopy: { title: 'Trip registration', description: null, actions: { a1: 'Confirm participation' } } };
    const row = buildInbox([c], [action], [event])[0];
    expect(row.title).toBe('Trip registration');
    expect(row.summary).toBeNull();
    expect(row.actionLabel).toBe('Confirm participation');
    expect(row.action?.dueAt).toBe(action.dueAt);
    expect(c.summary).toBe('Register by Monday.');
  });
  it('preserves existing text while copy is unavailable, including deadlines only in prose', () => {
    const row = buildInbox([trip], [action], [])[0];
    expect(row.title).toBe(trip.title);
    expect(row.summary).toBe(trip.summary);
    expect(row.actionLabel).toBe(action.title);
  });
});
