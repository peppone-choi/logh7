"use client";

import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";

const fields = [
  { name: "account", label: "게임 아이디", type: "text", autoComplete: "username" },
  { name: "currentPassword", label: "현재 비밀번호", type: "password", autoComplete: "current-password" },
  { name: "newPassword", label: "새 비밀번호", type: "password", autoComplete: "new-password" },
  { name: "confirmPassword", label: "새 비밀번호 확인", type: "password", autoComplete: "new-password" },
] as const;
type Field = typeof fields[number]["name"];
type Result = { kind: "idle" | "pending" | "success" } | { kind: "error"; message: string; field?: Field };

const newPasswordHint = "1~10자 · 공백 없이 영문, 숫자, 기호";
const badPassword = "공백 없이 영문, 숫자, 기호로 1~10자를 입력해 주세요.";
const invalidCredentials = "게임 아이디 또는 현재 비밀번호를 확인해 주세요.";
const unavailable = "변경 결과를 확인하지 못했습니다. 새 비밀번호로 게임 로그인을 먼저 시도해 주세요.";

export default function PasswordForm() {
  const [result, setResult] = useState<Result>({ kind: "idle" });
  const form = useRef<HTMLFormElement>(null);
  const successTitle = useRef<HTMLHeadingElement>(null);
  const error = result.kind === "error" ? result : null;
  const pending = result.kind === "pending";

  useEffect(() => {
    if (result.kind === "success") successTitle.current?.focus();
    if (error?.field) (form.current?.elements.namedItem(error.field) as HTMLInputElement | null)?.focus();
  }, [result, error]);

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const data = new FormData(event.currentTarget);
    const account = String(data.get("account") ?? "");
    const currentPassword = String(data.get("currentPassword") ?? "");
    const newPassword = String(data.get("newPassword") ?? "");
    if (!/^[A-Za-z0-9._-]{1,30}$/.test(account)) {
      setResult({ kind: "error", field: "account", message: "영문, 숫자, 마침표(.), 밑줄(_), 하이픈(-)으로 1~30자를 입력해 주세요." });
      return;
    }
    if (!/^[!-~]{1,10}$/.test(currentPassword)) {
      setResult({ kind: "error", field: "currentPassword", message: invalidCredentials });
      return;
    }
    if (!/^[!-~]{1,10}$/.test(newPassword)) {
      setResult({ kind: "error", field: "newPassword", message: badPassword });
      return;
    }
    if (newPassword !== data.get("confirmPassword")) {
      setResult({ kind: "error", field: "confirmPassword", message: "새 비밀번호를 동일하게 입력해 주세요." });
      return;
    }

    setResult({ kind: "pending" });
    try {
      const response = await fetch("/api/password", {
        method: "POST",
        body: new URLSearchParams({ account, currentPassword, newPassword }),
        signal: AbortSignal.timeout(10000),
      });
      const code = await response.text();
      switch (code) {
        case "changed": setResult({ kind: "success" }); break;
        case "invalid_credentials": setResult({ kind: "error", field: "currentPassword", message: invalidCredentials }); break;
        case "invalid_password": setResult({ kind: "error", field: "newPassword", message: badPassword }); break;
        case "retry_later": setResult({ kind: "error", message: "시도 횟수가 너무 많습니다. 1분 뒤에 다시 시도해 주세요." }); break;
        default: setResult({ kind: "error", message: unavailable });
      }
    } catch {
      setResult({ kind: "error", message: unavailable });
    }
  }

  return (
    <>
      <p className="sr-only" role="status">{pending ? "비밀번호를 변경하는 중입니다." : ""}</p>
      {result.kind === "success" ? (
        <div className="success">
          <p className="success-mark" aria-hidden="true">✓</p>
          <h2 ref={successTitle} tabIndex={-1}>비밀번호를 변경했습니다</h2>
          <p>다음 게임 로그인에서 새 비밀번호를 사용하세요.</p>
        </div>
      ) : (
        <form ref={form} method="post" onSubmit={changePassword} noValidate aria-labelledby="form-title">
          <h2 id="form-title">현재 비밀번호 확인</h2>
          {fields.map(({ name, label, type, autoComplete }) => {
            const fieldError = error?.field === name ? error.message : null;
            const hint = name === "newPassword";
            return (
              <div className="field" key={name}>
                <label htmlFor={name}>{label}</label>
                <input id={name} name={name} type={type} autoComplete={autoComplete} required
                  readOnly={pending} autoCapitalize="none" spellCheck={false}
                  onChange={() => { if (fieldError) setResult({ kind: "idle" }); }}
                  aria-invalid={Boolean(fieldError) || undefined}
                  aria-describedby={[fieldError && `${name}-error`, hint && "new-password-hint"].filter(Boolean).join(" ") || undefined} />
                {fieldError && <p id={`${name}-error`} className="error" role="alert">{fieldError}</p>}
                {hint && <p id="new-password-hint" className="hint">{newPasswordHint}</p>}
              </div>
            );
          })}
          {error && !error.field && <p className="error" role="alert">{error.message}</p>}
          <button type="submit" className="submit" aria-disabled={pending || undefined}>
            {pending ? "비밀번호 변경 중…" : "비밀번호 변경"}
          </button>
          <noscript><p className="error">비밀번호를 변경하려면 브라우저에서 자바스크립트를 켜고 새로고침해 주세요.</p></noscript>
        </form>
      )}
    </>
  );
}
