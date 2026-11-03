//! 패치 목록 서명 확인. 작성: Peppone Choi.
//!
//! `tools/ops/patch_sign.py`가 만든 ECDSA P-256 서명(r||s 128자리 16진수)을 공개키(X||Y 128자리
//! 16진수)로 확인한다. 외부 크레이트 없이 Windows CNG(`bcrypt.dll`)를 쓴다.
use crate::patch::sha256;

fn hex(text: &str, length: usize) -> Option<Vec<u8>> {
    let text = text.trim();
    if text.len() != length * 2 || !text.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    (0..length)
        .map(|i| u8::from_str_radix(&text[i * 2..i * 2 + 2], 16).ok())
        .collect()
}

/// 공개키 문자열 형식 확인(명령행 인자 검사용).
pub fn valid_key(key: &str) -> bool {
    hex(key, 64).is_some()
}

/// `data`의 SHA-256에 대한 서명이 `key`로 맞는지 확인한다.
pub fn verify(key: &str, data: &[u8], signature: &str) -> Result<(), String> {
    let key = hex(key, 64).ok_or("공개키는 128자리 16진수여야 합니다")?;
    let signature = hex(signature, 64).ok_or("서명 파일 형식이 아닙니다")?;
    let digest = hex(&sha256(data), 32).expect("sha256 hex");
    if platform::verify(&key, &digest, &signature) {
        Ok(())
    } else {
        Err("패치 목록 서명이 신뢰 공개키와 맞지 않습니다. 받지 않았습니다".into())
    }
}

#[cfg(windows)]
mod platform {
    use std::{ffi::c_void, ptr};
    #[link(name = "bcrypt")]
    unsafe extern "system" {
        fn BCryptOpenAlgorithmProvider(
            algorithm: *mut *mut c_void,
            id: *const u16,
            implementation: *const u16,
            flags: u32,
        ) -> i32;
        fn BCryptCloseAlgorithmProvider(algorithm: *mut c_void, flags: u32) -> i32;
        fn BCryptImportKeyPair(
            algorithm: *mut c_void,
            import_key: *mut c_void,
            blob_type: *const u16,
            key: *mut *mut c_void,
            input: *const u8,
            input_length: u32,
            flags: u32,
        ) -> i32;
        fn BCryptDestroyKey(key: *mut c_void) -> i32;
        fn BCryptVerifySignature(
            key: *mut c_void,
            padding: *const c_void,
            hash: *const u8,
            hash_length: u32,
            signature: *const u8,
            signature_length: u32,
            flags: u32,
        ) -> i32;
    }
    /// BCRYPT_ECDSA_PUBLIC_P256_MAGIC ("ECS1").
    const PUBLIC_P256: u32 = 0x3153_4345;

    fn wide(text: &str) -> Vec<u16> {
        text.encode_utf16().chain([0]).collect()
    }

    pub fn verify(key: &[u8], digest: &[u8], signature: &[u8]) -> bool {
        let mut blob = Vec::with_capacity(8 + key.len());
        blob.extend_from_slice(&PUBLIC_P256.to_le_bytes());
        blob.extend_from_slice(&32u32.to_le_bytes());
        blob.extend_from_slice(key);
        // SAFETY: every handle is checked before use and released on all paths; buffers outlive the calls.
        unsafe {
            let mut algorithm = ptr::null_mut();
            if BCryptOpenAlgorithmProvider(
                &mut algorithm,
                wide("ECDSA_P256").as_ptr(),
                ptr::null(),
                0,
            ) != 0
            {
                return false;
            }
            let mut handle = ptr::null_mut();
            let imported = BCryptImportKeyPair(
                algorithm,
                ptr::null_mut(),
                wide("ECCPUBLICBLOB").as_ptr(),
                &mut handle,
                blob.as_ptr(),
                blob.len() as u32,
                0,
            ) == 0;
            let valid = imported
                && BCryptVerifySignature(
                    handle,
                    ptr::null(),
                    digest.as_ptr(),
                    digest.len() as u32,
                    signature.as_ptr(),
                    signature.len() as u32,
                    0,
                ) == 0;
            if imported {
                BCryptDestroyKey(handle);
            }
            BCryptCloseAlgorithmProvider(algorithm, 0);
            valid
        }
    }
}

#[cfg(not(windows))]
mod platform {
    pub fn verify(_: &[u8], _: &[u8], _: &[u8]) -> bool {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    // patch_sign.py로 고정 개인키에서 만든 시험 벡터.
    const KEY: &str = "471c3e758c4904285bba7e53118ed0f524adeb0757d25bd2f8e7b0d76dfa714cdd520f7aca8a8b917acc37f51de8f0c9bbe3ad858382e702dc25a12d09f7a858";
    const SIGNATURE: &str = "c855369447a8585fe2a521392b5d54486faf2234ddc37e33c318333ece592a223f273ee2a05ee7ff9e180d7e5c57b1238644056aae7460a8e8c1c9bdc60d3e13";
    const MANIFEST: &[u8] = b"LOGH7-PATCH-1\nclient\t00\nfile\texe/dinput8.dll\t11\n";

    #[test]
    fn accepts_the_signed_manifest_only() {
        assert!(verify(KEY, MANIFEST, &format!("{SIGNATURE}\n")).is_ok());
        assert!(verify(KEY, b"LOGH7-PATCH-1\nclient\t00\n", SIGNATURE).is_err());
        let other = format!(
            "{}{}",
            &KEY[..127],
            if KEY.ends_with('8') { '9' } else { '8' }
        );
        assert!(verify(&other, MANIFEST, SIGNATURE).is_err());
        assert!(verify(KEY, MANIFEST, "00").is_err());
        assert!(!valid_key("xyz") && valid_key(KEY));
    }
}
