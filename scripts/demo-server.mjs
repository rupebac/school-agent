#!/usr/bin/env node
/** Read-only screenshot/demo server. Uses fictional fixtures only; no backend, vault or external API. */
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../frontend/dist/frontend/browser/', import.meta.url));
const port = Number(process.env.SCHOOLAGENT_DEMO_PORT || 4317);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('SCHOOLAGENT_DEMO_PORT must be a port number between 1 and 65535.');
  process.exit(1);
}
const at = (day, time = '10:00') => `2026-10-${String(day).padStart(2, '0')}T${time}:00+02:00`;
const kids = [
  { id: 'demo-jamie', name: 'Jamie', school: 'Example Secondary School', className: '7B', color: '#457fa0', assignedAccounts: ['morgan', 'casey'] },
  { id: 'demo-taylor', name: 'Taylor', school: 'Example Primary School', className: '2A', color: '#b9714d', assignedAccounts: ['morgan', 'casey'] },
];
const users = [{ id: 'demo-parent-one', name: 'morgan', displayName: 'Morgan' }, { id: 'demo-parent-two', name: 'casey', displayName: 'Casey' }];
const me = { ...users[0], role: 'ADMIN', language: 'en', whatsappNumber: null, briefingEnabled: true, briefingTime: '19:00', weeklyEnabled: true, weeklyTime: '10:00', instantEnabled: true };
const cases = [], actions = [], events = [], requirements = [];
function matter(id, kid, title, summary, score, options = {}) {
  const k = kids[kid];
  const informationOnly = !!options.info;
  const item = {
    id: `demo-${id}`, childId: k?.id ?? null, childName: k?.name ?? null, childColor: k?.color ?? null,
    title, summary, category: options.category || 'OTHER', status: informationOnly ? 'INFORMATION_ONLY' : 'ACTION_REQUIRED',
    informationOnly, read: false, score, importance: score >= 4 ? 'IMMEDIATE' : 'DIGEST', openActions: options.action ? 1 : 0,
    investigationComplete: true, openedAt: at(9), closedAt: null, updatedAt: at(12), sourceDate: at(9), sourceDateLabel: null,
    sourceTypes: [options.source || (kid === 1 ? 'SCHULMANAGER_LETTER' : 'PORTAL_LETTER')], revision: 'demo-revision', canUndo: false,
    inboxCopy: { title, description: summary, actions: {} },
  };
  cases.push(item);
  if (options.action) {
    const a = { id: `${item.id}-action`, caseId: item.id, caseTitle: title, title: options.action,
      description: null, dueAt: options.due ? at(options.due, '23:59') : null, status: 'OPEN', required: options.required !== false,
      assignedTo: null, assignedToName: null, externalUrl: null, externalLink: null, createdAt: at(9), acknowledgedBy: [] };
    actions.push(a); item.inboxCopy.actions[a.id] = a.title;
  }
  if (options.event) events.push({ id: `${item.id}-event`, caseId: item.id, caseTitle: title, title: options.eventTitle || title,
    eventType: options.exam ? 'EXAM' : 'OTHER', startTime: at(options.event, options.time || '08:30'), endTime: null,
    allDay: !!options.exam, location: options.location || null, status: 'ACTIVE' });
  if (options.bring) requirements.push({ id: `${item.id}-need`, caseId: item.id, caseTitle: title,
    description: options.bring, relevantOn: `2026-10-${options.event}`, status: 'OPEN', required: true });
  return item;
}
matter('contact-details', 0, 'Update emergency contact details', 'The school needs a current contact number and an alternative person to call.', 4,
  { action: 'Return the contact form', due: 12 });
matter('field-trip', 0, 'A day at the science museum', 'A hands-on visit to explore energy, inventions and the natural world.', 5,
  { action: 'Sign and return the consent form', due: 15, event: 20, eventTitle: 'Science museum', bring: 'Packed lunch, water and a light jacket', location: 'Example Science Museum', category: 'TRIP' });
matter('parents-evening', 0, 'Meet the class teachers', 'Hear how the class is settling in and discuss learning plans for the term.', 4,
  { info: true, event: 14, time: '18:30', eventTitle: 'Parents’ evening', location: 'Example School Hall' });
matter('math-exam', 0, 'Mathematics exam', 'A scheduled assessment covering the recent classroom work.', 4,
  { info: true, event: 16, exam: true, source: 'ELTERNPORTAL_RECORD', category: 'EXAM' });
