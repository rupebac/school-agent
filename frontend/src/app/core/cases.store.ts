import { HttpClient } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';

/** Mirrors the backend DTOs in de.schoolagent.api.CaseDtos — single source: docs/FRONTEND.md §5. */

export interface CaseRelevance {
  fallbackWeeks?: number;
  expiresAt: string;
  assessment: 'ESTIMATED' | 'UNKNOWN' | 'PENDING' | 'UNAVAILABLE';
  confidence: number | null;
  reason: string;
  archived: boolean;
}

export interface CaseSummary {
  relevance?: CaseRelevance | null;
  inboxCopy?: { title: string; description: string | null; actions: Record<string, string> } | null;
  informationOnly?: boolean;
  classificationOverridden?: boolean;
  classificationReview?: boolean;
  read?: boolean;
  revision?: string;
  canUndo?: boolean;
  schoolUpdatePending?: boolean;
  sourceTypes?: string[];
  sourceDate?: string | null;
  sourceDateLabel?: string | null;
  latestChange?: string | null;

  id: string;
  childId: string | null;
  childName: string | null;
  childColor: string | null;
  title: string;
  summary: string | null;
  category: string;
  status: string;
  importance: string;
  score: number;
  investigationComplete: boolean;
  openedAt: string | null;
  closedAt: string | null;
  updatedAt: string | null;
  openActions: number;
}

export interface SourceDto {
  forwardedBy?: string | null;
  originalSentAt?: string | null;
  superseded?: boolean;
  schoolVersion?: number | null;
  id: string;
  sourceType: string;
  externalId: string;
  title: string | null;
  sender: string | null;
  receivedAt: string | null;
  canonicalUrl: string | null;
  mimeType: string | null;
  fetchStatus: string;
}

export interface SourceReading {
  notice?: string | null;
  id: string;
  caseId: string;
  sourceType: string;
  title: string | null;
  sender: string | null;
  receivedAt: string | null;
  canonicalUrl: string | null;
  mimeType: string | null;
  originalText: string | null;
  translatedText: string | null;
  language: string;
  translated: boolean;
}

export interface EvidenceDto {
  id: string;
  sourceId: string;
  kind: string;
  subject: string;
  confidence: number | null;
}

export interface ParentActionDto {
  id: string;
  title: string;
  description: string | null;
  dueAt: string | null;
  assignedTo: string | null;
  assignedToName: string | null;
  status: string;
  externalUrl: string | null;
  externalLink?: { url: string; kind: 'FORM' | 'PORTAL'; provider?: string | null } | null;
  required?: boolean;
  createdAt?: string | null;
  acknowledgedBy?: { name: string; displayName: string; at: string }[];
}

export interface ChildRequirementDto {
  id: string;
  description: string;
  relevantOn: string | null;
  status: string;
  required?: boolean;
}

export interface SchoolEventDto {
  id: string;
  title: string;
  eventType: string;
  startTime: string;
  endTime: string | null;
  allDay: boolean;
  location: string | null;
  status: string;
}

export interface ExternalFormDto {
  id: string;
  url: string;
  deadline: string | null;
  requiresConsent: boolean;
  requiresPayment: boolean;
  accessStatus: string;
  externalLink?: { url: string; kind: 'PAGE' } | null;
}

export interface CaseChangeDto {
  id: string;
  changeType: string;
  targetType: string;
  targetId: string | null;
  createdAt: string;
}

export interface StatusEntryDto {
  id: string;
  fromStatus: string | null;
  toStatus: string;
  reason: string | null;
  actor: string;
  createdAt: string;
}

export interface StepDto {
  id: string;
  stepType: string;
  target: string;
  outcome: string;
  createdAt: string;
}

export interface InvestigationDto {
  id: string;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  pagesVisited: number;
  aiCalls: number;
  steps: StepDto[];
}

export interface CaseDetail {
  relevance?: CaseRelevance | null;
  informationOnly?: boolean;
  classificationOverridden?: boolean;
  classificationReview?: boolean;
  read?: boolean;
  revision?: string;
  canUndo?: boolean;
  schoolUpdatePending?: boolean;
  sourceTypes?: string[];
  sourceDate?: string | null;
  sourceDateLabel?: string | null;
  latestChange?: string | null;

