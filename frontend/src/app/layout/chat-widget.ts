import { Component, effect, ElementRef, inject, signal, viewChild } from '@angular/core';
import { KidsStore } from '../core/kids.store';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { I18n, TranslatePipe } from '../core/i18n';
import { Icon } from '../core/icon';

interface ChatRef {
  id: string;
  title: string;
}

interface ChatLine {
  role: 'user' | 'assistant';
  content: string;
  refs?: ChatRef[];
}

@Component({
  imports: [TranslatePipe, Icon],
  selector: 'app-chat-widget',
  styleUrl: './chat-widget.css',
  templateUrl: './chat-widget.html',
})
export class ChatWidget {
  private readonly http = inject(HttpClient);
  private readonly i18n = inject(I18n);
  private readonly router = inject(Router);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('thread');
  private drag: { x: number; y: number; w: number; h: number } | null = null;

  readonly kids = inject(KidsStore);
  private generation = 0;
  constructor() {
    let lastCase = this.openCaseId();
    this.router.events.pipe(takeUntilDestroyed()).subscribe(event => {
      if (!(event instanceof NavigationEnd)) return;
      const current = this.openCaseId(); if (current === lastCase) return;
      lastCase = current; this.generation++; this.messages.set([]); this.busy.set(false);
    });
    let lastKid = this.kids.activeKidId();
    effect(() => { const kid = this.kids.activeKidId(); if (kid === lastKid) return; lastKid = kid; this.generation++; this.messages.set([]); this.busy.set(false); });
  }
  readonly open = signal(false);
  readonly expanded = signal(false);
  readonly width = signal(420);
  readonly height = signal(600);
  readonly draft = signal('');
  readonly busy = signal(false);
  readonly messages = signal<ChatLine[]>([]);

  toggle(): void {
    if (this.open()) {
      this.generation++;
      this.open.set(false);
      this.expanded.set(false);
      this.messages.set([]);
      this.draft.set('');
      this.busy.set(false);
      return;
    }
    this.open.set(true);
  }

  toggleSize(): void {
    this.expanded.update((v) => !v);
  }

  startResize(event: PointerEvent): void {
    if (this.expanded()) return;
    event.preventDefault();
    (event.target as HTMLElement).setPointerCapture(event.pointerId);
    this.drag = { x: event.clientX, y: event.clientY, w: this.width(), h: this.height() };
  }

  onResize(event: PointerEvent): void {
    if (!this.drag) return;
    const maxW = Math.max(360, window.innerWidth - 100);
    const maxH = Math.max(420, window.innerHeight - 24);
    // The panel is anchored bottom-left, so dragging the top-right corner right/up grows it.
    this.width.set(Math.min(maxW, Math.max(360, this.drag.w + (event.clientX - this.drag.x))));
    this.height.set(Math.min(maxH, Math.max(420, this.drag.h + (this.drag.y - event.clientY))));
  }

  endResize(): void {
    this.drag = null;
  }

  send(): void {
    const message = this.draft().trim();
    if (!message || this.busy()) return;
    const generation = ++this.generation;
    const prior = this.messages();
    this.messages.set([...prior, { role: 'user', content: message }]);
    this.draft.set('');
    this.busy.set(true);
    this.scrollSoon();
    this.http
      .post<{ answer: string; refs?: ChatRef[] }>('/api/chat', {
        message,
        history: prior.map((m) => ({ role: m.role, content: m.content })),
        caseId: this.openCaseId(),
        kid: this.openCaseId() ? null : this.kids.activeKidId(),
      })
      .subscribe({
        next: (r) => {
          if (generation !== this.generation) return;
          this.messages.update((rows) => [
            ...rows,
            { role: 'assistant', content: r.answer, refs: r.refs ?? [] },
          ]);
          this.busy.set(false);
          this.scrollSoon();
        },
        error: () => {
          if (generation !== this.generation) return;
          this.messages.update((rows) => [
            ...rows,
            { role: 'assistant', content: this.i18n.t('chat.error') },
          ]);
          this.busy.set(false);
        },
      });
  }

  onInput(event: Event): void {
    this.draft.set((event.target as HTMLInputElement).value);
  }

  openCase(id: string, event: Event): void {
    event.preventDefault();
    void this.router.navigate(['/cases', id]);
  }

  private openCaseId(): string | null {
    const match = this.router.url.match(/\/cases\/([^/?#]+)/);
    return match?.[1] ?? null;
  }

  private scrollSoon(): void {
    queueMicrotask(() => {
      const el = this.scroller()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