matter('library', 0, 'More time to borrow a good book', 'The library now opens on Wednesday afternoons. Children may borrow two books.', 2, { info: true });
matter('swimming', 1, 'Swimming lessons begin', 'The class starts a six-week swimming course with the school instructors.', 4,
  { action: 'Pay the course contribution of €12', due: 14, event: 16, eventTitle: 'Swimming', bring: 'Swimwear, a towel and goggles', category: 'PAYMENT' });
matter('reading', 1, 'A new story for the class', 'The children will read a short adventure together and keep a reading journal.', 3,
  { action: 'Buy or borrow the class reading book', due: 16, event: 19, eventTitle: 'Reading project', bring: 'The reading book and a pencil' });
matter('class-photo', 1, 'Class photo day', 'Individual portraits and a class photograph will be taken during the morning.', 2,
  { info: true, event: 21, eventTitle: 'Class photos' });
matter('family-forum', null, 'Parent forum: helping children learn', 'An optional evening discussion about practical ways to support learning at home.', 3,
  { action: 'Register if you would like to attend', due: 19, required: false, event: 22, time: '18:00', source: 'EMAIL' });
const shared = cases.find(c => !c.childId);
shared.status = 'COMPLETED'; shared.closedAt = at(10); shared.openActions = 0; shared.canUndo = true;
actions.filter(a => a.caseId === shared.id).forEach(a => { a.status = 'COMPLETED'; });
const holidays = [{ id: 'demo-holiday', childId: null, name: 'Autumn break', startDate: '2026-10-26', endDate: '2026-10-30', entryType: 'HOLIDAY' }];
function detail(item) {
  return { ...item, confidence: 0.96, muted: false, investigationNotes: 'Fictional school letter, verified for the local demo.',
    sources: [{ id: `${item.id}-source`, sourceType: item.sourceTypes[0], externalId: 'demo-letter', title: item.title,
      sender: null, receivedAt: item.sourceDate, originalSentAt: item.sourceDate, canonicalUrl: null, mimeType: 'application/pdf', fetchStatus: 'FETCHED' }],
    actions: actions.filter(a => a.caseId === item.id), requirements: requirements.filter(a => a.caseId === item.id),
    events: events.filter(a => a.caseId === item.id), evidence: [], externalForms: [], investigations: [], changes: [], statusHistory: [],
  };
}
const translation = `# A day at the science museum\n\nDear parents and guardians,\n\nOur class will visit the Example Science Museum on Tuesday, 20 October. Children will explore hands-on exhibits about energy, inventions and the natural world with their class teachers.\n\n## Consent and preparation\n\nPlease sign the consent form and return it by Thursday, 15 October. There is no additional admission charge.\n\n## On the day\n\nWe leave school at 8:30 and return before the usual end of the school day. Your child should bring a packed lunch, a bottle of water and a light jacket.\n\nKind regards,\nThe class teaching team\n\nFictional letter written for this demo.`;
function api(url) {
  const p = url.pathname, kid = url.searchParams.get('kid');
  if (p === '/api/auth/me') return me;
  if (p === '/api/kids' || p === '/api/admin/kids') return kids;
  if (p === '/api/users' || p === '/api/admin/users') return users;
  if (p === '/api/cases') return cases.filter(c => !kid || !c.childId || c.childId === kid);
  if (p === '/api/actions') return actions;
  if (p === '/api/events') return events;
  if (p === '/api/requirements') return requirements;
  if (p === '/api/school-calendar') return holidays;
  if (p === '/api/health/connectors') return ['ELTERNPORTAL', 'SCHULMANAGER', 'GMAIL', 'CALENDAR', 'WHATSAPP', 'AI'].map(connector =>
    ({ connector, status: 'OK', lastSuccessAt: at(13, '11:59'), lastFailureAt: null, lastError: null, consecutiveFailures: 0 }));
  if (p === '/api/inbox/settings') return { archiveAfterWeeks: 4 };
  if (p === '/api/google/status') return { clientConfigured: true, connected: true, calendarId: 'demo-calendar', pollingEnabled: true,
    mailbox: `${kid === kids[1].id ? 'taylor' : 'jamie'}@example.org`, authorizeUrl: null };
  if (p === '/api/google/calendar-setup') return { connected: true, configuredCalendarId: 'demo-calendar', configuredIsPrimary: false,
    expectedName: 'School calendar', schoolCalendarFound: { id: 'demo-calendar', summary: 'School calendar', timeZone: 'Europe/Berlin' }, calendars: [] };
  if (p === '/api/school-connection/status') return { provider: kid === kids[1].id ? 'SCHULMANAGER' : 'ELTERNPORTAL', configured: true,
    url: 'https://portal.school.example', username: null, password: null, hasPassword: true, lastSync: at(13, '11:59'), studentId: null, institutionId: null,
    sections: ['EXAM', 'FORGOTTEN_HOMEWORK', 'FORGOTTEN_MATERIAL', 'PAYMENT'].map(kind => ({ kind, status: 'AVAILABLE', records: kind === 'EXAM' ? 1 : 0, message: null, checkedAt: at(13), lastSuccessAt: at(13) })) };
  if (p === '/api/whatsapp/status') return { configured: true, apiUrl: 'https://graph.facebook.com/v25.0', phoneNumberId: null, wabaId: null,
    token: null, verifyToken: null, appSecret: null, hasToken: true, hasVerifyToken: true, hasAppSecret: true, frontendUrl: 'https://school.example.org',
    webhookUrl: 'https://school.example.org/api/whatsapp/webhook', lastWebhookAt: at(13), recipients: users.map(u => ({ ...u, number: null })), templates: [], deliveries: [] };
  if (p === '/api/models') return ['TEXT', 'VISION'].map(role => ({ role, provider: 'z.ai', modelName: 'Configured model', temperature: 0.2, enabled: true }));
  if (p === '/api/ai/usage') return { totalCalls: 24, inputTokens: 18000, outputTokens: 4000, byModel: {} };
  if (p === '/api/family-rules' || p === '/api/audit') return [];
  if (p === '/api/search') return cases.filter(c => `${c.title} ${c.summary}`.toLowerCase().includes((url.searchParams.get('q') || '').toLowerCase()));
  const match = p.match(/^\/api\/cases\/([^/]+)(.*)$/);
  if (match) {
    const item = cases.find(c => c.id === match[1]); if (!item) return undefined;
    if (!match[2]) return detail(item);
    if (match[2] === '/summary') return { text: translation, sourceIds: [`${item.id}-source`], partial: false };
    if (match[2].startsWith('/sources/')) return { id: `${item.id}-source`, caseId: item.id, sourceType: item.sourceTypes[0], title: item.title,
      sender: null, receivedAt: item.sourceDate, canonicalUrl: null, mimeType: 'application/pdf', originalText: 'Liebe Eltern,\n\nunsere Klasse besucht das Beispiel-Wissenschaftsmuseum.\n\nEin fiktiver Brief für diese Demo.', translatedText: translation, language: 'en', translated: true };
  }
  return undefined;
}
const clock = `<script>const OriginalDate=Date;window.Date=class extends OriginalDate{constructor(...args){super(...(args.length?args:['2026-10-13T10:00:00Z']));}static now(){return new OriginalDate('2026-10-13T10:00:00Z').getTime();}};</script>`;
const mime = { '.js': 'application/javascript', '.css': 'text/css', '.html': 'text/html', '.png': 'image/png', '.ico': 'image/x-icon', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json' };
try { await readFile(resolve(root, 'index.html')); } catch { console.error('Build the frontend first: cd frontend && npm ci && npm run build'); process.exit(1); }
http.createServer(async (req, res) => {
  let url, name;
  try {
    url = new URL(req.url, 'http://127.0.0.1');
    name = decodeURIComponent(url.pathname).replace(/^\/+/, '');
  } catch { res.writeHead(400); res.end(); return; }
  res.setHeader('Cache-Control', 'no-store');
  if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ detail: 'This fictional demo is read-only.' })); return; }
  if (url.pathname.startsWith('/api/')) {
    const value = api(url); res.writeHead(value === undefined ? 404 : 200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value ?? { detail: 'Not available in the demo' })); return;
  }
  let path = resolve(root, name || 'index.html');
  if (!path.startsWith(root.endsWith(sep) ? root : root + sep)) { res.writeHead(404); res.end(); return; }
  try {
    let bytes;
    try { bytes = await readFile(path); } catch { if (extname(name)) throw new Error(); path = resolve(root, 'index.html'); bytes = await readFile(path); }
    if (extname(path) === '.html') bytes = Buffer.from(bytes.toString().replace('<head>', `<head>${clock}`));
    res.writeHead(200, { 'Content-Type': mime[extname(path)] || 'application/octet-stream' }); res.end(bytes);
  } catch { res.writeHead(404); res.end(); }
}).on('error', () => { console.error('Demo port unavailable; choose another SCHOOLAGENT_DEMO_PORT.'); process.exit(1); }).listen(port, '127.0.0.1', () => console.log(`Fictional, read-only demo: http://127.0.0.1:${port}`));
