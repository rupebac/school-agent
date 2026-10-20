import { GoogleClientSettings } from './google-client-settings';
import { SchoolEmailSettings } from './school-email-settings';
import { InstallApp } from '../../core/pwa';
import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Auth } from '../../core/auth';
import { GoogleSetup } from '../../core/google.store';
import { PortalSetup, PortalStatus, SchoolProvider, SchoolIdentity } from '../../core/portal.store';
import { WhatsappSetup } from '../../core/whatsapp.store';
import { KidsStore } from '../../core/kids.store';
import { SchoolCalendarStore } from '../../core/briefing.store';
import { PreferencesStore } from '../../core/preferences.store';
import { Cases } from '../../core/cases.store';
import { httpErrorMessage } from '../../core/http-error';
import { I18n, LocDatePipe, TranslatePipe } from '../../core/i18n';
import { LabelPipe } from '../../core/labels';

@Component({
  imports: [GoogleClientSettings, SchoolEmailSettings, InstallApp, FormsModule, TranslatePipe, LocDatePipe, LabelPipe],
  selector: 'app-settings-page',
  styleUrl: './settings.css',
  templateUrl: './settings.html',
})
export class SettingsPage implements OnInit {
  readonly google = inject(GoogleSetup);
  readonly portal = inject(PortalSetup);
  readonly whatsapp = inject(WhatsappSetup);
  readonly kidsStore = inject(KidsStore);
  readonly schoolCalendar = inject(SchoolCalendarStore);
  readonly preferences = inject(PreferencesStore);
  readonly auth = inject(Auth);
  readonly i18n = inject(I18n);
  private readonly cases = inject(Cases);
  private readonly router = inject(Router);

  readonly banner = signal<string | null>(null);
  archiveWeeks = 4;
  readonly archiveLoaded = signal(false);
  readonly archiveBusy = signal(false);
  readonly archiveMessage = signal<string | null>(null);
  readonly archiveError = signal<string | null>(null);

  validArchiveWeeks(): boolean { return Number.isInteger(this.archiveWeeks) && this.archiveWeeks >= 1 && this.archiveWeeks <= 52; }

  saveArchive(): void {
    if (!this.validArchiveWeeks() || !this.archiveLoaded() || this.archiveBusy()) return;
    this.archiveBusy.set(true);
    this.archiveMessage.set(null);
    this.archiveError.set(null);
    this.http.put<{ archiveAfterWeeks: number }>('/api/inbox/settings', { archiveAfterWeeks: this.archiveWeeks }).subscribe({
      next: s => {
        this.archiveWeeks = s.archiveAfterWeeks;
        this.archiveBusy.set(false);
        this.archiveMessage.set(this.i18n.t('set.archive.saved'));
        this.cases.refresh();
      },
      error: err => { this.archiveBusy.set(false); this.archiveError.set(httpErrorMessage(err, this.i18n.t('set.saveFailed'))); },
    });
  }
  shareWith = '';
  portalProvider: SchoolProvider = 'ELTERNPORTAL';
  portalIdentity: SchoolIdentity | null = null;
  portalStudentId = '';
  portalUrl = '';
  portalUser = '';
  portalPassword = '';
  oldPassword = '';
  newPassword = '';
  newPasswordRepeat = '';
  readonly pwMessage = signal<string | null>(null);

  readonly aiUsage = signal<{ totalCalls: number; inputTokens: number; outputTokens: number; byModel: Record<string, number> } | null>(null);

  readonly wa = {
    apiUrl: '',
    phoneNumberId: '',
    wabaId: '',
    token: '',
    verifyToken: '',
    appSecret: '',
    frontendUrl: '',
  };
  testTo = '';

  constructor(private http: HttpClient) {}

