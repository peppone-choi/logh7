//! 패치 적용·되돌리기. 작성: Peppone Choi.
//!
//! 패치 디렉터리의 `patch.manifest`가 지원 클라이언트 해시와 설치할 파일 목록을 적는다.
//! ```text
//! LOGH7-PATCH-1
//! client	<exe/G7MTClient.exe SHA-256>
//! file	exe/dinput8.dll	<SHA-256>
//! ```
//! 적용은 모든 해시를 먼저 확인한 뒤 바꿀 원래 파일을 `logh7-patch-backup/`에 보관하고,
//! 설치 목록을 `logh7-patch.state`에 남긴다. 되돌리기는 설치한 파일이 그대로일 때만
//! 원래 파일을 복원하거나 새로 더한 파일을 지운다. 원본 실행 파일은 바꾸지 않는다.
use std::{
    fs,
    path::{Component, Path, PathBuf},
};

const MANIFEST: &str = "patch.manifest";
const STATE: &str = "logh7-patch.state";
const BACKUP: &str = "logh7-patch-backup";
const PENDING: &str = "pending";
const CLIENT: &str = "exe/G7MTClient.exe";

struct Entry {
    path: String,
    hash: String,
}

/// SHA-256 (FIPS 180-4), 외부 크레이트 없이 구현한다.
pub fn sha256(data: &[u8]) -> String {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    let mut message = data.to_vec();
    message.push(0x80);
    while message.len() % 64 != 56 {
        message.push(0);
    }
    message.extend_from_slice(&((data.len() as u64) * 8).to_be_bytes());
    for block in message.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes(block[i * 4..i * 4 + 4].try_into().unwrap());
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let mut v = h;
        for i in 0..64 {
            let s1 = v[4].rotate_right(6) ^ v[4].rotate_right(11) ^ v[4].rotate_right(25);
            let ch = (v[4] & v[5]) ^ (!v[4] & v[6]);
            let t1 = v[7]
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = v[0].rotate_right(2) ^ v[0].rotate_right(13) ^ v[0].rotate_right(22);
            let maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            let t2 = s0.wrapping_add(maj);
            v = [
                t1.wrapping_add(t2),
                v[0],
                v[1],
                v[2],
                v[3].wrapping_add(t1),
                v[4],
                v[5],
                v[6],
            ];
        }
        for i in 0..8 {
            h[i] = h[i].wrapping_add(v[i]);
        }
    }
    h.iter().map(|x| format!("{x:08x}")).collect()
}

fn hash_file(path: &Path) -> Result<String, String> {
    fs::read(path)
        .map(|data| sha256(&data))
        .map_err(|_| format!("파일을 읽지 못했습니다: {}", path.display()))
}

/// 패치 경로는 클라이언트 루트 아래 상대 경로만 허용하고 원본 실행 파일은 막는다.
fn target(root: &Path, relative: &str) -> Result<PathBuf, String> {
    let path = Path::new(relative);
    let plain = path.components().all(|c| matches!(c, Component::Normal(_)));
    if relative.is_empty() || !plain || relative.eq_ignore_ascii_case(CLIENT) {
        return Err(format!("허용하지 않는 패치 경로: {relative}"));
    }
    Ok(root.join(path))
}

fn manifest(patch: &Path) -> Result<(String, Vec<Entry>), String> {
    let text = fs::read_to_string(patch.join(MANIFEST))
        .map_err(|_| "patch.manifest를 UTF-8로 읽지 못했습니다")?;
    let mut lines = text.trim_start_matches('\u{feff}').lines();
    if lines.next().map(str::trim) != Some("LOGH7-PATCH-1") {
        return Err("지원하지 않는 패치 형식입니다".into());
    }
    let (mut client, mut files) = (None, Vec::new());
    for line in lines.map(str::trim).filter(|l| !l.is_empty()) {
        let fields: Vec<&str> = line.split('\t').collect();
        match fields.as_slice() {
            ["client", hash] if client.is_none() => client = Some(hash.to_ascii_lowercase()),
            ["file", path, hash] => files.push(Entry {
                path: path.replace('\\', "/"),
                hash: hash.to_ascii_lowercase(),
            }),
            _ => return Err(format!("패치 목록을 해석하지 못했습니다: {line}")),
        }
    }
    let client = client.ok_or("패치 목록에 client 해시가 없습니다")?;
    if files.is_empty() {
        return Err("패치 목록에 파일이 없습니다".into());
    }
    Ok((client, files))
}

