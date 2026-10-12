import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { throwError } from 'rxjs';
import { KidsStore } from './kids.store';
import { Auth } from './auth';
import { authGuard } from './auth.guard';

describe('navigation persistence', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  });
  afterEach(() => { localStorage.clear(); TestBed.inject(HttpTestingController).verify(); });
  it('restores the selected child in a fresh store and rejects a child no longer accessible', () => {
    const first = TestBed.inject(KidsStore); first.select('taylor');
    const restored = new KidsStore(TestBed.inject(HttpClient));
    expect(restored.activeKidId()).toBe('taylor');
    restored.refresh(); TestBed.inject(HttpTestingController).expectOne('/api/kids').flush([{ id: 'jamie', name: 'Jamie' }]);
    expect(restored.activeKidId()).toBeNull(); expect(localStorage.getItem('schoolagent.activeKid')).toBeNull();
  });
  it('preserves the event deep link when the session expires', () => {
    const auth = TestBed.inject(Auth);
    auth.restore = () => throwError(() => new Error('expired'));
    TestBed.runInInjectionContext(() => authGuard(null, { url: '/cases/1234' })).subscribe(result => {
      expect(TestBed.inject(Router).serializeUrl(result as import('@angular/router').UrlTree)).toContain('returnUrl=%2Fcases%2F1234');
    });
  });
});
