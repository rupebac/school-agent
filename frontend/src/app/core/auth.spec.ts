import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Auth } from './auth';
import { I18n } from './i18n';

describe('Auth (session)', () => {
  let auth: Auth;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: I18n, useValue: { setLang: vi.fn() } }],
    });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('starts anonymous', () => {
    expect(auth.me()).toBeNull();
    expect(auth.isAdmin).toBeFalsy();
  });

  it('restore() picks up an existing session', () => {
    auth.restore().subscribe();
    const req = http.expectOne('/api/auth/me');
    req.flush({ name: 'morgan', displayName: 'Morgan', role: 'ADMIN', whatsappNumber: null, language: 'en' });
    expect(auth.me()?.name).toBe('morgan');
    expect(auth.isAdmin).toBeTruthy();
  });

  it('login posts credentials with rememberMe and loads me', () => {
    auth.login('morgan', 'pw', true).subscribe();
    const login = http.expectOne('/api/auth/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.body).toEqual({ username: 'morgan', password: 'pw', rememberMe: true });
    login.flush('', { status: 200, statusText: 'OK' });
    const me = http.expectOne('/api/auth/me');
    me.flush({ name: 'morgan', displayName: 'Morgan', role: 'ADMIN', whatsappNumber: null, language: 'es' });
    expect(auth.me()?.name).toBe('morgan');
  });

  it('does not report login success before the account has loaded', () => {
    let completed = false;
    auth.login('morgan', 'pw', false).subscribe(() => { completed = true; });
    http.expectOne('/api/auth/login').flush('');
    expect(completed).toBe(false);
    http.expectOne('/api/auth/me').flush({ name: 'morgan', displayName: 'Morgan', role: 'ADMIN', language: 'en' });
    expect(completed).toBe(true);
    expect(auth.isAdmin).toBe(true);
  });

  it('reports account loading errors to the login subscriber', () => {
    let failed = false;
    auth.login('morgan', 'pw', false).subscribe({ error: () => { failed = true; } });
    http.expectOne('/api/auth/login').flush('');
    http.expectOne('/api/auth/me').flush({}, { status: 500, statusText: 'Failed' });
    expect(failed).toBe(true);
    expect(auth.me()).toBeNull();
  });

});
