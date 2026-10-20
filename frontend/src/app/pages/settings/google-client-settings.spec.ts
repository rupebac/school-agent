import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GoogleClientSettings, GoogleClientStatus } from './google-client-settings';
import { I18n } from '../../core/i18n';
import { ES } from '../../core/i18n.es';

const status: GoogleClientStatus = {
  clientId: 'example.apps.googleusercontent.com', hasSecret: true, configured: true,
  redirectUri: 'https://school.example/api/google/callback', source: 'environment',
};

describe('Google administrator setup', () => {
  let http: HttpTestingController;
  const endpoint = '/api/admin/google/client';
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [GoogleClientSettings], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(I18n).setLang('en');
  });
  afterEach(() => http.verify());

  function loaded() {
    const fixture = TestBed.createComponent(GoogleClientSettings);
    fixture.detectChanges(); http.expectOne(endpoint).flush(status); fixture.detectChanges();
    return fixture;
  }

  it('shows the exact readonly callback and leaves the existing secret blank', () => {
    const fixture = loaded();
    const callback = fixture.nativeElement.querySelector('#google-callback') as HTMLInputElement;
    expect(callback.value).toBe(status.redirectUri);
    expect(callback.readOnly).toBe(true);
    expect(fixture.nativeElement.querySelector('input[type=password]').value).toBe('');
    expect(fixture.nativeElement.textContent).toContain('server’s credentials');
    expect(fixture.nativeElement.textContent).toContain('does not connect a mailbox');
  });

  it('preserves an existing secret and emits a connection status refresh after saving', () => {
    const component = loaded().componentInstance;
    let emitted = false; component.saved.subscribe(() => emitted = true);
    expect(component.canSave()).toBe(true); component.save();
    const request = http.expectOne(endpoint);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.clientSecret).toBeNull();
    request.flush({ ...status, source: 'settings' });
    expect(emitted).toBe(true); expect(component.busy()).toBe(false);
  });

  it('requires a new secret and explicit confirmation when replacing the client', () => {
    const component = loaded().componentInstance;
    component.clientId = 'replacement.apps.googleusercontent.com';
    expect(component.canSave()).toBe(false);
    component.clientSecret = 'synthetic-secret';
    expect(component.canSave()).toBe(false);
    component.confirmClientChange = true;
    expect(component.canSave()).toBe(true); component.save();
    const request = http.expectOne(endpoint);
    expect(request.request.body.confirmClientChange).toBe(true);
    request.flush({ ...status, clientId: component.clientId, source: 'settings' });
    expect(component.clientSecret).toBe(''); expect(component.confirmClientChange).toBe(false);
  });

  it('clears submitted secrets on failure and never displays raw backend errors', () => {
    const component = loaded().componentInstance;
    component.clientSecret = 'synthetic-secret'; component.save();
    http.expectOne(endpoint).flush({ detail: 'synthetic-secret' }, { status: 500, statusText: 'Failure' });
    expect(component.clientSecret).toBe('');
    expect(component.error()).not.toContain('synthetic-secret');
    expect(component.busy()).toBe(false);
  });

  it('allows retry after a failed load and renders Spanish instructions', () => {
    TestBed.inject(I18n).applyPack('es', ES);
    const fixture = TestBed.createComponent(GoogleClientSettings);
    fixture.detectChanges();
    http.expectOne(endpoint).flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Reintentar');
    fixture.nativeElement.querySelector('button').click();
    http.expectOne(endpoint).flush({ ...status, hasSecret: false, configured: false, clientId: '', source: 'none' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Guardar estas credenciales no conecta');
    expect(fixture.componentInstance.canSave()).toBe(false);
  });
});
