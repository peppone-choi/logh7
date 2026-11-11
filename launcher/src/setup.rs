use crate::config::{Config, parse_host, parse_port};
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
    let saved = original.as_deref().unwrap_or_default();
    let saved_host = endpoint_value(saved, "host")
        .and_then(|value| parse_host(value).ok())
        .map(|host| host.to_string());
    let saved_port = endpoint_value(saved, "port")
        .and_then(|value| parse_port(value).ok())
        .map(|port| port.to_string());
    let host = ask(
        input,
        output,
        "로그인 서버 IPv4 주소",
        saved_host.as_deref(),
    )?;
    let port = ask(
        input,
        output,
        "로그인 서버 포트",
        if original.is_none() {
            Some("47900")
        } else {
            saved_port.as_deref()
        },
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
        .and_then(|()| {
            writeln!(
                output,
                "로그인 서버를 확인하려면 --check-connection을 실행하세요."
            )
        })
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

fn endpoint_value<'a>(text: &'a str, name: &str) -> Option<&'a str> {
    text.trim_start_matches('\u{feff}')
        .lines()
        .find_map(|line| {
            let (key, value) = line.split_once('=')?;
            (key.trim() == name).then(|| value.trim())
        })
}

fn update_endpoint(text: &str, host: &str, port: &str) -> String {
    let mut updated: String = text
        .split_inclusive('\n')
        .map(|line| {
            let body = line
                .strip_suffix("\r\n")
                .or_else(|| line.strip_suffix('\n'))
                .unwrap_or(line);
            let Some((key, old)) = body.split_once('=') else {
                return line.to_owned();
            };
            let value = match key.trim_start_matches('\u{feff}').trim() {
                "host" => host,
                "port" => port,
                _ => return line.to_owned(),
            };
            let trimmed_start = old.trim_start();
            let prefix = &old[..old.len() - trimmed_start.len()];
            let suffix = &trimmed_start[trimmed_start.trim_end().len()..];
            format!("{key}={prefix}{value}{suffix}{}", &line[body.len()..])
        })
        .collect();
    let newline = if text.contains("\r\n") { "\r\n" } else { "\n" };
    for (name, value) in [("host", host), ("port", port)] {
        if endpoint_value(text, name).is_none() {
            if !updated.is_empty() && !updated.ends_with('\n') {
                updated.push_str(newline);
            }
            updated.push_str(&format!("{name}={value}{newline}"));
        }
    }
    updated
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

    #[test]
    fn setup_recovers_invalid_empty_and_missing_endpoint_fields() {
        let directory =
            std::env::temp_dir().join(format!("logh7-repair-endpoint-{}", std::process::id()));
        fs::create_dir(&directory).unwrap();
        let path = directory.join("launcher.ini");
        let other = "# port=1234\r\nclient_root=client copy\r\naccount=pilot\r\nlogin_ui=true";
        for (saved, repaired, input) in [
            (
                "host=0.0.0.0\r\nport=65536\r\n",
                "host=127.0.0.2\r\nport=50011\r\n",
                "127.0.0.2\n50011\n",
            ),
            (
                "host= \r\nport=50011\r\n",
                "host= 127.0.0.2\r\nport=50011\r\n",
                "127.0.0.2\n\n",
            ),
            ("host=127.0.0.2\r\n", "host=127.0.0.2\r\n", "\n50011\n"),
        ] {
            let original = format!("\u{feff}{saved}{other}");
            fs::write(&path, &original).unwrap();
            assert!(configure(&path, &mut &b"127.0.0.2\n0\n"[..], &mut Vec::new()).is_err());
            assert_eq!(fs::read_to_string(&path).unwrap(), original);
            configure(&path, &mut input.as_bytes(), &mut Vec::new()).unwrap();
            let text = fs::read_to_string(&path).unwrap();
            let mut expected = format!("\u{feff}{repaired}{other}");
            if !saved.contains("port=") {
                expected.push_str("\r\nport=50011\r\n");
            }
            assert_eq!(text, expected);
            assert_eq!(
                Config::parse(&text, &directory).unwrap().arguments(),
                ["127.0.0.2", "50011", "pilot"]
            );
        }
        fs::remove_dir_all(directory).unwrap();
    }
}
