const effectiveDate = 'August 21, 2026';

export default function TermsPage() {
  return (
    <main className="min-h-screen bg-[var(--paper)] px-6 py-12 text-[var(--ink)]">
      <article className="mx-auto max-w-3xl">
        <a href="/" className="font-[family-name:var(--font-display)] text-[15px] font-extrabold tracking-[-0.02em]">
          TASKFLOW
        </a>

        <header className="mt-12 border-b border-[var(--rule)] pb-8">
          <p className="font-mono text-[11px] font-medium uppercase tracking-[0.1em] text-[var(--ink-3)]">Legal</p>
          <h1 className="mt-2 font-[family-name:var(--font-display)] text-[40px] font-extrabold tracking-[-0.03em]">
            Terms of Service
          </h1>
          <p className="mt-3 text-[14px] text-[var(--ink-2)]">Effective date: {effectiveDate}</p>
        </header>

        <div className="space-y-10 py-10 text-[14px] leading-7 text-[var(--ink-2)]">
          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">1. Service and operator</h2>
            <p className="mt-3">
              TaskFlow is operated by Jungseok Heo. It lets you manage projects and tasks and, when you choose, create,
              update, or delete linked events in your primary Google Calendar. Contact{' '}
              <a className="underline underline-offset-4" href="mailto:tjrwjdgj@gmail.com">tjrwjdgj@gmail.com</a>.
            </p>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">2. Your account and responsibilities</h2>
            <p className="mt-3">
              You are responsible for activity under your Google account and for the tasks and calendar events you
              create. Do not misuse TaskFlow, interfere with its operation, or use it in violation of applicable law or
              another person&apos;s rights.
            </p>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">3. Google Calendar and AI features</h2>
            <p className="mt-3">
              Calendar synchronization requires your Google authorization. You may disconnect it at any time; doing so
              stops future synchronization but does not delete existing calendar events. AI features run only when you
              request them and may send relevant task fields to Google Gemini as described in the Privacy Policy.
            </p>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">4. Availability and changes</h2>
            <p className="mt-3">
              TaskFlow is provided on an as-available basis. Features may change, pause, or end, and synchronization may
              be delayed by network or third-party service failures. Material changes to these terms will be posted here
              with an updated effective date.
            </p>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">5. Ending use</h2>
            <p className="mt-3">
              You may stop using TaskFlow or request account deletion at any time. We may restrict access needed to
              protect the service, other users, or comply with law. Data deletion is handled as described in the{' '}
              <a className="underline underline-offset-4" href="/privacy">Privacy Policy</a>.
            </p>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">6. Disclaimer and liability</h2>
            <p className="mt-3">
              To the extent permitted by law, TaskFlow is provided without warranties and the operator is not liable for
              indirect or consequential loss caused by use of the service or third-party outages. Nothing in these terms
              limits rights or liability that cannot legally be limited.
            </p>
          </section>
        </div>
      </article>
    </main>
  );
}
