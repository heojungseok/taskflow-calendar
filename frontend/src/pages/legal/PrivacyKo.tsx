const h2 = 'text-[19px] font-semibold text-[var(--ink)]';
const link = 'underline underline-offset-4';

export default function PrivacyKo() {
  return (
    <>
      <p className="text-[13px] text-[var(--ink-3)]">
        이 문서는 영문 개인정보처리방침의 번역본입니다. 해석에 차이가 있을 경우 영문본이 우선합니다.
      </p>

      <section>
        <h2 className={h2}>1. 운영자 및 연락처</h2>
        <p className="mt-3">
          TaskFlow는 허정석이 운영합니다. 개인정보 관련 문의나 데이터 삭제 요청은{' '}
          <a className={link} href="mailto:tjrwjdgj@gmail.com">tjrwjdgj@gmail.com</a>으로 연락해 주세요.
        </p>
      </section>

      <section>
        <h2 className={h2}>2. 수집·저장하는 정보</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>로그인에 사용한 Google 계정의 이메일 주소와 표시 이름</li>
          <li>Google OAuth 액세스 토큰, 리프레시 토큰, 허용된 권한 범위, 토큰 만료 시각</li>
          <li>TaskFlow에서 생성한 프로젝트, 작업, 작업 변경 이력, 캘린더 동기화 상태</li>
          <li>
            동기화한 작업에 대해 해당 Google Calendar 이벤트 ID를 저장합니다. 이벤트를 수정할 때는 기본 캘린더에서 그
            이벤트만 조회해 제목, 설명, 시작 시각, 종료 시각에 접근합니다. 관련 없는 캘린더 이벤트를 조회하거나
            가져오지 않습니다.
          </li>
          <li>
            세션·OAuth 상태·CSRF 용도의 필수 쿠키와, 보안 및 장애 대응에 필요한 범위의 운영 로그(크기 제한 적용)
          </li>
        </ul>
      </section>

      <section>
        <h2 className={h2}>3. 정보의 이용 목적</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>Google 계정으로 로그인하고 TaskFlow 세션을 유지하기 위해</li>
          <li>사용자가 동기화를 선택한 작업에 한해 Google Calendar 이벤트를 생성·수정·삭제하기 위해</li>
          <li>Google 액세스 토큰을 갱신하고 실패한 캘린더 동기화를 재시도하기 위해</li>
          <li>TaskFlow를 운영·보호하고 장애에 대응하기 위해</li>
        </ul>
      </section>

      <section>
        <h2 className={h2}>4. 외부 처리</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>
            TaskFlow는 <code>calendar.events.owned</code> 권한을 요청합니다. 이 권한은 사용자가 소유한 캘린더의 이벤트를
            관리할 수 있게 합니다. 현재 TaskFlow는 사용자가 동기화를 선택한 작업과 연결된 기본 캘린더의 이벤트에만
            이 권한을 사용하며, 관련 없는 이벤트를 조회하거나 가져오지 않습니다.
          </li>
          <li>
            사용자가 AI 기능을 명시적으로 실행할 때, 요청한 결과를 생성하기 위해 제목·설명·일정·상태 등 관련 작업 항목이
            Google Gemini API로 전송될 수 있습니다. OAuth 토큰과 Google Calendar 이벤트 데이터는 Gemini로 전송되지
            않습니다.
          </li>
          <li>
            Cloudflare는 DNS, HTTPS 프록시, 보안 서비스를 제공하며, TaskFlow를 전달하고 보호하는 데 필요한 IP 주소,
            요청 메타데이터, 네트워크 트래픽을 처리할 수 있습니다.
          </li>
        </ul>
        <p className="mt-3">
          TaskFlow가 Google API로부터 받은 정보를 이용하고 전송하는 행위는 제한적 사용(Limited Use) 요건을 포함한{' '}
          <a
            className={link}
            href="https://developers.google.com/terms/api-services-user-data-policy"
            target="_blank"
            rel="noreferrer"
          >
            Google API 서비스 사용자 데이터 정책
          </a>
          을 따릅니다.
        </p>
      </section>

      <section>
        <h2 className={h2}>5. 보관 및 삭제</h2>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>
            Google 계정 프로필, TaskFlow 프로젝트, 작업, 작업 이력, 동기화 상태, 연결된 이벤트 ID는 계정이 유지되는 동안
            또는 확인된 삭제 요청이 처리될 때까지 보관합니다.
          </li>
          <li>
            Google OAuth 토큰은 사용자가 Google 연결을 해제하거나, 계정을 삭제하거나, 그 밖의 사유로 권한이 취소될
            때까지 보관합니다.
          </li>
          <li>운영 로그는 설정된 크기 제한 내에서 순환하며, 제한에 도달하면 오래된 로그 파일부터 덮어씁니다.</li>
          <li>
            TaskFlow에서 Google 연결을 해제하면 저장된 Google OAuth 토큰을 취소하고 삭제하며 TaskFlow 세션을 종료합니다.
            이미 생성된 Google Calendar 이벤트는 연결 해제만으로 삭제되지 않습니다.
          </li>
          <li>동기화된 작업을 삭제하면 연결된 Google Calendar 이벤트의 삭제를 요청합니다.</li>
          <li>데모 사용자 데이터는 방문자별로 분리되며 24시간 후 자동으로 삭제됩니다.</li>
          <li>
            이메일로 TaskFlow 계정 및 관련 데이터의 삭제를 요청할 수 있습니다. 확인된 요청은 30일 이내에 처리합니다.
            이미 생성된 Google Calendar 이벤트는, Google이 연결된 상태에서 해당 동기화 작업을 삭제하지 않는 한 그대로
            남습니다. 권한이 취소된 뒤에는 TaskFlow가 해당 이벤트를 삭제할 수 없습니다.
          </li>
        </ul>
      </section>

      <section>
        <h2 className={h2}>6. 데이터 보호 조치</h2>
        <p className="mt-3">
          OAuth 토큰과 작업에 연결된 캘린더 이벤트를 포함한 Google 사용자 데이터는 다음 조치로 보호합니다.
        </p>
        <ul className="mt-3 list-disc space-y-2 pl-5">
          <li>
            <strong>전송 구간 암호화.</strong> 브라우저와 TaskFlow 사이의 모든 트래픽은 TLS로 암호화합니다. 평문 HTTP
            요청은 HTTPS로 리다이렉트되며, HTTP Strict Transport Security 헤더를 전송해 브라우저가 HTTPS로만 접속하도록
            합니다.
          </li>
          <li>
            <strong>저장 시 암호화.</strong> Google OAuth 액세스 토큰과 리프레시 토큰은 데이터베이스에 기록되기 전에
            AES-256-GCM으로 암호화합니다. 암호화 키는 서버 환경변수로만 주입되며 데이터베이스에 저장하지 않으므로,
            데이터베이스 사본만으로는 토큰을 복원할 수 없습니다.
          </li>
          <li>
            <strong>세션 보호.</strong> 세션 쿠키와 OAuth 상태 쿠키에는 HttpOnly, Secure, SameSite 속성을 적용하며,
            상태를 변경하는 요청에는 CSRF 토큰을 요구합니다.
          </li>
          <li>
            <strong>접근 통제.</strong> 사용자의 데이터에 접근하는 요청은 반드시 인증을 거쳐야 하며, 프로젝트·작업·캘린더
            동기화 기록은 항상 소유자를 기준으로 조회합니다. 따라서 한 계정이 다른 계정의 데이터를 읽거나 수정할 수 없습니다.
          </li>
          <li>
            <strong>인프라 격리.</strong> 데이터베이스와 캐시는 TaskFlow 내부 네트워크에서만 접근할 수 있으며 공개
            인터넷에 노출되지 않습니다. TaskFlow는 데이터베이스 서버를 관리할 수 없는 최소 권한 계정으로 접속합니다.
          </li>
          <li>
            <strong>로그.</strong> 액세스 토큰과 리프레시 토큰은 애플리케이션 로그에 기록하지 않습니다.
          </li>
          <li>
            <strong>최소 권한과 관리자 접근.</strong> TaskFlow는 캘린더 기능에 필요한 권한 범위만 요청하며, 서버와
            데이터베이스에 대한 관리자 접근은 1항에 기재된 운영자로 제한됩니다.
          </li>
          <li>
            <strong>사용자의 통제권.</strong> 어떤 서비스도 완전한 보안을 보장할 수는 없습니다. 사용자는 언제든{' '}
            <a
              className={link}
              href="https://myaccount.google.com/permissions"
              target="_blank"
              rel="noreferrer"
            >
              Google 계정 권한 페이지
            </a>
            에서 TaskFlow의 접근 권한을 직접 취소할 수 있습니다.
          </li>
        </ul>
      </section>

      <section>
        <h2 className={h2}>7. 제공 및 판매</h2>
        <p className="mt-3">
          TaskFlow는 개인정보를 판매하지 않으며, Google 사용자 데이터를 광고나 범용 AI 모델 학습에 사용하지 않습니다.
          정보는 TaskFlow를 제공하고 보호하는 데 필요한 범위에서 위에 기재한 서비스 제공자에게만, 또는 법령이 요구하는
          경우에만 제공됩니다.
        </p>
      </section>

      <section>
        <h2 className={h2}>8. 정책 변경</h2>
        <p className="mt-3">
          변경 사항은 이 페이지에 게시하고 위의 시행일을 갱신합니다. 중요한 변경은 별도의 시행일을 명시하지 않는 한
          게시 시점부터 적용됩니다.
        </p>
      </section>

      <p>
        <a className={link} href="/terms">서비스 이용약관</a>도 함께 확인해 주세요.
      </p>
    </>
  );
}