  readonly adminUsers = signal<any[]>([]);
  readonly accountsMessage = signal<string | null>(null);
  newKid = { name: '', school: '', className: '', color: '#2563eb', accountNames: '' };
  readonly newUser = { name: '', displayName: '', password: '', role: 'PARENT', whatsappNumber: '', language: 'en' };
  readonly area = signal<'school' | 'you' | 'family'>('school');
  readonly panel = signal<'google' | 'calendar' | 'portal' | null>(null);
  readonly addingKid = signal(false);
  readonly addingUser = signal(false);
  myWhatsapp = '';

  setArea(area: 'school' | 'you' | 'family'): void {
    this.area.set(area);
    void this.router.navigate([], { queryParams: { area }, queryParamsHandling: 'merge' });
    if (area === 'school' && !this.settingsKidId()) this.settingsKidId.set(this.kidsStore.kids()[0]?.id ?? null);
    if (area === 'you') this.myWhatsapp = this.auth.me()?.whatsappNumber ?? '';
  }

  togglePanel(panel: 'google' | 'calendar' | 'portal'): void {
    this.panel.set(this.panel() === panel ? null : panel);
  }

  goToSchool(kidId: string): void {
    this.selectKid(kidId);
    this.setArea('school');
  }

  saveMyWhatsapp(): void {
    this.http.post<{ whatsappNumber: string | null }>('/api/account/whatsapp', { whatsappNumber: this.myWhatsapp }).subscribe({
      next: (r) => {
        const me = this.auth.me();
        if (me) this.auth.me.set({ ...me, whatsappNumber: r.whatsappNumber });
        this.pwMessage.set(this.i18n.t('set.you.phoneSaved'));
      },
      error: (err) => this.pwMessage.set(httpErrorMessage(err, this.i18n.t('set.saveFailed'))),
    });
  }

  addKid(): void {
    const accounts = this.newKid.accountNames.split(/[\s,]+/).filter(Boolean);
    this.http.post('/api/admin/kids', { ...this.newKid, accountNames: accounts }).subscribe({
      next: () => {
        this.newKid = { name: '', school: '', className: '', color: '#2563eb', accountNames: '' };
        this.kidsStore.refresh(() => this.loadAdminKids());
        this.addingKid.set(false);
      },
      error: (err) => alert(httpErrorMessage(err, this.i18n.t('set.createFailed'))),
    });
  }

  deleteKid(k: any): void {
    if (!confirm(this.i18n.t('set.kids.confirm', { name: k.name }))) return;
    this.http.delete(`/api/admin/kids/${k.id}`).subscribe({ next: () => { this.kidsStore.refresh(); this.loadAdminKids(); } });
  }

  readonly adminKids = signal<any[]>([]);
  loadAdminKids(): void {
    this.http.get<any[]>('/api/admin/kids').subscribe((k) => this.adminKids.set(k));
  }

  loadUsers(): void {
    if (!this.auth.isAdmin) return;
    this.http.get<any[]>('/api/admin/users').subscribe((u) => this.adminUsers.set(u));
  }

  addUser(): void {
    this.http.post('/api/admin/users', this.newUser).subscribe({
      next: () => {
        this.newUser.name = this.newUser.displayName = this.newUser.password = this.newUser.whatsappNumber = '';
        this.newUser.language = 'en';
        this.addingUser.set(false);
        this.loadUsers();
        this.whatsapp.refresh(this.whatsappKid(), (s) => this.applyWhatsapp(s));
      },
      error: (err) => alert(httpErrorMessage(err, this.i18n.t('set.createFailed'))),
    });
  }

  updateUser(u: any): void {
    this.http.put(`/api/admin/users/${u.id}`, {
      displayName: u.displayName,
      role: u.role,
      whatsappNumber: u.whatsappNumber ?? '',
      language: u.language ?? 'en',
    }).subscribe({
      next: () => {
        this.accountsMessage.set(this.i18n.t('set.people.saved', { name: u.displayName || u.name }));
        const me = this.auth.me();
        if (me && me.name === u.name) {
          this.auth.me.set({ ...me, language: u.language ?? 'en', displayName: u.displayName, whatsappNumber: u.whatsappNumber ?? null });
          this.i18n.setLang(u.language);
        }
        this.whatsapp.refresh(this.whatsappKid(), (s) => this.applyWhatsapp(s));
      },
      error: (err) => this.accountsMessage.set(httpErrorMessage(err, this.i18n.t('set.updateFailed'))),
    });
  }

