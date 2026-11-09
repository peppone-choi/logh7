//! 패치 내려받기. 작성: Peppone Choi.
//!
//! HTTPS 또는 시험용 HTTP에서 `patch.manifest`와 그 목록의 파일을 받는다.
//! 받은 파일은 목록의 SHA-256과 대조한다. `patch::apply`도 적용 전에 다시 확인한다.
use crate::patch::sha256;
use std::{
    fs,
    io::{Read, Write},
    net::TcpStream,
    path::Path,
    time::Duration,
};

const LIMIT: usize = 256 << 20;

fn https_agent(base: &str) -> Result<ureq::Agent, String> {
    use ureq::tls::{Certificate, RootCerts, TlsConfig};
    let uri = base
        .parse::<ureq::http::Uri>()
        .map_err(|_| "HTTPS 패치 주소 형식이 올바르지 않습니다")?;
    if !base.is_ascii()
        || base.contains(['#', '?', '\\'])
        || uri
            .authority()
            .is_none_or(|host| host.as_str().contains('@'))
    {
        return Err("HTTPS 패치 주소 형식이 올바르지 않습니다".into());
    }
    let roots = rustls_native_certs::load_native_certs();
    if roots.certs.is_empty() {
        return Err("HTTPS 패치 서버를 확인할 신뢰 인증서를 읽지 못했습니다".into());
    }
    let roots = RootCerts::from(
        roots
            .certs
            .iter()
            .map(|cert| Certificate::from_der(cert.as_ref()).to_owned()),
    );
    let timeout = Some(Duration::from_secs(30));
    Ok(ureq::Agent::config_builder()
        .https_only(true)
        .max_redirects(0)
        .timeout_resolve(timeout)
        .timeout_connect(timeout)
        .timeout_recv_response(timeout)
        .timeout_per_read(timeout)
        .timeout_per_write(timeout)
        .tls_config(TlsConfig::builder().root_certs(roots).build())
        .build()
        .new_agent())
}

fn get_https(agent: &ureq::Agent, base: &str, relative: &str) -> Result<Vec<u8>, String> {
    use std::fmt::Write as _;
    let mut url = format!("{}/", base.trim_end_matches('/'));
    for byte in relative.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~/".contains(&byte) {
            url.push(char::from(byte));
        } else {
            write!(url, "%{byte:02X}").expect("writing to a String cannot fail");
        }
    }
    let mut reply = agent.get(url).call().map_err(|error| match error {
        ureq::Error::StatusCode(code) => format!("패치 서버 응답 HTTP {code}"),
        ureq::Error::Tls(_) | ureq::Error::Rustls(_) => "HTTPS 인증 또는 암호화 연결 실패".into(),
        ureq::Error::Io(ref error) if error.kind() == std::io::ErrorKind::InvalidData => {
            "HTTPS 인증 또는 암호화 연결 실패".into()
        }
        ureq::Error::HostNotFound => "패치 서버 주소를 찾지 못했습니다".into(),
        ureq::Error::Timeout(_) => "HTTPS 패치 요청 시간이 초과됐습니다".into(),
        ureq::Error::TooManyRedirects => "패치 서버가 다른 주소로 이동을 요청했습니다".into(),
        _ => "HTTPS 패치 요청 실패".into(),
    })?;
    if reply.status() != 200 {
        return Err(format!("패치 서버 응답 HTTP {}", reply.status().as_u16()));
    }
    if reply
        .body()
        .content_length()
        .is_some_and(|length| length > LIMIT as u64)
    {
        return Err("패치 파일이 너무 큽니다".into());
    }
    let mut body = Vec::new();
    reply
        .body_mut()
        .as_reader()
        .take((LIMIT + 1) as u64)
        .read_to_end(&mut body)
        .map_err(|_| "HTTPS 패치 응답 본문을 읽지 못했습니다")?;
    if body.len() > LIMIT {
        return Err("패치 파일이 너무 큽니다".into());
    }
    Ok(body)
}

