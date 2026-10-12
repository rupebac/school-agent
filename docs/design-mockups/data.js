// Fake data shared by every mockup. Nothing here comes from the real system.
(function () {
  const TODAY = new Date('2026-09-29T08:15:00');

  const kids = [
    { id: 'jamie', name: 'Jamie', school: 'Example Secondary School', klass: '7b', color: '#e4572e', soft: '#fde8e1' },
    { id: 'mia', name: 'Mia', school: 'Example Primary School', klass: '3a', color: '#1f9e89', soft: '#dcf3ee' },
  ];

  const health = [
    { name: 'Gmail', status: 'ok', note: 'Checked 2 min ago' },
    { name: 'Elternportal', status: 'ok', note: 'Checked 14 min ago' },
    { name: 'Schulmanager', status: 'ok', note: 'Checked 14 min ago' },
    { name: 'Calendar', status: 'ok', note: 'Synced 5 min ago' },
    { name: 'WhatsApp', status: 'ok', note: 'Webhook live' },
    { name: 'Browser worker', status: 'warn', note: 'Slow — last run 3 h ago' },
  ];

  // bucket: now | coming | noted | done
  const items = [
    {
      id: 'lunch', kid: 'mia', score: 5, bucket: 'now', overdue: true,
      title: 'Lunch fees for October are overdue',
      summary: 'The Mensa account is €12.30 short. Top up €46.50 so Mia keeps getting lunch from Thursday.',
      action: { title: 'Top up the Mensa account (€46.50)', due: '2026-09-25', must: true, payment: '€46.50', form: true },
      source: { type: 'Email', sender: 'Mensa Söcking', date: '2026-09-22' },
    },
    {
      id: 'wandertag', kid: 'jamie', score: 4, bucket: 'now',
      title: 'Wandertag to Andechs — sign slip and pay bus fare',
      summary: 'Class 7b hikes to Andechs on Wed 7 Oct. Sign the consent slip and pay €8 bus fare by Friday.',
      action: { title: 'Sign consent slip and pay €8', due: '2026-10-02', must: true, payment: '€8.00' },
      event: { date: '2026-10-07', time: '08:00', end: '15:30', location: 'Bahnhof Starnberg' },
      change: 'Meeting point moved from school to Bahnhof Starnberg',
      source: { type: 'Elternportal', sender: 'Ms Huber', date: '2026-09-28' },
    },
    {
      id: 'swim', kid: 'mia', score: 3, bucket: 'now',
      title: 'Swimming lessons start Thursday',
      summary: 'Mia needs a swimsuit, towel and swim cap every Thursday until the Christmas break.',
      need: ['Swimsuit', 'Towel', 'Swim cap', 'Hairbrush'],
      action: { title: 'Pack the swim bag', due: '2026-10-01', must: true },
      event: { date: '2026-10-01', time: '10:00', end: '11:30', location: 'Hallenbad Starnberg' },
      source: { type: 'Email', sender: 'Frau Weber', date: '2026-09-27' },
    },
    {
      id: 'klassenfahrt', kid: 'jamie', score: 4, bucket: 'now',
      title: 'Klassenfahrt Berchtesgaden — deposit and consent',
      summary: 'Five-day class trip in March. A €120 deposit and the signed consent form are due mid-October.',
      action: { title: 'Pay €120 deposit and return form', due: '2026-10-16', must: true, payment: '€120.00', form: true },
      source: { type: 'Schulmanager', sender: 'Mr Brandl', date: '2026-09-24' },
    },
    {
      id: 'atlas', kid: 'jamie', score: 3, bucket: 'now',
      title: 'Order the Diercke Weltatlas',
      summary: 'The geography atlas is required from November. Order through the school bulk order to pay €24.90 instead of €39.',
      action: { title: 'Order via school form (€24.90)', due: '2026-10-09', must: false, payment: '€24.90', form: true },
      source: { type: 'Elternportal', sender: 'Fachschaft Erdkunde', date: '2026-09-21' },
    },
    {
      id: 'elternabend', kid: 'jamie', score: 3, bucket: 'coming',
      title: 'Elternabend 7b',
      summary: 'Parents’ evening with the class teacher. Topics: Klassenfahrt, tablets, class representatives.',
      event: { date: '2026-10-01', time: '19:30', end: '21:00', location: 'Room 214' },
      change: 'Moved from 18:00 to 19:30',
      source: { type: 'Schulmanager', sender: 'Ms Huber', date: '2026-09-18' },
    },
    {
      id: 'photo', kid: 'mia', score: 2, bucket: 'coming',
      title: 'Class photo day',
      summary: 'Photographer visits 3a. Photos can be ordered online afterwards — no action needed now.',
      event: { date: '2026-10-14', time: '08:30', location: 'Example Primary School' },
      source: { type: 'Photo', sender: 'Letter in Mia’s folder', date: '2026-09-26' },
    },
    {
      id: 'jar', kid: 'mia', score: 2, bucket: 'now',
      title: 'Bring an empty jam jar for lantern crafts',
      summary: 'Class 3a builds St. Martin lanterns. Mia needs one clean jar (about 400 ml) in her bag.',
      action: { title: 'Pack an empty jar', due: '2026-10-21', must: false },
      source: { type: 'Photo', sender: 'Letter in Mia’s folder', date: '2026-09-29' },
    },
    {
      id: 'mathe', kid: 'jamie', score: 3, bucket: 'coming',
      title: 'First maths Schulaufgabe',
      summary: 'Written test on fractions and negative numbers. Practice sheets are on Schulmanager.',
      event: { date: '2026-10-20', time: '08:00', location: 'Room 214' },
      source: { type: 'Schulmanager', sender: 'Mr Brandl', date: '2026-09-23' },
    },
    {
      id: 'holidays', kid: null, score: 1, bucket: 'noted',
      title: 'Autumn holidays 2 – 6 November',
      summary: 'No school for either child. The Hort is open with reduced hours (08:00–14:00).',
      event: { date: '2026-11-02', allDay: true, endDate: '2026-11-06' },
      source: { type: 'Elternportal', sender: 'Sekretariat', date: '2026-09-15' },
    },
    {
      id: 'lice', kid: 'mia', score: 2, bucket: 'noted', unread: true,
      title: 'Head lice reported in class 3a',
      summary: 'Please check Mia’s hair this week. Only report back if you find something.',
      source: { type: 'Email', sender: 'Frau Weber', date: '2026-09-28' },
    },
    {
      id: 'sicknote', kid: 'jamie', score: 1, bucket: 'noted', unread: true,
      title: 'New rule for sick notes from October',
      summary: 'Absences are now reported through Schulmanager before 07:45 — phone calls are no longer accepted.',
      source: { type: 'Email', sender: 'Sekretariat', date: '2026-09-25' },
    },
    {
      id: 'ldg', kid: 'mia', score: 2, bucket: 'done', doneAt: '2026-09-24',
      title: 'Book Lernentwicklungsgespräch',
      summary: 'Booked for Tue 13 Oct, 15:20 with Frau Weber.',
      source: { type: 'Elternportal', sender: 'Frau Weber', date: '2026-09-16' },
    },
    {
      id: 'library', kid: 'jamie', score: 1, bucket: 'done', doneAt: '2026-09-22',
      title: 'Return library books',
      summary: 'Two overdue books returned.',
      source: { type: 'Email', sender: 'Schulbibliothek', date: '2026-09-17' },
    },
  ];

  // Full detail for the case the mockups open by default.
  const detail = {
    id: 'wandertag',
    kid: 'jamie',
    status: 'Needs us',
    score: 4,
    title: 'Wandertag to Andechs — sign slip and pay bus fare',
    summary:
      'Class 7b goes on a hiking day to Andechs Monastery on Wednesday 7 October. The school needs the signed consent slip and €8 for the bus by Friday 2 October. The meeting point changed to Bahnhof Starnberg in a follow-up email.',
    opened: '2026-09-28',
    actions: [
      { title: 'Sign and return the consent slip', due: '2026-10-02', must: true, done: false, who: 'Morgan' },
      { title: 'Pay €8 bus fare (cash in envelope)', due: '2026-10-02', must: true, done: false, who: null },
      { title: 'Tell Ms Huber about the nut allergy', due: '2026-10-05', must: false, done: true, who: 'Taylor' },
    ],
    needs: ['Packed lunch and 1 L water', 'Rain jacket', 'Sturdy shoes', 'Bus ticket money (€8)'],
    events: [
      { title: 'Wandertag Andechs', date: '2026-10-07', time: '08:00', end: '15:30', location: 'Bahnhof Starnberg', status: 'MOVED', note: 'was: school yard' },
    ],
    sources: [
      { type: 'Elternportal', title: 'Elternbrief Wandertag 7b.pdf', sender: 'Ms Huber', date: '2026-09-28', pages: 2, lang: 'German' },
      { type: 'Email', title: 'Re: Wandertag — neuer Treffpunkt', sender: 'huber@school.example', date: '2026-09-29', lang: 'German' },
    ],
    changes: [
      { at: '2026-09-29T07:42', text: 'Meeting point changed from the school yard to Bahnhof Starnberg.', from: 'Email from Ms Huber' },
      { at: '2026-09-28T16:10', text: 'Deadline found: slip and money by Fri 2 Oct.', from: 'Elternbrief PDF, page 1' },
    ],
    history: [
      { at: '2026-09-29T07:43', who: 'Agent', text: 'Updated after new email' },
      { at: '2026-09-28T19:02', who: 'Taylor', text: 'Completed “Tell Ms Huber about the nut allergy”' },
      { at: '2026-09-28T16:11', who: 'Agent', text: 'Opened as Needs us · WhatsApp sent' },
    ],
    excerpt:
      'Liebe Eltern der Klasse 7b, am Mittwoch, 7. Oktober, unternehmen wir unseren Wandertag zum Kloster Andechs. Bitte geben Sie den unterschriebenen Abschnitt sowie 8 € Buskosten bis Freitag, 2. Oktober, bei der Klassenleitung ab …',
    translation:
      'Dear parents of class 7b, on Wednesday 7 October we are going on our hiking day to Andechs Monastery. Please hand in the signed slip and €8 for the bus by Friday 2 October to the class teacher …',
  };

  // School calendar marks for the month grid (September/October 2026).
  const offDays = { '2026-10-03': 'Tag der Deutschen Einheit', '2026-11-02': 'Herbstferien' };

  const M = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const WD = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  const d = (iso) => new Date(iso.length === 10 ? iso + 'T12:00:00' : iso);
  const dayDiff = (iso) => {
    const a = new Date(TODAY); a.setHours(0, 0, 0, 0);
    const b = d(iso); b.setHours(0, 0, 0, 0);
    return Math.round((b - a) / 86400000);
  };

  const fmt = {
    short: (iso) => `${d(iso).getDate()} ${M[d(iso).getMonth()]}`,
    wdShort: (iso) => `${WD[d(iso).getDay()]} ${d(iso).getDate()} ${M[d(iso).getMonth()]}`,
    wd: (iso) => WD[d(iso).getDay()],
    day: (iso) => d(iso).getDate(),
    mon: (iso) => M[d(iso).getMonth()],
    month: (date) => `${MONTHS[date.getMonth()]} ${date.getFullYear()}`,
    time: (iso) => d(iso).toTimeString().slice(0, 5),
    rel(iso) {
      const n = dayDiff(iso);
      if (n === 0) return 'Today';
      if (n === 1) return 'Tomorrow';
      if (n === -1) return 'Yesterday';
      if (n < 0) return `${-n} days late`;
      if (n < 7) return WD[d(iso).getDay()];
      return fmt.short(iso);
    },
    ago(iso) {
      const n = -dayDiff(iso);
      if (n <= 0) return 'Today';
      if (n === 1) return 'Yesterday';
      return `${n} days ago`;
    },
  };

  const kid = (id) => kids.find((k) => k.id === id) ?? { id: 'family', name: 'Family', color: '#6b7280', soft: '#eef0f3', klass: '' };
  const dateOf = (it) => it.action?.due ?? it.event?.date ?? null;
  const counts = {
    now: items.filter((i) => i.bucket === 'now').length,
    coming: items.filter((i) => i.bucket === 'coming' || (i.bucket === 'now' && i.event)).length,
    noted: items.filter((i) => i.bucket === 'noted').length,
    done: items.filter((i) => i.bucket === 'done').length,
    overdue: items.filter((i) => i.overdue).length,
  };

  // Month grid (Monday first) for a given year/month.
  function monthCells(year, month) {
    const first = new Date(year, month, 1, 12);
    const lead = (first.getDay() + 6) % 7;
    const days = new Date(year, month + 1, 0).getDate();
    const cells = [];
    for (let i = 0; i < lead; i++) cells.push(null);
    for (let n = 1; n <= days; n++) {
      const iso = `${year}-${String(month + 1).padStart(2, '0')}-${String(n).padStart(2, '0')}`;
      const date = new Date(year, month, n, 12);
      cells.push({
        iso, n,
        today: dayDiff(iso) === 0,
        weekend: date.getDay() === 0 || date.getDay() === 6,
        off: offDays[iso] ?? null,
        items: items.filter((it) => it.bucket !== 'done' && (it.action?.due === iso || it.event?.date === iso)),
      });
    }
    while (cells.length % 7) cells.push(null);
    return cells;
  }

  // Next N days starting today, for week strips.
  function strip(n) {
    return Array.from({ length: n }, (_, i) => {
      const date = new Date(TODAY); date.setDate(date.getDate() + i);
      const iso = date.toISOString().slice(0, 10);
      return {
        iso, n: date.getDate(), wd: WD[date.getDay()], mon: M[date.getMonth()],
        today: i === 0, weekend: date.getDay() === 0 || date.getDay() === 6,
        off: offDays[iso] ?? null,
        items: items.filter((it) => it.bucket !== 'done' && (it.action?.due === iso || it.event?.date === iso)),
      };
    });
  }

  const params = new URLSearchParams(location.search);

  window.MOCK = { TODAY, kids, health, items, detail, offDays, fmt, kid, dateOf, dayDiff, counts, monthCells, strip, params, user: 'Morgan' };
})();