pub fn apply(root: &Path, patch: &Path) -> Result<usize, String> {
    if root.join(STATE).exists() {
        return Err("이미 패치가 적용돼 있습니다. 먼저 --revert-patch로 되돌리세요".into());
    }
    let (client, files) = manifest(patch)?;
    if hash_file(&root.join(CLIENT))? != client {
        return Err("이 패치가 지원하지 않는 클라이언트입니다. 파일을 바꾸지 않았습니다".into());
    }
    for entry in &files {
        target(root, &entry.path)?;
        if hash_file(&patch.join(&entry.path))? != entry.hash {
            return Err(format!("패치 파일이 손상됐습니다: {}", entry.path));
        }
    }
    let mut state = String::from("LOGH7-PATCH-STATE-1\n");
    for entry in &files {
        if let Err(failure) = install(root, patch, entry, &mut state) {
            // 적용 도중 실패하면 그때까지 바꾼 파일(쓰다 만 파일 포함)을 바로 되돌린다.
            if !root.join(STATE).exists() {
                let _ = fs::remove_dir_all(root.join(BACKUP));
                return Err(format!("{failure}. 바꾼 파일은 없습니다"));
            }
            return Err(match revert(root) {
                Ok(_) => format!("{failure}. 바꾼 파일을 모두 원래대로 되돌렸습니다"),
                Err(again) => format!("{failure}. 자동 되돌리기도 실패했습니다: {again}"),
            });
        }
    }
    Ok(files.len())
}