fn get_http(base: &str, relative: &str) -> Result<Vec<u8>, String> {
    let rest = base
        .strip_prefix("http://")
        .ok_or("패치 주소는 https:// 또는 http://로 시작해야 합니다")?;
    let (authority, prefix) = rest.split_once('/').unwrap_or((rest, ""));
    let address = if authority.contains(':') {
        authority.to_string()
    } else {
        format!("{authority}:80")
    };
    let path = format!(
        "/{}{}{}",
        prefix,
        if prefix.is_empty() || prefix.ends_with('/') {
            ""
        } else {
            "/"
        },
        relative
    );
    if path.contains(['\r', '\n', ' ']) {
        return Err("허용하지 않는 요청 경로입니다".into());
    }
    let mut stream = TcpStream::connect(&address).map_err(|_| "패치 서버에 연결하지 못했습니다")?;
    let timeout = Some(Duration::from_secs(30));
    stream.set_read_timeout(timeout).ok();
    stream.set_write_timeout(timeout).ok();
    let request = format!("GET {path} HTTP/1.0\r\nHost: {authority}\r\nConnection: close\r\n\r\n");
    stream
        .write_all(request.as_bytes())
        .map_err(|_| "패치 요청을 보내지 못했습니다")?;
    // Read until Content-Length is satisfied (or EOF without one): a server that resets the
    // connection after a complete body must not fail the download.
    let failed = || "패치 응답 본문을 읽지 못했습니다".into();
    let (mut response, mut chunk) = (Vec::new(), [0u8; 65536]);
    let mut framing: Option<(usize, Option<usize>)> = None;
    loop {
        if let Some((end, Some(length))) = framing {
            if response.len() >= end + 4 + length {
                response.truncate(end + 4 + length);
                break;
            }
        }
        let n = match stream.read(&mut chunk) {
            Ok(n) => n,
            Err(_) if matches!(framing, Some((_, Some(_)))) => return Err(failed()),
            Err(_) if framing.is_some() => break,
            Err(_) => return Err(failed()),
        };
        if n == 0 {
            break;
        }
        response.extend_from_slice(&chunk[..n]);
        if response.len() > LIMIT + 65536 {
            return Err("패치 파일이 너무 큽니다".into());
        }
        if framing.is_none() {
            if let Some(end) = response.windows(4).position(|w| w == b"\r\n\r\n") {
                let head = String::from_utf8_lossy(&response[..end]).to_ascii_lowercase();
                let length = head
                    .lines()
                    .find_map(|l| l.strip_prefix("content-length:"))
                    .and_then(|v| v.trim().parse().ok());
                framing = Some((end, length));
            }
        }
    }
    let (end, length) = framing.ok_or("패치 응답 머리가 없습니다")?;
    let head = String::from_utf8_lossy(&response[..end]);
    let status = head
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .filter(|code| code.len() == 3 && code.bytes().all(|b| b.is_ascii_digit()))
        .ok_or("패치 서버의 HTTP 상태 코드가 올바르지 않습니다")?;
    if status != "200" {
        return Err(format!("패치 서버 응답 HTTP {status}"));
    }
    let body = response[end + 4..].to_vec();
    if length.is_some_and(|length| body.len() != length) {
        return Err(failed());
    }
    if body.len() > LIMIT {
        return Err("패치 파일이 너무 큽니다".into());
    }
    Ok(body)
}

