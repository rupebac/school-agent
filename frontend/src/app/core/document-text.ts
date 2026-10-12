import { Component, computed, input } from '@angular/core';

/** Text-only rendering: source/model output never becomes executable markup. */
@Component({
  selector: 'app-document-text',
  template: `@for (block of blocks(); track $index) {
    @if (block.kind === 'heading') {
      <h3>{{ block.text }}</h3>
    } @else if (block.kind === 'list') {
      <ul>
        @for (line of block.lines; track $index) {
          <li>{{ line }}</li>
        }
      </ul>
    } @else {
      <p>{{ block.text }}</p>
    }
  }`,
  styles: [
    `
      :host {
        display: block;
        max-width: 68ch;
        font-size: 1rem;
        line-height: 1.8;
        overflow-wrap: anywhere;
      }
      p {
        margin: 0 0 1.1em;
        white-space: pre-line;
      }
      h3 {
        font-size: 1.08rem;
        margin: 1.6em 0 0.6em;
      }
      ul {
        padding-left: 1.4em;
        margin: 0 0 1.1em;
      }
      li {
        margin: 0.35em 0;
      }
    `,
  ],
})
export class DocumentText {
  readonly text = input('');
  readonly blocks = computed(() =>
    this.text()
      .replace(/\r\n/g, '\n')
      .split(/\n\s*\n/)
      .filter((x) => x.trim())
      .map((raw) => {
        const lines = raw.trim().split('\n');
        if (lines.every((l) => /^\s*(?:[-*•]|\d+[.)])\s/.test(l)))
          return {
            kind: 'list',
            text: '',
            lines: lines.map((l) => l.replace(/^\s*(?:[-*•]|\d+[.)])\s+/, '')),
          };
        if (/^#{1,4}\s/.test(raw) || (lines.length === 1 && raw.length < 90 && /:$/.test(raw)))
          return {
            kind: 'heading',
            text: raw.replace(/^#{1,4}\s+/, '').replace(/\*\*/g, ''),
            lines: [],
          };
        return {
          kind: 'paragraph',
          text: raw.replace(/([^\n])\n(?=[a-zà-ÿ])/g, '$1 ').replace(/\*\*/g, ''),
          lines: [],
        };
      }),
  );
}
