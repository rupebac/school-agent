import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Auth } from './auth';

describe('Auth (session)', () => {
  let auth: Auth;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
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
});