  deleteUser(u: any): void {
    if (!confirm(this.i18n.t('set.people.confirm', { name: u.name }))) return;
    this.http.delete(`/api/admin/users/${u.id}`).subscribe({ next: () => this.loadUsers() });
  }

  readonly models = signal<{ role: string; provider: string; modelName: string; temperature: number | null; enabled: boolean }[]>([]);
  readonly rules = signal<{ id: string; ruleKey: string; text: string; enabled: boolean }[]>([]);
  newRule = { ruleKey: '', text: '' };
  readonly policyMessage = signal<string | null>(null);

  ngOnInit(): void {
    this.http.get<{ archiveAfterWeeks: number }>('/api/inbox/settings').subscribe({
      next: s => { this.archiveWeeks = s.archiveAfterWeeks; this.archiveLoaded.set(true); },
      error: err => this.archiveError.set(httpErrorMessage(err, this.i18n.t('set.saveFailed'))),
    });
    const params = this.router.getCurrentNavigation()?.finalUrl?.queryParamMap
      ?? this.router.routerState.snapshot.root.queryParamMap;
    if (params.get('google') === 'connected') {
      this.banner.set(this.i18n.t('set.google.ok'));
      this.area.set('school');
      this.panel.set('google');
    }
    const err = params.get('googleError');
    if (err) {
      this.banner.set('⚠️ ' + err);
      this.area.set('school');
    }
    const area = params.get('area');
    if (area === 'you' || area === 'family' || area === 'school') this.area.set(area);
    this.http.get('/api/ai/usage').subscribe((u: any) => this.aiUsage.set(u));
    this.http.get<any[]>('/api/models').subscribe((m) => this.models.set(m));
    this.http.get<any[]>('/api/family-rules').subscribe((r) => this.rules.set(r));
    this.auth.restore().subscribe((me) => {
      this.loadUsers();
      this.loadAdminKids();
      this.myWhatsapp = me?.whatsappNumber ?? '';
      this.preferences.loadFromMe(me);
    });
    if (this.auth.isAdmin) this.whatsapp.refresh(null, s => this.applyWhatsapp(s));
    this.schoolCalendar.refresh();
    this.kidsStore.refresh(() => {
      this.settingsKidId.set(this.kidsStore.kids().find(k => k.id === params.get('kid'))?.id ?? this.kidsStore.activeKidId() ?? this.kidsStore.kids()[0]?.id ?? null);
      this.reloadConnectors();
    });
  }

  readonly settingsKidId = signal<string | null>(null);
  whatsappKid(): null { return null; }
  settingsKid() { return this.kidsStore.kids().find(k => k.id === this.settingsKidId()); }

  selectKid(id: string): void {
    this.settingsKidId.set(id || null);
    this.portalUrl = ''; this.portalUser = ''; this.portalPassword = ''; this.shareWith = '';
    this.portalIdentity = null; this.portalStudentId = ''; this.portalProvider = 'ELTERNPORTAL';
    this.google.busy.set(false); this.portal.busy.set(false);
    this.google.status.set(null);
    this.google.calendar.set(null);
    this.portal.status.set(null);
    this.reloadConnectors();
  }

  private reloadConnectors(): void {
    const id = this.settingsKidId();
    if (!id) return;
    this.google.refresh(id);
    this.portal.refresh(id, (s) => this.applyPortal(s));
  }

  savePortal(): void {
    if (!this.portal.status()) return;
    const student = this.portalIdentity?.students.find(s => s.id === this.portalStudentId);
    this.portal.save(this.settingsKidId(), {
      provider: this.portalProvider, url: this.portalUrl.trim(), username: this.portalUser.trim(),
      password: this.portalPassword || null, studentId: this.portalStudentId || null,
      institutionId: this.portalIdentity?.institutionId ?? null, studentName: student?.name ?? null,
    }, s => this.applyPortal(s));
  }

