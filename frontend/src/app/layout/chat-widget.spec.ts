import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ChatWidget } from './chat-widget';

describe('ChatWidget', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ChatWidget],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'cases/:id', component: ChatWidget }]),
      ],
    });
  });

  it('forgets the thread when the panel is closed', () => {
    const fixture = TestBed.createComponent(ChatWidget);
    const w = fixture.componentInstance;
    w.open.set(true);
    w.messages.set([{ role: 'user', content: 'when is Elternabend?' }]);
    w.toggle();
    expect(w.open()).toBe(false);
    expect(w.messages()).toEqual([]);
  });

  it('stays out of the way until opened from the navigation, then offers search hints', () => {
    const fixture = TestBed.createComponent(ChatWidget);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.chat-panel')).toBeNull();
    expect(host.querySelector('button')).toBeNull();
    fixture.componentInstance.toggle();
    fixture.detectChanges();
    expect(host.querySelector('.chat-panel')).toBeTruthy();
    expect(host.textContent).toContain('Search letters and dates');
  });

  it('closes when the backdrop is tapped', () => {
    const fixture = TestBed.createComponent(ChatWidget);
    fixture.componentInstance.toggle();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.chat-scrim') as HTMLElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.open()).toBe(false);
  });

  it('can enlarge the panel', () => {
    const fixture = TestBed.createComponent(ChatWidget);
    const w = fixture.componentInstance;
    w.open.set(true);
    fixture.detectChanges();
    expect(w.expanded()).toBe(false);
    w.toggleSize();
    expect(w.expanded()).toBe(true);
  });

  it('sends the open letter id with the question', async () => {
    const fixture = TestBed.createComponent(ChatWidget);
    await TestBed.inject(Router).navigateByUrl('/cases/00000000-0000-0000-0000-0000000000cc');
    const w = fixture.componentInstance;
    w.open.set(true);
    w.draft.set('what do we need to bring?');
    w.send();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/chat');
    expect(req.request.body.caseId).toBe('00000000-0000-0000-0000-0000000000cc');
    req.flush({ answer: 'Goggles', refs: [] });
  });

  it('opens a letter from a chat chip', async () => {
    const fixture = TestBed.createComponent(ChatWidget);
    const w = fixture.componentInstance;
    w.open.set(true);
    w.messages.set([
      {
        role: 'assistant',
        content: 'Swimming on Tuesday',
        refs: [{ id: '00000000-0000-0000-0000-0000000000cc', title: 'Swimming lessons begin' }],
      },
    ]);
    fixture.detectChanges();
    const link = (fixture.nativeElement as HTMLElement).querySelector('.chat-ref') as HTMLAnchorElement;
    expect(link.textContent).toContain('Swimming lessons begin');
    link.click();
    await fixture.whenStable();
    expect(TestBed.inject(Router).url).toContain('/cases/00000000-0000-0000-0000-0000000000cc');
  });
});
