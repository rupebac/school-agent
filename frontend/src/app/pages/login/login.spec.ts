import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { describe, it, expect, vi } from 'vitest';
import { Auth } from '../../core/auth';
import { I18n } from '../../core/i18n';
import { LoginPage } from './login';

describe('Login flow', () => {
  function setup(target = '/') {
    const auth = { login: vi.fn().mockReturnValue(of({})) };
    const router = { navigateByUrl: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: I18n, useValue: { t: (key: string) => key } }] });
    const page = TestBed.runInInjectionContext(() => new LoginPage(auth as unknown as Auth,
      router as unknown as Router, { snapshot: { queryParamMap: convertToParamMap({ returnUrl: target }) } } as ActivatedRoute));
    page.user = 'example'; page.password = 'synthetic-password';
    return { page, auth, router };
  }

  it('restores a calendar deep link after authentication', () => {
    const { page, router } = setup('/calendar?day=2026-11-16');
    page.submit();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/calendar?day=2026-11-16');
  });

  it('rejects external return URLs', () => {
    const { page, router } = setup('//example.org');
    page.submit();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('distinguishes unavailable authentication from rejected credentials', () => {
    const { page, auth } = setup();
    for (const status of [401, 503, 0]) {
      auth.login.mockReturnValue(throwError(() => ({ status })));
      page.submit();
      expect(page.error()).toBe(status === 401 ? 'login.failed' : 'login.unavailable');
      expect(page.busy()).toBe(false);
    }
  });

  it('does not issue a second login while the first is pending', () => {
    const { page, auth } = setup();
    auth.login.mockReturnValue(new Subject());
    page.submit(); page.submit();
    expect(auth.login).toHaveBeenCalledTimes(1);
  });
});
