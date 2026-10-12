import { AfterViewInit, Component, DestroyRef, ElementRef, effect, inject, input, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { KidsStore } from '../../core/kids.store';
import { HttpClient } from '@angular/common/http';
import { timeout } from 'rxjs';
import { ConsequencePipe, consequenceTone, isHotScore, showStatusTag } from '../../core/labels';
import { ImportanceBars } from '../../core/importance';
import { TranslatePipe } from '../../core/i18n';
import { Icon } from '../../core/icon';

interface SearchHit {
  caseId: string;
  title: string;
  status: string;
  match: string;
  score?: number;
}

@Component({
  imports: [RouterLink, ConsequencePipe, TranslatePipe, ImportanceBars, Icon],
  selector: 'app-search-page',
  styleUrl: './search.css',
  templateUrl: './search.html',
})
export class SearchPage implements AfterViewInit {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly box = viewChild<ElementRef<HTMLInputElement>>('box');
  private typing?: ReturnType<typeof setTimeout>;
  readonly q = input('', { transform: (value: string | null | undefined) => value ?? '' });
  readonly tone = consequenceTone;
  readonly showTag = showStatusTag;
  readonly hot = isHotScore;
  readonly hits = signal<SearchHit[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly retryVersion = signal(0);
  readonly kids = inject(KidsStore);

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.typing));
    effect((cleanup) => {
      this.retryVersion();
      const q = this.q().trim();
      const kid = this.kids.activeKidId();
      this.hits.set([]);
      this.failed.set(false);
      this.loading.set(q.length >= 2);
      if (q.length < 2) return;
      const request = this.http.get<SearchHit[]>('/api/search', { params: { q, ...(kid ? { kid } : {}) } })
        .pipe(timeout(8000)).subscribe({
          next: hits => { this.hits.set(hits); this.loading.set(false); },
          error: () => { this.failed.set(true); this.loading.set(false); },
        });
      cleanup(() => request.unsubscribe());
    });
  }

  retry(): void { this.retryVersion.update(value => value + 1); }

  /** Phones have no search box in the header, so an empty search page starts with the cursor here. */
  ngAfterViewInit(): void {
    const el = this.box()?.nativeElement;
    if (el && !this.q().trim() && el.offsetParent !== null) el.focus();
  }

  /** The page's own box (phones): update the query while typing, without filling Back with keystrokes. */
  queue(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    clearTimeout(this.typing);
    this.typing = setTimeout(() => this.submit(value), 300);
  }

  submit(value: string): void {
    clearTimeout(this.typing);
    void this.router.navigate(['/search'], { queryParams: { q: value.trim() }, replaceUrl: true });
  }
}
