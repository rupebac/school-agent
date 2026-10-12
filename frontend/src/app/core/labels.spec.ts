import { consequenceKey, humanLabel, isFyi, isHotScore, isMust, scoreOf, showStatusTag, sortActions, sortByScore } from './labels';

describe('humanLabel', () => {
  it('turns enum codes into readable English', () => {
    expect(humanLabel('ACTION_REQUIRED')).toBe('Needs you');
    expect(humanLabel('ELTERNPORTAL')).toBe('Portal');
    expect(humanLabel('IMMEDIATE')).toBe('Notify now');
  });
});

describe('consequenceKey', () => {
  it('maps internal statuses to parent-facing tags', () => {
    expect(consequenceKey('ACTION_REQUIRED')).toBe('tag.needsYou');
    expect(consequenceKey('INFORMATION_ONLY')).toBe('tag.noted');
    expect(consequenceKey('INGESTING')).toBe('tag.reading');
  });
});

describe('scoreOf', () => {
  it('hides zero and junk, keeps 1–5', () => {
    expect(scoreOf(0)).toBeNull();
    expect(scoreOf(undefined)).toBeNull();
    expect(scoreOf(1)).toBe(1);
    expect(scoreOf(4.6)).toBe(5);
    expect(scoreOf(9)).toBe(5);
  });

  it('treats 4–5 as the bang range', () => {
    expect(isHotScore(3)).toBe(false);
    expect(isHotScore(4)).toBe(true);
    expect(isHotScore(5)).toBe(true);
  });
});

describe('showStatusTag', () => {
  it('labels information as well as action', () => {
    expect(showStatusTag('INFORMATION_ONLY')).toBe(true);
    expect(showStatusTag('INGESTING')).toBe(false);
    expect(showStatusTag('ACTION_REQUIRED')).toBe(true);
    expect(showStatusTag('COMPLETED')).toBe(true);
  });
});

describe('sortByScore', () => {
  it('puts the highest score first', () => {
    const sorted = sortByScore([{ score: 1 }, { score: 5 }, { score: 0 }]);
    expect(sorted.map((x) => x.score)).toEqual([5, 1, 0]);
  });
});

describe('sortActions', () => {
  it('puts must-do and high scores first', () => {
    const sorted = sortActions([
      { required: false, score: 5, dueAt: null },
      { required: true, score: 2, dueAt: null },
      { required: true, score: 5, dueAt: null },
    ]);
    expect(sorted[0]).toEqual({ required: true, score: 5, dueAt: null });
    expect(sorted[1]).toEqual({ required: true, score: 2, dueAt: null });
    expect(isMust(sorted[2].required)).toBe(false);
  });
});

describe('isFyi', () => {
  it('is info when nothing required of us', () => {
    expect(isFyi({ status: 'INFORMATION_ONLY', action: null })).toBe(true);
    expect(isFyi({ status: 'INFORMATION_ONLY', action: { required: false } })).toBe(true);
    expect(isFyi({ status: 'ACTION_REQUIRED', action: { required: true } })).toBe(false);
    expect(isFyi({ status: 'ACTION_REQUIRED', action: { required: false } })).toBe(true);
    expect(isFyi({ status: 'NEEDS_REVIEW', action: null })).toBe(false);
  });
});