  id: string;
  childId: string | null;
  childName: string | null;
  childColor: string | null;
  title: string;
  summary: string | null;
  category: string;
  status: string;
  importance: string;
  score: number;
  confidence: number | null;
  investigationComplete: boolean;
  investigationNotes: string | null;
  openedAt: string | null;
  closedAt: string | null;
  muted?: boolean;
  sources: SourceDto[];
  previousSources?: SourceDto[];
  schoolPayments?: { title: string; status: string; currency: string; totalMinor: number | null; paidMinor: number | null; observedAt: string }[];
  schoolObservations?: { title: string | null; version: number; checkedAt: string; lastSuccessAt: string | null;
    failureCode: string | null; receipt: string; response: string; deadline: string | null; deadlineAllDay?: boolean }[];
  evidence: EvidenceDto[];
  actions: ParentActionDto[];
  requirements: ChildRequirementDto[];
  events: SchoolEventDto[];
  externalForms: ExternalFormDto[];
  changes: CaseChangeDto[];
  statusHistory: StatusEntryDto[];
  investigations: InvestigationDto[];
}

const ACTIVE = ['NEW', 'INGESTING', 'INVESTIGATING', 'READY_FOR_REASONING', 'ACTION_REQUIRED', 'WAITING_FOR_PARENT', 'INFORMATION_ONLY'];
const NEEDS_ACTION = ['ACTION_REQUIRED', 'WAITING_FOR_PARENT'];

@Injectable({ providedIn: 'root' })
export class Cases {
  readonly cases = signal<CaseSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  private request = 0;
  private kidId: string | null = null;

  constructor(private http: HttpClient) {}

  private kidFilter(): string {
    return this.kidId ? `?kid=${this.kidId}` : '';
  }

  setKid(kidId: string | null): void {
    if (this.kidId === kidId) return;
    this.kidId = kidId;
    this.cases.set([]);
    this.refresh();
  }

  refresh(): void {
    const request = ++this.request;
    this.loading.set(true);
    this.error.set(null);
    this.http.get<CaseSummary[]>(`/api/cases${this.kidFilter()}`).subscribe({
      next: (list) => {
        if (request !== this.request) return;
        this.cases.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        if (request !== this.request) return;
        this.error.set(err?.message ?? 'API unreachable');
        this.loading.set(false);
      },
    });
  }

  load(id: string): Observable<CaseDetail> {
    return this.http.get<CaseDetail>(`/api/cases/${id}`);
  }

  readSource(caseId: string, sourceId: string, lang: string): Observable<SourceReading> {
    return this.http.get<SourceReading>(`/api/cases/${caseId}/sources/${sourceId}`, { params: { lang } });
  }

  mutate(id: string, operation: string, body: unknown, after?: () => void): void {
    this.error.set(null);
    this.http.put(`/api/cases/${id}/${operation}`, body).subscribe({
      next: () => { this.refresh(); after?.(); },
      error: (err) => this.error.set(err?.error?.detail ?? 'Could not save. Please refresh and try again.'),
    });
  }

  complete(id: string, after?: () => void, revision?: string): void {
    this.mutate(id, 'complete', { revision: revision ?? this.cases().find(c => c.id === id)?.revision }, after);
  }
  undo(id: string, after?: () => void): void { this.mutate(id, 'undo-complete', {}, after); }
  markRead(id: string, read: boolean, revision?: string, after?: () => void): void {
    this.mutate(id, 'read', { read, revision }, after);
  }
  classify(id: string, informationOnly: boolean, after?: () => void): void {
    this.mutate(id, 'classification', { informationOnly }, after);
  }

  mute(id: string, muted: boolean, after?: () => void): void {
    this.http.put(`/api/cases/${id}/mute`, { muted }).subscribe({
      next: () => {
        this.refresh();
        after?.();
      },
    });
  }

  static isActive(c: CaseSummary): boolean {
    return !c.relevance?.archived && ACTIVE.includes(c.status);
  }

  static needsAction(c: CaseSummary): boolean {
    return !c.relevance?.archived && (NEEDS_ACTION.includes(c.status) || c.openActions > 0);
  }
}
