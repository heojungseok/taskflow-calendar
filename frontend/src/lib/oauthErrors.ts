const OAUTH_ERRORS = {
  consent_cancelled: {
    message: 'Google 권한 확인이 취소되었습니다. 로그인하려면 다시 시도해주세요.',
    reconsent: false,
  },
  calendar_permission_required: {
    message: 'Google Calendar 권한이 필요합니다. 권한을 다시 확인해주세요.',
    reconsent: true,
  },
  refresh_token_unavailable: {
    message: 'Google 연결을 복구하지 못했습니다. 권한을 다시 확인하거나 Google 계정에서 TaskFlow 액세스를 삭제한 뒤 다시 시도해주세요.',
    reconsent: true,
  },
  oauth_failed: {
    message: 'Google 로그인에 실패했습니다. 다시 시도해주세요.',
    reconsent: false,
  },
} as const;

export function isOAuthError(code: string | null) {
  return code !== null && code in OAUTH_ERRORS;
}

export function oauthError(code: string | null) {
  return isOAuthError(code)
    ? OAUTH_ERRORS[code as keyof typeof OAUTH_ERRORS]
    : code ? OAUTH_ERRORS.oauth_failed : null;
}
