const effectiveDate = 'August 21, 2026';

export default function PrivacyPage() {
  return (
    <main className="min-h-screen bg-[var(--paper)] px-6 py-12 text-[var(--ink)]">
      <article className="mx-auto max-w-3xl">
        <a
          href="/login"
          className="font-[family-name:var(--font-display)] text-[15px] font-extrabold tracking-[-0.02em]"
        >
          TASKFLOW
        </a>

        <header className="mt-12 border-b border-[var(--rule)] pb-8">
          <p className="font-mono text-[11px] font-medium uppercase tracking-[0.1em] text-[var(--ink-3)]">
            Legal
          </p>
          <h1 className="mt-2 font-[family-name:var(--font-display)] text-[40px] font-extrabold tracking-[-0.03em]">
            Privacy Policy
          </h1>
          <p className="mt-3 text-[14px] text-[var(--ink-2)]">
            Effective date: {effectiveDate}
          </p>
        </header>

        <div className="space-y-10 py-10 text-[14px] leading-7 text-[var(--ink-2)]">
          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">1. Operator and contact</h2>
            <p className="mt-3">
              TaskFlow is operated by Heojeongseok. For privacy questions or data-deletion requests, contact{' '}
              <a className="underline underline-offset-4" href="mailto:tjrwjdgj@gmail.com">
                tjrwjdgj@gmail.com
              </a>.
            </p>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">2. Information we collect and store</h2>
            <ul className="mt-3 list-disc space-y-2 pl-5">
              <li>Google account email address and display name used to sign in.</li>
              <li>Google OAuth access token, refresh token, granted scopes, and token expiry time.</li>
              <li>Projects, tasks, task history, and calendar-sync status created in TaskFlow.</li>
              <li>Google Calendar event IDs created or managed through TaskFlow.</li>
              <li>Session cookies and minimum operational logs needed for security and troubleshooting.</li>
            </ul>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">3. How we use information</h2>
            <ul className="mt-3 list-disc space-y-2 pl-5">
              <li>To sign you in with Google and maintain your TaskFlow session.</li>
              <li>To create, update, or delete Google Calendar events only for tasks you choose to sync.</li>
              <li>To refresh Google access tokens and retry failed calendar synchronization.</li>
              <li>To operate, secure, and troubleshoot TaskFlow.</li>
            </ul>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">4. External processing</h2>
            <p className="mt-3">
              TaskFlow uses the Google Calendar API to synchronize the calendar events you select. When you explicitly use
              an AI feature, relevant task content may be sent to the Google Gemini API to generate a summary,
              recommendation, or search result.
            </p>
            <p className="mt-3">
              TaskFlow&apos;s use and transfer of information received from Google APIs adheres to the{' '}
              <a
                className="underline underline-offset-4"
                href="https://developers.google.com/terms/api-services-user-data-policy"
                target="_blank"
                rel="noreferrer"
              >
                Google API Services User Data Policy
              </a>
              , including the Limited Use requirements.
            </p>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">5. Retention and deletion</h2>
            <ul className="mt-3 list-disc space-y-2 pl-5">
              <li>
                Disconnecting Google in TaskFlow revokes and deletes the stored Google OAuth tokens and ends your
                TaskFlow session. Existing Google Calendar events are not deleted by disconnecting.
              </li>
              <li>Deleting a synced task requests deletion of its connected Google Calendar event.</li>
              <li>Demo-user data is isolated per visitor and automatically deleted after 24 hours.</li>
              <li>
                You can request deletion of your TaskFlow account and associated data by email. We will process verified
                requests within 30 days.
              </li>
            </ul>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">6. Sharing and sale</h2>
            <p className="mt-3">
              TaskFlow does not sell personal information. We do not share your personal information except with Google
              services needed to provide the features described in this policy or when required by law.
            </p>
          </section>

          <section>
            <h2 className="text-[19px] font-semibold text-[var(--ink)]">7. Changes to this policy</h2>
            <p className="mt-3">
              We will post changes on this page and update the effective date above. Material changes take effect when
              posted unless a later date is stated.
            </p>
          </section>
        </div>
      </article>
    </main>
  );
}
