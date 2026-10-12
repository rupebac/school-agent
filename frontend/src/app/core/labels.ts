import { Pipe, PipeTransform, inject } from '@angular/core';
import { EN, I18n } from './i18n';

export function humanLabel(value: string | null | undefined): string {
  if (!value) return '—';
  const key = `label.${value}`;
  return EN[key] ?? value.replaceAll('_', ' ').toLowerCase();
}

export function consequenceKey(status: string | null | undefined): string {
  switch (status) {
    case 'ACTION_REQUIRED':
    case 'WAITING_FOR_PARENT':
      return 'tag.needsYou';
    case 'NEEDS_REVIEW':
      return 'tag.checkThis';
    case 'INFORMATION_ONLY':
      return 'tag.noted';
    case 'COMPLETED':
      return 'tag.done';
    case 'CANCELLED':
      return 'tag.cancelled';
    case 'FAILED':
      return 'tag.failed';
    default:
      return 'tag.reading';
  }
}

export function isImportant(importance: string | null | undefined): boolean {
  return importance === 'IMMEDIATE';
}

export function isMust(required: boolean | null | undefined): boolean {
  return required !== false;
}

/** No must-do for us — newsletter, calendar note, optional outing. */
export function isFyi(item: { status: string; action: { required?: boolean } | null }): boolean {
  if (item.status === 'NEEDS_REVIEW' || item.status === 'FAILED') return false;
  if (item.status === 'COMPLETED' || item.status === 'CANCELLED') return false;
  if (item.action && isMust(item.action.required)) return false;
  return true;
}

/** Hide 0; 1–5 are shown as a coloured score. */
export function scoreOf(n: number | null | undefined): number | null {
  if (n == null || !Number.isFinite(n) || n < 1) return null;
  return Math.min(5, Math.round(n));
}

export function isHotScore(n: number | null | undefined): boolean {
  const s = scoreOf(n);
  return s != null && s >= 4;
}

/** Action needed, check this, info, failed, done, cancelled. */
export function showStatusTag(status: string | null | undefined): boolean {
  return (
    status === 'ACTION_REQUIRED' ||
    status === 'WAITING_FOR_PARENT' ||
    status === 'INFORMATION_ONLY' ||
    status === 'NEEDS_REVIEW' ||
    status === 'FAILED' ||
    status === 'COMPLETED' ||
    status === 'CANCELLED'
  );
}

export function sortByScore<T extends { score?: number | null }>(list: T[]): T[] {
  return [...list].sort((a, b) => (scoreOf(b.score) ?? 0) - (scoreOf(a.score) ?? 0));
}

export function sortActions<T extends { required?: boolean; score?: number | null; dueAt?: string | null }>(list: T[]): T[] {
  return [...list].sort((a, b) => {
    const must = Number(isMust(b.required)) - Number(isMust(a.required));
    if (must) return must;
    const score = (scoreOf(b.score) ?? 0) - (scoreOf(a.score) ?? 0);
    if (score) return score;
    const da = a.dueAt ? new Date(a.dueAt).getTime() : Number.POSITIVE_INFINITY;
    const db = b.dueAt ? new Date(b.dueAt).getTime() : Number.POSITIVE_INFINITY;
    return da - db;
  });
}

export function consequenceTone(status: string | null | undefined): 'needs' | 'check' | 'noted' | 'done' | '' {
  switch (status) {
    case 'ACTION_REQUIRED':
    case 'WAITING_FOR_PARENT':
    case 'FAILED':
      return 'needs';
    case 'NEEDS_REVIEW':
      return 'check';
    case 'INFORMATION_ONLY':
      return 'noted';
    case 'COMPLETED':
      return 'done';
    default:
      return '';
  }
}

@Pipe({ name: 'label', pure: false })
export class LabelPipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(value: string | null | undefined): string {
    this.i18n.lang();
    if (!value) return this.i18n.t('common.dash');
    const translated = this.i18n.t(`label.${value}`);
    if (translated !== `label.${value}`) return translated;
    return value.replaceAll('_', ' ').toLowerCase();
  }
}

@Pipe({ name: 'consequence', pure: false })
export class ConsequencePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(status: string | null | undefined): string {
    this.i18n.lang();
    return this.i18n.t(consequenceKey(status));
  }
}
