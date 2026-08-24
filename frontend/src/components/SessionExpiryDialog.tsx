import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '@/api/endpoints/auth';
import { clearReturnPath, saveReturnPath } from '@/lib/authReturnPath';
import { useAuthStore } from '@/store/authStore';
import { cx, clsx } from '@/styles/cx';

const WARNING_WINDOW_MS = 10 * 60 * 1000;

export default function SessionExpiryDialog() {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const laterButtonRef = useRef<HTMLButtonElement>(null);
  const reauthorizeButtonRef = useRef<HTMLButtonElement>(null);
  const authorizeControllerRef = useRef<AbortController | null>(null);
  const expiryReadbackPendingRef = useRef(false);
  const navigate = useNavigate();
  const userType = useAuthStore(state => state.userType);
  const expiresAt = useAuthStore(state => state.expiresAt);
  const clearSession = useAuthStore(state => state.clearSession);
  const [dismissed, setDismissed] = useState(false);
  const [open, setOpen] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = authorizeControllerRef.current;
    if (controller) {
      authorizeControllerRef.current = null;
      controller.abort();
      clearReturnPath();
    }
    setDismissed(false);
    setOpen(false);
    setAuthorizing(false);
    setError('');
  }, [userType, expiresAt]);

  useEffect(() => () => {
    const controller = authorizeControllerRef.current;
    if (controller) {
      authorizeControllerRef.current = null;
      controller.abort();
      clearReturnPath();
    }
  }, []);

  useEffect(() => {
    let active = true;

    const checkExpiry = async () => {
      if (userType !== 'GOOGLE' || !expiresAt) {
        setOpen(false);
        return;
      }

      const remaining = new Date(expiresAt).getTime() - Date.now();
      if (!Number.isFinite(remaining)) {
        setOpen(false);
      } else if (remaining <= 0) {
        if (expiryReadbackPendingRef.current) return;
        expiryReadbackPendingRef.current = true;
        try {
          const session = await authApi.sessionOrNull();
          if (!active || session?.authenticated) return;

          const controller = authorizeControllerRef.current;
          if (controller) {
            authorizeControllerRef.current = null;
            controller.abort();
            setAuthorizing(false);
          }
          saveReturnPath();
          clearSession();
          navigate('/login', { replace: true });
        } catch {
          return;
        } finally {
          expiryReadbackPendingRef.current = false;
        }
      } else if (dismissed) {
        setOpen(false);
      } else {
        setOpen(remaining <= WARNING_WINDOW_MS);
      }
    };

    const handleVisibilityChange = () => void checkExpiry();
    void checkExpiry();
    const interval = window.setInterval(() => void checkExpiry(), 60_000);
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      active = false;
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [userType, expiresAt, dismissed, clearSession, navigate]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  const dismiss = () => {
    const controller = authorizeControllerRef.current;
    if (controller) {
      authorizeControllerRef.current = null;
      controller.abort();
      clearReturnPath();
      setAuthorizing(false);
    }
    setDismissed(true);
    setOpen(false);
    setError('');
  };

  const reauthorize = async () => {
    const controller = new AbortController();
    authorizeControllerRef.current = controller;
    setAuthorizing(true);
    setError('');
    saveReturnPath();
    try {
      const authorizeUrl = await authApi.googleAuthorizeUrl(controller.signal);
      if (controller.signal.aborted) return;
      window.location.assign(authorizeUrl);
    } catch {
      if (controller.signal.aborted) return;
      clearReturnPath();
      setError('다시 로그인을 시작하지 못했습니다. 잠시 후 다시 시도하세요.');
    } finally {
      if (authorizeControllerRef.current === controller) {
        authorizeControllerRef.current = null;
        setAuthorizing(false);
      }
    }
  };

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="session-expiry-title"
      onCancel={(event) => {
        event.preventDefault();
        dismiss();
      }}
      onKeyDown={(event) => {
        if (event.key !== 'Tab') return;
        const laterButton = laterButtonRef.current;
        const reauthorizeButton = reauthorizeButtonRef.current;
        if (!laterButton || !reauthorizeButton) return;
        if (reauthorizeButton.disabled) {
          event.preventDefault();
          laterButton.focus();
        } else if (event.shiftKey && document.activeElement === laterButton) {
          event.preventDefault();
          reauthorizeButton.focus();
        } else if (!event.shiftKey && document.activeElement === reauthorizeButton) {
          event.preventDefault();
          laterButton.focus();
        }
      }}
      className={clsx(cx.modal, 'm-auto max-w-[420px] backdrop:bg-black/40')}
    >
      <h2 id="session-expiry-title" className={cx.text.heading}>세션이 곧 만료됩니다</h2>
      <p className={clsx(cx.text.body, 'mt-3')}>
        작업을 계속하려면 Google 계정으로 다시 로그인해주세요.
      </p>
      {error && <p role="alert" className={clsx(cx.errorBox, 'mt-4')}>{error}</p>}
      <div className="mt-6 flex justify-end gap-2">
        <button ref={laterButtonRef} type="button" autoFocus onClick={dismiss} className={cx.btn.secondary}>
          나중에
        </button>
        <button
          type="button"
          ref={reauthorizeButtonRef}
          onClick={reauthorize}
          disabled={authorizing}
          className={cx.btn.primary}
        >
          {authorizing ? '로그인 준비 중' : '지금 다시 로그인'}
        </button>
      </div>
    </dialog>
  );
}
