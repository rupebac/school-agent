import { isActionClosed, isActionOverdue, isActionPending } from './overview.store';

describe('action visibility', () => {
  it('hides overdue items from the default pending list', () => {
    const overdue = { status: 'OPEN', dueAt: '2000-01-01T00:00:00Z' };
    const current = { status: 'OPEN', dueAt: '2099-01-01T00:00:00Z' };
    const done = { status: 'COMPLETED', dueAt: '2000-01-01T00:00:00Z' };

    expect(isActionOverdue(overdue)).toBe(true);
    expect(isActionPending(overdue)).toBe(false);
    expect(isActionPending(current)).toBe(true);
    expect(isActionClosed(done)).toBe(true);
    expect(isActionOverdue(done)).toBe(false);
  });
});
