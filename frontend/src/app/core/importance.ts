import { Component, computed, inject, input } from '@angular/core';
import { I18n } from './i18n';
import { scoreOf } from './labels';

/** Five bars for the model's 1–5 importance; colour rises with the level. 0 shows empty bars. */
@Component({
  selector: 'app-importance',
  host: {
    class: 'importance',
    role: 'img',
    '[attr.data-level]': 'level()',
    '[attr.title]': 'label()',
    '[attr.aria-label]': 'label()',
  },
  template: `@for (n of bars; track n) {<i [class.on]="n <= level()"></i>}`,
  styles: `
    :host { display: inline-flex; gap: 2px; flex: none; vertical-align: middle; }
    i { width: 4px; height: 12px; border-radius: 2px; background: var(--line-2, #d6dae1); }
    :host([data-level='5']) i.on { background: var(--danger); }
    :host([data-level='4']) i.on { background: #ea580c; }
    :host([data-level='3']) i.on { background: #d97706; }
    :host([data-level='2']) i.on, :host([data-level='1']) i.on { background: #64748b; }
  `,
})
export class ImportanceBars {
  private readonly i18n = inject(I18n);
  readonly score = input<number | null | undefined>(null);
  readonly bars = [1, 2, 3, 4, 5];
  readonly level = computed(() => scoreOf(this.score()) ?? 0);
  readonly label = computed(() => {
    this.i18n.lang();
    const n = this.level();
    const label = this.i18n.t('importance.label', { n });
    return n ? `${label} · ${this.i18n.t(`score.${n}`)}` : label;
  });
}