/// `expected`는 신뢰할 수 있는 다른 경로(배포 공지·런처 설정)로 받은 목록의 SHA-256이다. 주면 목록이
/// 그 값과 같을 때만 받는다. `trusted`는 `patch_sign.py`의 공개키다. 주면 `patch.manifest.sig`가
/// 그 키로 맞을 때만 받는다(패치마다 고정값을 다시 알릴 필요가 없는 고정점).
pub fn download(
    base: &str,
    out: &Path,
    expected: Option<&str>,
    trusted: Option<&str>,
) -> Result<usize, String> {
    if out.exists() {
        return Err("받을 디렉터리가 이미 있습니다".into());
    }
    let https = base
        .starts_with("https://")
        .then(|| https_agent(base))
        .transpose()?;
    let get = |relative: &str, kind: &str| {
        let result = match &https {
            Some(agent) => get_https(agent, base, relative),
            None => get_http(base, relative),
        };
        result.map_err(|error| format!("{kind}: {error}"))
    };
    let manifest = get("patch.manifest", "패치 목록 받기")?;
    if let Some(expected) = expected {
        if sha256(&manifest) != expected.to_ascii_lowercase() {
            return Err("패치 목록 해시가 고정값과 다릅니다. 받지 않았습니다".into());
        }
    }
    let signature = match trusted {
        Some(key) => {
            let signature = get("patch.manifest.sig", "목록 서명 받기")?;
            let text =
                String::from_utf8(signature.clone()).map_err(|_| "서명 파일 형식이 아닙니다")?;
            crate::signature::verify(key, &manifest, &text)?;
            Some(signature)
        }
        None => None,
    };
    let text =
        String::from_utf8(manifest.clone()).map_err(|_| "patch.manifest가 UTF-8이 아닙니다")?;
    let mut files = Vec::new();
    for line in text.lines().skip(1).map(str::trim) {
        if let Some(rest) = line.strip_prefix("file\t") {
            let (relative, hash) = rest
                .split_once('\t')
                .ok_or("패치 목록의 파일 항목 형식이 올바르지 않습니다")?;
            if relative
                .split('/')
                .any(|part| part.is_empty() || part == "." || part == "..")
                || relative.contains('\\')
                || relative.contains(':')
            {
                return Err("허용하지 않는 패치 경로입니다".into());
            }
            files.push((relative.to_string(), hash.to_ascii_lowercase()));
        }
    }
    if text.lines().next().map(str::trim) != Some("LOGH7-PATCH-1") || files.is_empty() {
        return Err("지원하지 않는 패치 목록입니다".into());
    }
    // 이어받기(파일 단위): 이전 시도의 `.partial`에서 목록 해시와 같은 내용만 빈 새 디렉터리로 옮겨 쓴다.
    // - 새 디렉터리에는 목록 철자 그대로 쓰므로 대소문자가 다른 옛 이름이나 옛 파일/디렉터리 충돌이 남지 않는다.
    // - 옛 디렉터리는 표준 `remove_dir_all`로 지운다. 이 함수는 심볼릭 링크·정션을 따라가지 않는다
    //   (Rust 1.58.1, CVE-2022-21658). 링크를 통해 읽힌 내용도 목록 해시와 같을 때만 쓰므로 섞이지 않는다.
    let staging = out.with_extension("partial");
    let fresh = out.with_extension("partial-next");
    let _ = fs::remove_dir_all(&fresh);
    fs::create_dir_all(&fresh).map_err(|_| "임시 다운로드 디렉터리를 만들지 못했습니다")?;
    let write = |root: &Path, relative: &str, data: &[u8]| -> Result<(), String> {
        let target = root.join(relative);
        fs::create_dir_all(target.parent().unwrap())
            .map_err(|_| "받은 파일을 넣을 디렉터리를 만들지 못했습니다")?;
        fs::write(&target, data).map_err(|_| "받은 파일을 쓰지 못했습니다".into())
    };
    let mut pending = Vec::new();
    let mut reused = 0usize;
    for (relative, hash) in &files {
        match fs::read(staging.join(relative)) {
            Ok(data) if data.len() <= LIMIT && sha256(&data) == *hash => {
                write(&fresh, relative, &data)?;
                reused += 1;
            }
            _ => pending.push((relative, hash)),
        }
    }
    if fs::symlink_metadata(&staging).is_ok() {
        fs::remove_dir_all(&staging)
            .or_else(|_| fs::remove_file(&staging))
            .map_err(|_| "이전 내려받기 디렉터리를 지우지 못했습니다")?;
    }
    fs::rename(&fresh, &staging).map_err(|_| "내려받기 디렉터리를 준비하지 못했습니다")?;
    if reused > 0 {
        eprintln!("이어받기: 이미 받은 파일 {reused}개를 다시 쓰고 나머지만 받습니다");
    }
    for (relative, hash) in pending {
        let data = get(relative, "패치 파일 받기")?;
        if sha256(&data) != *hash {
            return Err("받은 파일 해시가 목록과 다릅니다".into());
        }
        write(&staging, relative, &data)?;
    }
    fs::write(staging.join("patch.manifest"), manifest)
        .map_err(|_| "받은 패치 목록을 쓰지 못했습니다")?;
    if let Some(signature) = signature {
        fs::write(staging.join("patch.manifest.sig"), signature)
            .map_err(|_| "받은 목록 서명을 쓰지 못했습니다")?;
    }
    // 모두 받고 확인한 뒤에만 최종 이름으로 바꾼다.
    fs::rename(&staging, out).map_err(|_| "받은 패치를 최종 위치로 옮기지 못했습니다")?;
    Ok(files.len())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::TcpListener, thread};

    /// 요청마다 경로에 맞는 본문을 한 번 돌려주는 시험 서버.
    fn serve(routes: Vec<(&'static str, Vec<u8>)>) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        thread::spawn(move || {
            for stream in listener.incoming().take(routes.len()) {
                let mut stream = stream.unwrap();
                let (mut request, mut chunk) = (Vec::new(), [0u8; 1024]);
                while !request.windows(4).any(|w| w == b"\r\n\r\n") {
                    let n = stream.read(&mut chunk).unwrap();
                    if n == 0 {
                        break;
                    }
                    request.extend_from_slice(&chunk[..n]);
                }
                let line = String::from_utf8_lossy(&request);
                let path = line.split_whitespace().nth(1).unwrap_or("").to_string();
                match routes.iter().find(|(p, _)| *p == path) {
                    Some((_, body)) => {
                        write!(
                            stream,
                            "HTTP/1.0 200 OK\r\nContent-Length: {}\r\n\r\n",
                            body.len()
                        )
                        .unwrap();
                        stream.write_all(body).unwrap();
                    }
                    None => write!(stream, "HTTP/1.0 404 Not Found\r\n\r\n").unwrap(),
                }
            }
        });
        format!("http://127.0.0.1:{port}/patch")
    }

    fn manifest(hash: &str) -> Vec<u8> {
        format!(
            "LOGH7-PATCH-1\nclient\t{}\nfile\texe/dinput8.dll\t{hash}\n",
            sha256(b"client")
        )
        .into_bytes()
    }

    #[test]
    fn downloads_verified_files_into_a_new_directory() {
        let base = serve(vec![
            ("/patch/patch.manifest", manifest(&sha256(b"dll"))),
            ("/patch/exe/dinput8.dll", b"dll".to_vec()),
        ]);
        let out = std::env::temp_dir().join(format!("logh7-download-ok-{}", std::process::id()));
        let _ = fs::remove_dir_all(&out);
        assert_eq!(1, download(&base, &out, None, None).unwrap());
        assert_eq!(b"dll", &fs::read(out.join("exe/dinput8.dll")).unwrap()[..]);
        assert!(
            download(&base, &out, None, None).is_err(),
            "an existing directory is not overwritten"
        );
        fs::remove_dir_all(&out).unwrap();
    }

    #[test]
    fn a_pinned_manifest_hash_must_match() {
        let listed = manifest(&sha256(b"dll"));
        let routes = vec![
            ("/patch/patch.manifest", listed.clone()),
            ("/patch/exe/dinput8.dll", b"dll".to_vec()),
        ];
        let out = std::env::temp_dir().join(format!("logh7-download-pin-{}", std::process::id()));
        let _ = fs::remove_dir_all(&out);
        let wrong = sha256(b"another list");
        assert!(download(&serve(routes.clone()), &out, Some(&wrong), None).is_err());
        assert!(!out.exists());
        assert_eq!(
            1,
            download(&serve(routes), &out, Some(&sha256(&listed)), None).unwrap()
        );
        fs::remove_dir_all(&out).unwrap();
    }

    #[test]
    fn a_trusted_key_requires_a_matching_signature() {
        // signature.rs의 시험 벡터: 고정 개인키로 서명한 목록.
        let listed = b"LOGH7-PATCH-1
client	00
file	exe/dinput8.dll	11
"
        .to_vec();
        let key = "471c3e758c4904285bba7e53118ed0f524adeb0757d25bd2f8e7b0d76dfa714cdd520f7aca8a8b917acc37f51de8f0c9bbe3ad858382e702dc25a12d09f7a858";
        let signed = b"c855369447a8585fe2a521392b5d54486faf2234ddc37e33c318333ece592a223f273ee2a05ee7ff9e180d7e5c57b1238644056aae7460a8e8c1c9bdc60d3e13
".to_vec();
        let out = std::env::temp_dir().join(format!("logh7-download-sig-{}", std::process::id()));
        let _ = fs::remove_dir_all(&out);
        let mut forged = listed.clone();
        forged.extend_from_slice(
            b"file	exe/evil.dll	22
",
        );
        let base = serve(vec![
            ("/patch/patch.manifest", forged),
            ("/patch/patch.manifest.sig", signed.clone()),
        ]);
        assert!(download(&base, &out, None, Some(key)).is_err());
        assert!(!out.exists());
        // 서명은 맞지만 목록 해시 11은 받은 파일과 달라 실패해야 한다(서명 뒤에도 파일 해시를 확인).
        let base = serve(vec![
            ("/patch/patch.manifest", listed),
            ("/patch/patch.manifest.sig", signed),
            ("/patch/exe/dinput8.dll", b"dll".to_vec()),
        ]);
        let failure = download(&base, &out, None, Some(key)).unwrap_err();
        assert!(
            failure.contains("받은 파일 해시가 목록과 다릅니다"),
            "{failure}"
        );
        assert!(!out.exists());
    }

    #[test]
    fn a_tampered_file_leaves_nothing_behind() {
        let base = serve(vec![
            ("/patch/patch.manifest", manifest(&sha256(b"dll"))),
            ("/patch/exe/dinput8.dll", b"tampered".to_vec()),
        ]);
        let out = std::env::temp_dir().join(format!("logh7-download-bad-{}", std::process::id()));
        let _ = fs::remove_dir_all(&out);
        assert!(download(&base, &out, None, None).is_err());
        // The tampered file is not kept for a later resume, and nothing reaches the final directory.
        assert!(
            !out.exists()
                && !out
                    .with_extension("partial")
                    .join("exe/dinput8.dll")
                    .exists()
        );
        let _ = fs::remove_dir_all(out.with_extension("partial"));
        assert!(download("https://127.0.0.1/patch?x=y", &out, None, None).is_err());
    }

    #[test]
    fn an_interrupted_download_resumes_from_verified_files_and_drops_leftovers() {
        let listed = format!(
            "LOGH7-PATCH-1\nclient\t{}\nfile\texe/a.dll\t{}\nfile\tdata/b.tga\t{}\n",
            sha256(b"client"),
            sha256(b"aaa"),
            sha256(b"bbb")
        )
        .into_bytes();
        let out =
            std::env::temp_dir().join(format!("logh7-download-resume-{}", std::process::id()));
        let staging = out.with_extension("partial");
        let _ = fs::remove_dir_all(&out);
        let _ = fs::remove_dir_all(&staging);
        // First attempt: the second file is missing on the server (connection lost / 404).
        let first = serve(vec![
            ("/patch/patch.manifest", listed.clone()),
            ("/patch/exe/a.dll", b"aaa".to_vec()),
        ]);
        assert!(download(&first, &out, None, None).is_err());
        assert!(!out.exists());
        assert_eq!(
            b"aaa",
            &fs::read(staging.join("exe/a.dll")).unwrap()[..],
            "verified file kept"
        );
        // A leftover from some earlier patch, and a corrupted copy, must not survive.
        fs::create_dir_all(staging.join("old")).unwrap();
        fs::write(staging.join("old/stale.bin"), b"stale").unwrap();
        fs::write(
            staging
                .join("data/b.tga")
                .parent()
                .map(|p| {
                    fs::create_dir_all(p).unwrap();
                    p.join("b.tga")
                })
                .unwrap(),
            b"corrupt",
        )
        .unwrap();
        // Second attempt: a.dll is no longer served, so it can only come from the resume.
        let second = serve(vec![
            ("/patch/patch.manifest", listed),
            ("/patch/data/b.tga", b"bbb".to_vec()),
        ]);
        assert_eq!(2, download(&second, &out, None, None).unwrap());
        assert_eq!(b"aaa", &fs::read(out.join("exe/a.dll")).unwrap()[..]);
        assert_eq!(b"bbb", &fs::read(out.join("data/b.tga")).unwrap()[..]);
        assert!(!out.join("old").exists(), "unlisted leftovers are removed");
        assert!(!staging.exists());
        fs::remove_dir_all(&out).unwrap();
    }

    fn two_files(first: &str, second: &str) -> Vec<u8> {
        format!(
            "LOGH7-PATCH-1\nclient\t{}\nfile\t{first}\t{}\nfile\t{second}\t{}\n",
            sha256(b"client"),
            sha256(b"aaa"),
            sha256(b"bbb")
        )
        .into_bytes()
    }

    #[test]
    fn a_resumed_file_is_written_with_the_listed_spelling() {
        let out = std::env::temp_dir().join(format!("logh7-download-case-{}", std::process::id()));
        let staging = out.with_extension("partial");
        let _ = fs::remove_dir_all(&out);
        let _ = fs::remove_dir_all(&staging);
        // A previous attempt left `exe/A.dll`; the list now spells it `exe/a.dll`.
        fs::create_dir_all(staging.join("exe")).unwrap();
        fs::write(staging.join("exe/A.dll"), b"aaa").unwrap();
        let mut routes = vec![
            (
                "/patch/patch.manifest",
                two_files("exe/a.dll", "data/b.tga"),
            ),
            ("/patch/data/b.tga", b"bbb".to_vec()),
        ];
        if !cfg!(windows) {
            // A case-sensitive file system does not see the old name, so the file is fetched again.
            routes.push(("/patch/exe/a.dll", b"aaa".to_vec()));
        }
        assert_eq!(2, download(&serve(routes), &out, None, None).unwrap());
        let names: Vec<_> = fs::read_dir(out.join("exe"))
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        assert_eq!(vec!["a.dll".to_string()], names);
        fs::remove_dir_all(&out).unwrap();
    }

    #[test]
    fn an_old_directory_does_not_block_a_listed_file_of_the_same_name() {
        let out =
            std::env::temp_dir().join(format!("logh7-download-conflict-{}", std::process::id()));
        let staging = out.with_extension("partial");
        let _ = fs::remove_dir_all(&out);
        let _ = fs::remove_dir_all(&staging);
        // An earlier patch had `exe/item/` as a directory; this list has `exe/item` as a file.
        fs::create_dir_all(staging.join("exe/item")).unwrap();
        fs::write(staging.join("exe/item/old.bin"), b"old").unwrap();
        let base = serve(vec![
            ("/patch/patch.manifest", two_files("exe/item", "data/b.tga")),
            ("/patch/exe/item", b"aaa".to_vec()),
            ("/patch/data/b.tga", b"bbb".to_vec()),
        ]);
        assert_eq!(2, download(&base, &out, None, None).unwrap());
        assert_eq!(b"aaa", &fs::read(out.join("exe/item")).unwrap()[..]);
        fs::remove_dir_all(&out).unwrap();
    }
}