  changePortalProvider(provider: SchoolProvider): void {
    this.portalProvider = provider;
    this.portalUrl = ''; this.portalUser = ''; this.portalPassword = '';
    this.portalIdentity = null; this.portalStudentId = '';
    this.portal.message.set(null);
    const saved = this.portal.status();
    if (saved?.provider === provider) this.applyPortal(saved);
  }

  portalCredentialsChanged(): void {
    this.portalIdentity = null; this.portalStudentId = '';
  }

  discoverPortal(): void {
    this.portal.discover(this.settingsKidId(), { provider: this.portalProvider, username: this.portalUser.trim(), password: this.portalPassword || null }, identity => {
      this.portalIdentity = identity;
      this.portalStudentId = identity.students.length === 1 ? identity.students[0].id : '';
    });
  }

  canSavePortal(): boolean {
    return !!this.portal.status() && !this.portal.busy() && !!this.portalUser.trim()
      && (this.portalProvider === 'ELTERNPORTAL' ? !!this.portalUrl.trim() : !!this.portalStudentId && !!this.portalIdentity?.institutionId);
  }

  portalMatchesSaved(): boolean {
    const saved = this.portal.status();
    return !!saved?.configured && this.portalProvider === saved.provider && this.portalUser === saved.username
      && (this.portalProvider === 'ELTERNPORTAL' ? this.portalUrl === saved.url && this.portalPassword === saved.password
        : this.portalStudentId === saved.studentId && !this.portalPassword);
  }

  modelLine(): string {
    const m = this.aiUsage()?.byModel ?? {};
    return Object.entries(m).map(([k, v]) => `${k} (${v})`).join(', ') || '—';
  }

  savePreferences(): void {
    const p = this.preferences.prefs();
    if (p) this.preferences.save(p);
  }

  currentYear(): number { return new Date().getFullYear(); }

  importCalendar(year: number): void {
    this.schoolCalendar.importYear(year);
  }

  saveWhatsapp(): void {
    if (!this.whatsapp.status()) return;
    this.whatsapp.save(this.whatsappKid(), {
      apiUrl: this.wa.apiUrl.trim(),
      phoneNumberId: this.wa.phoneNumberId.trim(),
      wabaId: this.wa.wabaId.trim(),
      token: this.wa.token,
      verifyToken: this.wa.verifyToken.trim(),
      appSecret: this.wa.appSecret,
      frontendUrl: this.wa.frontendUrl.trim() || window.location.origin,
    }, (s) => this.applyWhatsapp(s));
  }

  private applyPortal(s: PortalStatus): void {
    this.portalProvider = s.provider ?? 'ELTERNPORTAL';
    this.portalStudentId = s.studentId ?? '';
    this.portalIdentity = s.studentId ? { institutionId: s.institutionId ?? null, schoolName: null, students: [{ id: s.studentId, name: s.studentName ?? 'Connected child' }] } : null;
    this.portalUrl = s.url ?? '';
    this.portalUser = s.username ?? '';
    this.portalPassword = s.password ?? '';
  }

  private applyWhatsapp(s: {
    apiUrl: string;
    phoneNumberId: string | null;
    wabaId?: string | null;
    token?: string | null;
    verifyToken?: string | null;
    appSecret?: string | null;
    frontendUrl: string;
    recipients: { name: string; displayName: string; number: string | null }[];
  }): void {
    this.wa.apiUrl = s.apiUrl;
    this.wa.phoneNumberId = s.phoneNumberId ?? '';
    this.wa.wabaId = s.wabaId ?? '';
    this.wa.token = s.token ?? '';
    this.wa.verifyToken = s.verifyToken ?? '';
    this.wa.appSecret = s.appSecret ?? '';
    this.wa.frontendUrl = s.frontendUrl?.includes('127.0.0.1') || s.frontendUrl?.includes('localhost')
      ? window.location.origin
      : s.frontendUrl;
    const stillValid = s.recipients.some((r) => r.name === this.testTo);
    if (!stillValid) {
      this.testTo = s.recipients.find((r) => r.number)?.name ?? s.recipients[0]?.name ?? '';
    }
  }

