const h2 = 'text-[19px] font-semibold text-[var(--ink)]';
const link = 'underline underline-offset-4';

export default function PrivacyEn() {
  return (
    <>
      <section>
        <h2 className={h2}>1. Operator and contact</h2>
        <p className="mt-3">
          TaskFlow is operated by Jungseok Heo. For privacy questions or data-deletion requests, contact{' '}
          <a className={link} href="mailto:tjrwjdgj@gmail.com">tjrwjdgj@gmail.com</a>.
        </p>
      </section>

      <section>
        <h2 className={h2}>2. Information we collect and store</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>Google account email address and display name used to sign in.</li>
          <li>Google OAuth access token, refresh token, granted scopes, and token expiry time.</li>
          <li>Projects, tasks, task history, and calendar-sync status created in TaskFlow.</li>
          <li>
            For a synced task, TaskFlow stores its Google Calendar event ID. When updating that event, TaskFlow
            retrieves that specific event from your primary calendar and accesses its title, description, start time,
            and end time. TaskFlow does not list or import unrelated calendar events.
          </li>
          <li>
            Essential session, OAuth-state, and CSRF cookies, plus size-limited operational logs needed for security
            and troubleshooting.
          </li>
        </ul>
      </section>

      <section>
        <h2 className={h2}>3. How we use information</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>To sign you in with Google and maintain your TaskFlow session.</li>
          <li>To create, update, or delete Google Calendar events only for tasks you choose to sync.</li>
          <li>To refresh Google access tokens and retry failed calendar synchronization.</li>
          <li>To operate, secure, and troubleshoot TaskFlow.</li>
        </ul>
      </section>

      <section>
        <h2 className={h2}>4. External processing</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>
            TaskFlow requests the <code>calendar.events.owned</code> permission, which allows event management on
            calendars you own. TaskFlow currently uses it only for events on your primary calendar that are linked
            to tasks you choose to sync; it does not list or import unrelated events.
          </li>
          <li>
            When you explicitly use an AI feature, relevant TaskFlow task fields, such as the title, description,
            schedule, and status, may be sent to the Google Gemini API to generate the requested result. OAuth tokens
            and Google Calendar event data are not sent to Gemini.
          </li>
          <li>
            Cloudflare provides DNS, HTTPS proxying, and security services and may process your IP address, request
            metadata, and network traffic needed to deliver and protect TaskFlow.
          </li>
        </ul>
        <p className="mt-3">
          TaskFlow&apos;s use and transfer of information received from Google APIs adheres to the{' '}
          <a
            className={link}
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
        <h2 className={h2}>5. Retention and deletion</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>
            Your Google account profile, TaskFlow projects, tasks, task history, sync status, and linked event IDs are
            retained while your account is active or until your verified deletion request is processed.
          </li>
          <li>
            Google OAuth tokens are retained until you disconnect Google, delete your account, or the authorization is
            otherwise revoked.
          </li>
          <li>
            Operational logs are rotated within configured size limits and older log files are overwritten when those
            limits are reached.
          </li>
          <li>
            Disconnecting Google in TaskFlow revokes and deletes the stored Google OAuth tokens and ends your
            TaskFlow session. Existing Google Calendar events are not deleted by disconnecting.
          </li>
          <li>Deleting a synced task requests deletion of its connected Google Calendar event.</li>
          <li>Demo-user data is isolated per visitor and automatically deleted after 24 hours.</li>
          <li>
            You can request deletion of your TaskFlow account and associated data by email. We will process verified
            requests within 30 days. Existing Google Calendar events remain unless you delete their synced tasks while
            Google is still connected; after authorization is revoked, TaskFlow can no longer delete those events.
          </li>
        </ul>
      </section>

      <section>
        <h2 className={h2}>6. How we protect your data</h2>
        <p className="mt-3">
          Google user data, including OAuth tokens and the calendar events linked to your tasks, is protected by the
          following measures.
        </p>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>
            <strong>Encryption in transit.</strong> All traffic between your browser and TaskFlow is encrypted with
            TLS. Plain HTTP requests are redirected to HTTPS, and TaskFlow sends an HTTP Strict Transport Security
            header so browsers connect over HTTPS only.
          </li>
          <li>
            <strong>Encryption at rest.</strong> Google OAuth access and refresh tokens are encrypted with AES-256-GCM
            before they are written to the database. The encryption key is supplied only through the server
            environment and is never stored in the database, so a copy of the database alone cannot reveal your tokens.
          </li>
          <li>
            <strong>Session protection.</strong> Session and OAuth-state cookies are HttpOnly, Secure, and
            SameSite-restricted, and state-changing requests require a CSRF token.
          </li>
          <li>
            <strong>Access control.</strong> Any request that reaches your data must be authenticated, and projects,
            tasks, and calendar-sync records are always retrieved by their owner, so one account cannot read or modify
            another account&apos;s data.
          </li>
          <li>
            <strong>Infrastructure isolation.</strong> The database and cache are reachable only from TaskFlow&apos;s
            internal network and are not exposed to the public internet. TaskFlow connects with a least-privilege
            database account that cannot administer the database server.
          </li>
          <li>
            <strong>Logging.</strong> Access tokens and refresh tokens are never written to application logs.
          </li>
          <li>
            <strong>Minimum scope and administrative access.</strong> TaskFlow requests only the scope its calendar
            feature needs, and administrative access to the servers and database is limited to the operator identified
            in section 1.
          </li>
          <li>
            <strong>Your control.</strong> No service can guarantee absolute security. You can revoke
            TaskFlow&apos;s access to your Google data at any time from your{' '}
            <a
              className={link}
              href="https://myaccount.google.com/permissions"
              target="_blank"
              rel="noreferrer"
            >
              Google Account permissions page
            </a>.
          </li>
        </ul>
      </section>

      <section>
        <h2 className={h2}>7. Sharing and sale</h2>
        <p className="mt-3">
          TaskFlow does not sell personal information or use Google user data for advertising or to train
          general-purpose AI models. Information is shared only with the service providers described above as needed
          to provide and secure TaskFlow, or when required by law.
        </p>
      </section>

      <section>
        <h2 className={h2}>8. Changes to this policy</h2>
        <p className="mt-3">
          We will post changes on this page and update the effective date above. Material changes take effect when
          posted unless a later date is stated.
        </p>
      </section>

      <p>
        See also the <a className={link} href="/terms">Terms of Service</a>.
      </p>
    </>
  );
}
