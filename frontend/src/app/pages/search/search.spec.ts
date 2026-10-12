import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { SearchPage } from './search';
import { KidsStore } from '../../core/kids.store';

describe('SearchPage', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ imports: [SearchPage], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  });

  it('cancels obsolete requests and updates signal-based results', async () => {
    const fixture = TestBed.createComponent(SearchPage);
    fixture.componentRef.setInput('q', 'copy');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const old = http.expectOne('/api/search?q=copy');
    fixture.componentRef.setInput('q', 'copy fees');
    fixture.detectChanges();
    expect(old.cancelled).toBe(true);
    http.expectOne('/api/search?q=copy%20fees').flush([{ caseId: 'one', title: 'Copying fee', status: 'ACTION_REQUIRED', match: 'Pay the copying fee', score: 3 }]);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Pay the copying fee');
    expect(fixture.nativeElement.textContent).not.toContain('Searching…');
  });

  it('shows a retryable error rather than pretending a failed search has no matches', () => {
    const fixture = TestBed.createComponent(SearchPage);
    fixture.componentRef.setInput('q', 'swimming');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/search?q=swimming').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Search could not finish');
    expect(fixture.nativeElement.textContent).not.toContain('Nothing matched');
    fixture.componentInstance.retry();
    fixture.detectChanges();
    http.expectOne('/api/search?q=swimming').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nothing matched');
  });

  it('skips empty queries and refreshes immediately when the selected child changes', () => {
    const fixture = TestBed.createComponent(SearchPage);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('q', '');
    fixture.detectChanges();
    http.expectNone(request => request.url === '/api/search');
    fixture.componentRef.setInput('q', 'lunch');
    fixture.detectChanges();
    const old = http.expectOne('/api/search?q=lunch');
    TestBed.inject(KidsStore).select('child-two');
    fixture.detectChanges();
    expect(old.cancelled).toBe(true);
    http.expectOne('/api/search?q=lunch&kid=child-two').flush([]);
    fixture.detectChanges();
    expect(fixture.componentInstance.loading()).toBe(false);
  });
  it('has its own search box (phones have none in the header) that searches on Enter', () => {
    const fixture = TestBed.createComponent(SearchPage);
    fixture.detectChanges();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const box = fixture.nativeElement.querySelector('.m-search-box input') as HTMLInputElement;
    expect(box).toBeTruthy();
    box.value = 'swim cap ';
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(navigate).toHaveBeenCalledWith(['/search'], { queryParams: { q: 'swim cap' }, replaceUrl: true });
  });
});