  canTest(): boolean {
    return !!this.whatsapp.status()?.recipients.find((r) => r.name === this.testTo)?.number;
  }

  copyWebhook(): void {
    const url = this.whatsapp.status()?.webhookUrl;
    if (!url) return;
    navigator.clipboard.writeText(url).then(() => this.whatsapp.message.set(this.i18n.t('set.wa.copied')));
  }

  changePassword(): void {
    if (this.newPassword !== this.newPasswordRepeat) {
      this.pwMessage.set(this.i18n.t('set.account.pwMismatch'));
      return;
    }
    if (this.newPassword.length < 8) {
      this.pwMessage.set(this.i18n.t('set.account.pwShort'));
      return;
    }
    this.http.post('/api/account/password', {
      oldPassword: this.oldPassword,
      newPassword: this.newPassword,
    }).subscribe({
      next: () => {
        this.pwMessage.set(this.i18n.t('set.account.pwOk'));
        this.oldPassword = this.newPassword = this.newPasswordRepeat = '';
      },
      error: (err) => this.pwMessage.set('⚠️ ' + httpErrorMessage(err, this.i18n.t('set.account.pwFail'))),
    });
  }

  connect(): void {
    const url = this.google.status()?.authorizeUrl;
    if (url) window.location.href = url;
  }

  createCalendar(): void {
    const emails = this.shareWith
      .split(/[,;\s]+/)
      .map((e) => e.trim())
      .filter((e) => e.length > 0);
    this.google.ensureCalendar(this.settingsKidId(), emails);
  }

  saveModel(m: { role: string; modelName: string; enabled: boolean; temperature: number | null }): void {
    this.http.put(`/api/models/${m.role}`, {
      modelName: m.modelName,
      enabled: m.enabled,
      temperature: m.temperature,
    }).subscribe({
      next: () => this.policyMessage.set(this.i18n.t('set.ai.saved')),
      error: (err) => this.policyMessage.set(httpErrorMessage(err, this.i18n.t('set.saveFailed'))),
    });
  }

  addRule(): void {
    const text = this.newRule.text.trim();
    if (!text) return;
    const ruleKey = this.newRule.ruleKey.trim() || text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
    this.http.post('/api/family-rules', { ruleKey, text }).subscribe({
      next: (r: any) => {
        this.rules.update((list) => [r, ...list]);
        this.newRule = { ruleKey: '', text: '' };
        this.policyMessage.set(this.i18n.t('set.rules.added'));
      },
      error: (err) => this.policyMessage.set(httpErrorMessage(err, this.i18n.t('set.addRuleFailed'))),
    });
  }

  toggleRule(r: { id: string; enabled: boolean }): void {
    this.http.put(`/api/family-rules/${r.id}`, { enabled: r.enabled }).subscribe({
      error: (err) => this.policyMessage.set(httpErrorMessage(err, this.i18n.t('set.updateFailed'))),
    });
  }

  deleteRule(r: { id: string; ruleKey: string }): void {
    if (!confirm(this.i18n.t('set.rules.confirm', { key: r.ruleKey }))) return;
    this.http.delete(`/api/family-rules/${r.id}`).subscribe({
      next: () => this.rules.update((list) => list.filter((x) => x.id !== r.id)),
    });
  }

  setLanguage(language: string): void {
    this.i18n.setLang(language);
    this.http.post<{ language: string }>('/api/account/language', { language }).subscribe({
      next: (r) => {
        const me = this.auth.me();
        if (me) this.auth.me.set({ ...me, language: r.language });
        this.i18n.setLang(r.language);
        this.pwMessage.set(this.i18n.t('set.account.langSaved'));
      },
    });
  }

  scrollTo(id: string): void {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
