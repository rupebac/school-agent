import { Injectable, signal, TemplateRef } from '@angular/core';

/**
 * Lets a page put its own controls into the shell's top bar (next to the title and
 * search), so desktop has a single header row. The template keeps its page's context.
 */
@Injectable({ providedIn: 'root' })
export class ShellBar {
  readonly template = signal<TemplateRef<unknown> | null>(null);
}
