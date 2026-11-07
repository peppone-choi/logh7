use crate::config::Config;
use std::{
    fs::{self, OpenOptions},
    io::{self, BufRead, Write},
    path::Path,
};

pub fn configure(
    path: &Path,
    input: &mut impl BufRead,
    output: &mut impl Write,
) -> Result<(), String> {
    let original = match fs::read_to_string(path) {
        Ok(text) => Some(text),
        Err(error) if error.kind() == io::ErrorKind::NotFound => None,
        Err(_) => return Err("기존 설정을 읽을 수 없습니다".into()),
    };
    let path = if original.is_some() {
        path.canonicalize()
            .map_err(|_| "설정 경로를 열 수 없습니다")?
    } else {
        path.to_owned()
    };
    let directory = path
        .parent()
        .filter(|dir| !dir.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let config = original
        .as_deref()
        .map(|text| Config::parse(text, directory))
        .transpose()?;
    let host = ask(
        input,
        output,
        "로그인 서버 IPv4 주소",
        config.as_ref().map(|c| c.host.to_string()).as_deref(),
    )?;
    let port = ask(
        input,
        output,
        "로그인 서버 포트",
        Some(&config.as_ref().map_or(47900, |c| c.port).to_string()),
    )?;
    let updated = match &original {
        Some(text) => update_endpoint(text, &host, &port),
        None => {
            let root = ask(
                input,
                output,
                "게임 설치 폴더 (exe와 data가 있는 폴더)",
                Some("."),
            )?;
            let account = ask(input, output, "등록한 게임 계정", None)?;
            format!(
                "client_root={root}\r\nhost={host}\r\nport={port}\r\naccount={account}\r\nlogin_ui=true\r\n"
            )
        }
    };
    let parsed = Config::parse(&updated, directory)?;
    if original.is_none() {
        parsed.executable()?;
    }
    if original.as_deref() != Some(&updated) {
        save(&path, original.as_deref(), &updated)?;
    }
    writeln!(output, "설정 저장 완료 · {}:{}", parsed.host, parsed.port)
        .map_err(|_| "설정 완료 메시지를 표시할 수 없습니다".into())
}

fn ask(
    input: &mut impl BufRead,
    output: &mut impl Write,
    label: &str,
    default: Option<&str>,
) -> Result<String, String> {
    match default {
        Some(value) => write!(output, "{label} [{value}]: "),
        None => write!(output, "{label}: "),
    }
    .and_then(|()| output.flush())
    .map_err(|_| "설정 질문을 표시할 수 없습니다")?;
    let mut line = String::new();
    if input
        .read_line(&mut line)
        .map_err(|_| "입력을 읽을 수 없습니다")?
        == 0
    {
        return Err("설정 입력이 취소되었습니다".into());
    }
    let value = line.trim();
    Ok(if value.is_empty() {
        default.unwrap_or("")
    } else {
        value
    }
    .to_owned())
}

fn update_endpoint(text: &str, host: &str, port: &str) -> String {
    text.split_inclusive('\n')
        .map(|line| {
            let Some((key, old)) = line.split_once('=') else {
                return line.to_owned();
            };
            let value = match key.trim_start_matches('\u{feff}').trim() {
                "host" => host,
                "port" => port,
                _ => return line.to_owned(),
            };
            let prefix = &old[..old.len() - old.trim_start().len()];
            let suffix = &old[old.trim_end().len()..];
            format!("{key}={prefix}{value}{suffix}")
        })
        .collect()
}

fn save(path: &Path, original: Option<&str>, updated: &str) -> Result<(), String> {
    let staging = path.with_extension(format!("setup-{}.tmp", std::process::id()));
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&staging)
        .map_err(|_| "설정 임시 파일을 만들 수 없습니다")?;
    let written = file
        .write_all(updated.as_bytes())
        .and_then(|()| file.sync_all());
    drop(file);
    let result = written
        .map_err(|_| "설정 내용을 쓸 수 없습니다")
        .and_then(|()| {
            let unchanged = match fs::read_to_string(path) {
                Ok(current) => Some(current.as_str()) == original,
                Err(error) => original.is_none() && error.kind() == io::ErrorKind::NotFound,
            };
            if !unchanged {
                return Err("다른 곳에서 설정이 바뀌었습니다. --setup을 다시 실행하세요");
            }
            fs::rename(&staging, path).map_err(|_| "설정 파일을 바꿀 수 없습니다")
        });
    if result.is_err() {
        let _ = fs::remove_file(&staging);
    }
    result.map_err(str::to_owned)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_setup_saves_a_login_ui_profile_without_credentials() {
        let directory =
            std::env::temp_dir().join(format!("logh7-first-setup-{}", std::process::id()));
        fs::create_dir(&directory).unwrap();
        fs::create_dir(directory.join("data")).unwrap();
        fs::create_dir(directory.join("exe")).unwrap();
        fs::write(directory.join("exe/G7MTClient.exe"), []).unwrap();
        let path = directory.join("launcher.ini");
        configure(
            &path,
            &mut &b"127.0.0.1\n50010\n\nnewpilot\n"[..],
            &mut Vec::new(),
        )
        .unwrap();
        let text = fs::read_to_string(&path).unwrap();
        assert!(!text.contains("credential"));
        assert_eq!(
            Config::parse(&text, &directory).unwrap().arguments(),
            ["127.0.0.1", "50010", "newpilot"]
        );
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn setup_only_changes_the_endpoint_and_never_saves_invalid_or_cancelled_input() {
        let directory =
            std::env::temp_dir().join(format!("logh7-update-setup-{}", std::process::id()));
        fs::create_dir(&directory).unwrap();
        let path = directory.join("launcher.ini");
        let original = "\u{feff}host = 127.0.0.1\r\n# port=1234\r\nport=50010\r\nclient_root=client copy\r\naccount=pilot\r\nresolution=1920x1080";
        fs::write(&path, original).unwrap();
        for data in ["0.0.0.0\n50011\n", "127.0.0.2\n0\n", "127.0.0.2\n"] {
            assert!(configure(&path, &mut data.as_bytes(), &mut Vec::new()).is_err());
            assert_eq!(fs::read_to_string(&path).unwrap(), original);
        }
        configure(&path, &mut &b"127.0.0.2\n50011\n"[..], &mut Vec::new()).unwrap();
        assert_eq!(
            fs::read_to_string(&path).unwrap(),
            original
                .replace("127.0.0.1", "127.0.0.2")
                .replace("50010", "50011")
        );
        fs::remove_dir_all(directory).unwrap();
    }
}
