import { EN, I18n } from './i18n';
import { ES } from './i18n.es';

describe('I18n', () => {
  it('stays English until a Spanish pack is applied', () => {
    const i18n = new I18n();
    i18n.setLang('en');
    expect(i18n.t('nav.home')).toBe('Home');
    expect(i18n.t('dash.nOpen', { n: 3 })).toBe('3 open');
    i18n.setLang('es');
    expect(i18n.lang()).toBe('es');
    expect(i18n.t('nav.home')).toBe('Home');
    expect(i18n.locale()).toBe('en-GB');
  });

  it('uses a Spanish pack only after it is applied', () => {
    const i18n = new I18n();
    i18n.applyPack('es', ES);
    expect(i18n.t('nav.home')).toBe('Inicio');
    expect(i18n.locale()).toBe('es');
    expect(i18n.t('label.ACTION_REQUIRED')).toBe('Necesita acción');
    expect(i18n.t('dash.nOpen', { n: 3 })).toBe('3 abiertas');
  });

  it('never lets Spanish define unknown keys', () => {
    for (const key of Object.keys(ES)) {
      expect(EN[key], key).toBeDefined();
    }
  });
});