/// 파일 하나를 설치한다. 순서: 원래 파일 보관 → 상태에 `pending` 표시와 함께 기록 → 복사 → `pending` 제거.
/// 복사 중 중단되면(전원 차단 등) 상태의 마지막 항목이 `pending`으로 남아, `revert`가 쓰다 만 그 파일도
/// 해시 확인 없이 원래대로 돌린다. 보관 중 중단되면 상태에 그 항목이 없고 대상 파일도 아직 그대로다.
fn install(root: &Path, patch: &Path, entry: &Entry, state: &mut String) -> Result<(), String> {
    let destination = target(root, &entry.path)?;
    let original = destination.exists();
    if original {
        let backup = root.join(BACKUP).join(&entry.path);
        fs::create_dir_all(backup.parent().unwrap()).map_err(|e| e.to_string())?;
        fs::copy(&destination, &backup)
            .map_err(|_| format!("원래 파일을 보관하지 못했습니다: {}", entry.path))?;
    } else if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    state.push_str(&format!(
        "{}\t{}\t{}\n",
        entry.path,
        entry.hash,
        u8::from(original)
    ));
    let record = |text: &str| {
        fs::write(root.join(STATE), text).map_err(|_| "패치 상태를 기록하지 못했습니다".to_string())
    };
    record(&format!("{state}{PENDING}\n"))?;
    fs::copy(patch.join(&entry.path), &destination)
        .map_err(|_| format!("패치 파일을 쓰지 못했습니다: {}", entry.path))?;
    record(state)
}
pub fn revert(root: &Path) -> Result<usize, String> {
    let text = fs::read_to_string(root.join(STATE)).map_err(|_| "적용된 패치가 없습니다")?;
    let mut lines = text.lines();
    if lines.next() != Some("LOGH7-PATCH-STATE-1") {
        return Err("패치 상태 형식이 다릅니다".into());
    }
    let lines: Vec<&str> = lines.filter(|l| !l.is_empty()).collect();
    // 마지막 줄이 `pending`이면 바로 앞 항목은 쓰다 만 파일일 수 있어 해시를 확인하지 않는다.
    let (lines, pending) = match lines.split_last() {
        Some((&PENDING, rest)) if !rest.is_empty() => (rest, true),
        _ => (&lines[..], false),
    };
    let mut entries = Vec::new();
    for (index, line) in lines.iter().enumerate() {
        let fields: Vec<&str> = line.split('\t').collect();
        let [path, hash, original] = fields.as_slice() else {
            return Err(format!("패치 상태를 해석하지 못했습니다: {line}"));
        };
        let destination = target(root, path)?;
        let interrupted = pending && index + 1 == lines.len();
        if !interrupted && hash_file(&destination)? != *hash {
            return Err(format!(
                "패치 뒤 바뀐 파일이 있어 되돌리지 않았습니다: {path}"
            ));
        }
        entries.push((path.to_string(), destination, *original == "1"));
    }
    for (path, destination, original) in &entries {
        if *original {
            fs::copy(root.join(BACKUP).join(path), destination)
                .map_err(|_| format!("원래 파일을 복원하지 못했습니다: {path}"))?;
        } else {
            match fs::remove_file(destination) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => {
                    return Err(format!("추가 파일을 지우지 못했습니다: {path}"));
                }
                _ => {}
            }
        }
    }
    let _ = fs::remove_dir_all(root.join(BACKUP));
    fs::remove_file(root.join(STATE)).map_err(|_| "패치 상태를 지우지 못했습니다")?;
    Ok(entries.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let path = std::env::temp_dir().join(format!("logh7-patch-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).unwrap();
        path
    }

    fn setup(name: &str) -> (PathBuf, PathBuf) {
        let base = temp(name);
        let (root, patch) = (base.join("client"), base.join("patch"));
        fs::create_dir_all(root.join("exe")).unwrap();
        fs::create_dir_all(patch.join("exe")).unwrap();
        fs::create_dir_all(patch.join("data")).unwrap();
        fs::write(root.join(CLIENT), b"client").unwrap();
        fs::write(root.join("exe/String.txt"), b"original").unwrap();
        fs::write(patch.join("exe/String.txt"), b"korean").unwrap();
        fs::write(patch.join("exe/dinput8.dll"), b"dll").unwrap();
        let manifest = format!(
            "LOGH7-PATCH-1\nclient\t{}\nfile\texe/String.txt\t{}\nfile\texe/dinput8.dll\t{}\n",
            sha256(b"client"),
            sha256(b"korean"),
            sha256(b"dll")
        );
        fs::write(patch.join(MANIFEST), manifest).unwrap();
        (root, patch)
    }

    #[test]
    fn sha256_matches_known_vectors() {
        assert_eq!(
            sha256(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            sha256(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(
            sha256(&[b'a'; 1000]),
            "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3"
        );
    }

    #[test]
    fn apply_backs_up_and_revert_restores_exactly() {
        let (root, patch) = setup("roundtrip");
        assert_eq!(2, apply(&root, &patch).unwrap());
        assert_eq!(
            b"korean",
            &fs::read(root.join("exe/String.txt")).unwrap()[..]
        );
        assert!(apply(&root, &patch).is_err(), "a second apply is refused");
        assert_eq!(2, revert(&root).unwrap());
        assert_eq!(
            b"original",
            &fs::read(root.join("exe/String.txt")).unwrap()[..]
        );
        assert!(!root.join("exe/dinput8.dll").exists());
        assert!(!root.join(STATE).exists() && !root.join(BACKUP).exists());
    }

    #[test]
    fn wrong_client_damaged_patch_unsafe_path_and_changed_file_are_refused() {
        let (root, patch) = setup("refuse");
        fs::write(root.join(CLIENT), b"other").unwrap();
        assert!(apply(&root, &patch).is_err());
        assert_eq!(
            b"original",
            &fs::read(root.join("exe/String.txt")).unwrap()[..]
        );
        fs::write(root.join(CLIENT), b"client").unwrap();
        fs::write(patch.join("exe/dinput8.dll"), b"tampered").unwrap();
        assert!(apply(&root, &patch).is_err());
        assert!(target(&root, "../outside.txt").is_err() && target(&root, CLIENT).is_err());
        fs::write(patch.join("exe/dinput8.dll"), b"dll").unwrap();
        apply(&root, &patch).unwrap();
        fs::write(root.join("exe/String.txt"), b"edited").unwrap();
        assert!(
            revert(&root).is_err(),
            "a file changed after patching is not overwritten"
        );
        assert_eq!(
            b"edited",
            &fs::read(root.join("exe/String.txt")).unwrap()[..]
        );
    }

    #[test]
    fn a_failure_while_applying_restores_the_files_already_changed() {
        let (root, patch) = setup("midway");
        // The second target is occupied by a directory, so it cannot be backed up or written.
        fs::create_dir_all(root.join("exe/dinput8.dll")).unwrap();
        let failure = apply(&root, &patch).unwrap_err();
        assert!(failure.contains("되돌렸습니다"), "{failure}");
        // A failure on the very first file changes nothing and leaves no backup behind.
        let (first, patch_first) = setup("first");
        fs::remove_file(first.join("exe/String.txt")).unwrap();
        fs::create_dir_all(first.join("exe/String.txt")).unwrap();
        let failure = apply(&first, &patch_first).unwrap_err();
        assert!(failure.contains("바꾼 파일은 없습니다"), "{failure}");
        assert!(!first.join(STATE).exists() && !first.join(BACKUP).exists());
        assert_eq!(
            b"original",
            &fs::read(root.join("exe/String.txt")).unwrap()[..]
        );
        assert!(!root.join(STATE).exists() && !root.join(BACKUP).exists());
        // Once the obstacle is gone the same patch applies normally.
        fs::remove_dir(root.join("exe/dinput8.dll")).unwrap();
        assert_eq!(2, apply(&root, &patch).unwrap());
    }

    #[test]
    fn an_interrupted_copy_is_reverted_without_a_hash_match() {
        let (root, patch) = setup("crash");
        apply(&root, &patch).unwrap();
        // As if power was lost while the last file was being written.
        fs::write(root.join("exe/dinput8.dll"), b"d").unwrap();
        let mut state = fs::read_to_string(root.join(STATE)).unwrap();
        state.push_str("pending\n");
        fs::write(root.join(STATE), &state).unwrap();
        assert_eq!(2, revert(&root).unwrap());
        assert_eq!(
            b"original",
            &fs::read(root.join("exe/String.txt")).unwrap()[..]
        );
        assert!(!root.join("exe/dinput8.dll").exists());
        // Without the marker the same half-written file is protected as a user change.
        apply(&root, &patch).unwrap();
        fs::write(root.join("exe/dinput8.dll"), b"d").unwrap();
        assert!(revert(&root).is_err());
    }
}
