"use client";

import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";

const serverErrors = {
  invalid_account: { field: "account", message: "영문, 숫자, 마침표(.), 밑줄(_), 하이픈(-)으로 1~30자를 입력해 주세요." },
  duplicate: { field: "account", message: "이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요." },
  invalid_password: { field: "password", message: "공백 없이 영문, 숫자, 기호로 1~10자를 입력해 주세요." },
  too_large: { field: null, message: "입력이 너무 깁니다. 아이디와 비밀번호의 길이를 확인해 주세요." },
  unavailable: { field: null, message: "가입 결과를 확인하지 못했습니다. 잠시 후 같은 아이디와 비밀번호로 다시 시도하세요." },
} as const;
const errors = {
  ...serverErrors,
  duplicate_after_retry: {
    field: "account",
    message: "이 아이디는 이미 등록되어 있습니다. 처음 가입할 때 입력한 비밀번호로 게임에 로그인해 보세요. 로그인할 수 없다면 다른 아이디를 입력하세요.",
  },
} as const;
type ErrorCode = keyof typeof errors;
type Result = { kind: "idle" | "pending" } | { kind: "error"; code: ErrorCode } | { kind: "success"; account: string };

export default function RegistrationForm({ loginPort }: { loginPort: number | null }) {
  const [result, setResult] = useState<Result>({ kind: "idle" });
  const [showPassword, setShowPassword] = useState(false);
  const accountInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const successTitle = useRef<HTMLHeadingElement>(null);
  const unconfirmedAccounts = useRef(new Set<string>());
  const error = result.kind === "error" ? errors[result.code] : null;
  const pending = result.kind === "pending";

  useEffect(() => {
    if (result.kind === "success") successTitle.current?.focus();
    if (error?.field === "account") accountInput.current?.focus();
    if (error?.field === "password") passwordInput.current?.focus();
  }, [result, error]);

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const data = new FormData(event.currentTarget);
    const account = String(data.get("account") ?? "");
    const password = String(data.get("password") ?? "");
    setShowPassword(false);
    setResult({ kind: "pending" });
    try {
      const response = await fetch("/api/register", {
        method: "POST",
        body: new URLSearchParams({ account, password }),
        signal: AbortSignal.timeout(10000),
      });
      const code = await response.text();
      if (response.status === 201 && code === "created") {
        setResult({ kind: "success", account });
        return;
      }
      if (code === "duplicate" && unconfirmedAccounts.current.has(account)) {
        setResult({ kind: "error", code: "duplicate_after_retry" });
      } else if (Object.hasOwn(serverErrors, code) && code !== "unavailable") {
        setResult({ kind: "error", code: code as keyof typeof serverErrors });
      } else {
        unconfirmedAccounts.current.add(account);
        setResult({ kind: "error", code: "unavailable" });
      }
    } catch {
      unconfirmedAccounts.current.add(account);
      setResult({ kind: "error", code: "unavailable" });
    }
  }

  function clearFieldError(field: "account" | "password") {
    if (error?.field === field) setResult({ kind: "idle" });
  }

  if (result.kind === "success") {
    return (
      <div className="success">
        <p className="success-mark" aria-hidden="true">✓</p>
        <h2 ref={successTitle} tabIndex={-1}>계정을 만들었습니다</h2>
        <dl><dt>게임 아이디</dt><dd>{result.account}</dd></dl>
        <div className="next-steps">
          <h3>게임에 로그인하기</h3>
          <ol>
            <li>
              {loginPort === null
                ? "LOGH7 런처에서 가입한 서버의 주소와 로그인 포트를 확인하세요."
                : <>
                  LOGH7 런처에 아래 값을 입력하세요.
                  <dl className="login-settings">
                    <dt>서버 주소</dt><dd>127.0.0.1</dd>
                    <dt>로그인 포트</dt><dd>{loginPort}</dd>
                  </dl>
                </>}
            </li>
            <li>게임을 실행해 위 아이디와 방금 만든 비밀번호를 입력하세요.</li>
          </ol>
        </div>
      </div>
    );
  }

  return (
    <form method="post" onSubmit={register} noValidate aria-labelledby="form-title">
      <div>
        <h2 id="form-title">계정 정보</h2>
        <p className="hint form-note">아이디와 비밀번호를 모두 입력하세요.</p>
      </div>
      <div className="field">
        <div>
          <label htmlFor="account">게임 아이디</label>
          <p id="account-hint" className="hint">1~30자 · 영문, 숫자, 마침표(.), 밑줄(_), 하이픈(-)</p>
        </div>
        <input id="account" name="account" ref={accountInput} required readOnly={pending}
          autoComplete="username" autoCapitalize="none" spellCheck={false} onChange={() => clearFieldError("account")}
          aria-invalid={error?.field === "account" || undefined}
          aria-describedby={error?.field === "account" ? "account-error account-hint" : "account-hint"} />
        {error?.field === "account" && <p id="account-error" className="error" role="alert">{error.message}</p>}
      </div>
      <div className="field">
        <div>
          <label htmlFor="password">게임 비밀번호</label>
          <p id="password-hint" className="hint">1~10자 · 공백 없이 영문, 숫자, 기호</p>
        </div>
        <div className="password-row">
          <input id="password" name="password" ref={passwordInput} type={showPassword ? "text" : "password"}
            required readOnly={pending} autoComplete="new-password" autoCapitalize="none" spellCheck={false}
            onChange={() => clearFieldError("password")} aria-invalid={error?.field === "password" || undefined}
            aria-describedby={error?.field === "password" ? "password-error password-hint" : "password-hint"} />
          <button type="button" className="reveal" aria-pressed={showPassword} disabled={pending}
            onClick={() => setShowPassword(visible => !visible)}>비밀번호 표시</button>
        </div>
        {error?.field === "password" && <p id="password-error" className="error" role="alert">{error.message}</p>}
      </div>
      <div className="submission">
        <p className="error" role="alert">{error?.field === null ? error.message : ""}</p>
        <p className="hint submission-status" role="status">{pending ? "계정을 만드는 중입니다." : ""}</p>
        <button type="submit" className="submit" aria-disabled={pending || undefined}>
          계정 만들기
        </button>
      </div>
      <noscript><p className="error">가입하려면 브라우저에서 자바스크립트를 켜고 새로고침해 주세요.</p></noscript>
    </form>
  );
}
