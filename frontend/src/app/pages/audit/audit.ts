import { Component, inject, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuditStore } from '../../core/overview.store';
import { LabelPipe } from '../../core/labels';
import { LocDatePipe, TranslatePipe } from '../../core/i18n';

@Component({
  imports: [RouterLink, LabelPipe, TranslatePipe, LocDatePipe],
  selector: 'app-audit-page',
  styleUrl: './audit.css',
  templateUrl: './audit.html',
})
export class AuditPage implements OnInit {
  readonly store = inject(AuditStore);

  ngOnInit(): void {
    this.store.refresh();
  }
}
