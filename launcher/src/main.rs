mod audio;
mod config;
mod connection;
mod download;
mod graphic_config;
mod patch;
mod setup;
mod signature;
use config::Config;
use std::{
    env, fs,
    io::{self, IsTerminal},
    net::SocketAddrV4,
    path::PathBuf,
    process::{Command, ExitCode},
};

const COMMAND_CONFLICT: &str = "설정·점검·패치 명령은 한 번에 하나만 실행하세요";

fn is_operation(option: Option<&str>) -> bool {
    matches!(
        option,
        Some(
            "--setup"
                | "--check"
                | "--check-connection"
                | "--apply-patch"
                | "--revert-patch"
                | "--download-patch"
        )
    )
}

fn run() -> Result<i32, String> {
    let mut path = env::current_exe()
        .map_err(|_| "런처 경로를 찾지 못했습니다")?
        .with_file_name("launcher.ini");
    let mut check = false;
    let mut check_connection = false;
    let mut configure = false;
    let mut apply_patch: Option<PathBuf> = None;
    let mut apply_trusted = None;
    let mut revert_patch = false;
    let mut operation_selected = false;
    let mut args = env::args_os().skip(1);
    while let Some(arg) = args.next() {
        if is_operation(arg.to_str()) {
            if operation_selected {
                return Err(COMMAND_CONFLICT.into());
            }
            operation_selected = true;
        }
        match arg.to_str() {
            Some("--config") => {
                path = PathBuf::from(args.next().ok_or("--config 뒤에 파일 경로를 지정하세요")?)
            }
            Some("--check") => check = true,
            Some("--check-connection") => check_connection = true,
            Some("--setup") => configure = true,
            Some("--apply-patch") => {
                apply_patch = Some(PathBuf::from(
                    args.next()
                        .ok_or("--apply-patch 뒤에 패치 디렉터리를 지정하세요")?,
                ))
            }
            Some("--revert-patch") => revert_patch = true,
            Some("--trust-key") => {
                if apply_trusted.is_some() {
                    return Err("--trust-key는 한 번만 지정하세요".into());
                }
                apply_trusted = Some(
                    args.next()
                        .and_then(|value| value.into_string().ok())
                        .filter(|key| signature::valid_key(key))
                        .ok_or("--trust-key 뒤에 128자리 공개키를 지정하세요")?,
                );
            }
            Some("--download-patch") => {
                if apply_trusted.is_some() {
                    return Err(
                        "--download-patch의 --trust-key는 받을 디렉터리 뒤에 지정하세요".into(),
                    );
                }
                let url = args
                    .next()
                    .ok_or("--download-patch 뒤에 HTTPS 또는 HTTP 주소를 지정하세요")?;
                let out = args
                    .next()
                    .ok_or("--download-patch 주소 뒤에 받을 디렉터리를 지정하세요")?;
                let url = url
                    .to_str()
                    .ok_or("패치 주소는 ASCII여야 합니다")?
                    .to_string();
                // Optional anchors after the directory: --expect-manifest <SHA-256>, --trust-key <P-256 X||Y>.
                let (mut expected, mut trusted) = (None, None);
                while let Some(flag) = args.next() {
                    if is_operation(flag.to_str()) {
                        return Err(COMMAND_CONFLICT.into());
                    }
                    let value = args.next().and_then(|v| v.to_str().map(str::to_string));
                    match flag.to_str() {
                        Some("--expect-manifest") => {
                            expected = Some(
                                value
                                    .filter(|v| {
                                        v.len() == 64 && v.bytes().all(|b| b.is_ascii_hexdigit())
                                    })
                                    .ok_or("--expect-manifest 뒤에 64자리 SHA-256을 지정하세요")?,
                            )
                        }
                        Some("--trust-key") => {
                            trusted = Some(
                                value
                                    .filter(|v| signature::valid_key(v))
                                    .ok_or("--trust-key 뒤에 128자리 공개키를 지정하세요")?,
                            )
                        }
                        _ => {
                            return Err("--download-patch 뒤에는 --expect-manifest·--trust-key만 올 수 있습니다".into());
                        }
                    }
                }
                let count = download::download(
                    &url,
                    &PathBuf::from(out),
                    expected.as_deref(),
                    trusted.as_deref(),
                )
                .map_err(|error| format!("패치 내려받기: {error}"))?;
                println!(
                    "패치 파일 {count}개를 받아 해시를 확인했습니다{}",
                    match (expected.is_some(), trusted.is_some()) {
                        (_, true) => " (목록 서명 일치)",
                        (true, false) => " (목록 고정값 일치)",
                        (false, false) => " (목록 고정값·서명 없음)",
                    }
                );
                return Ok(0);
            }
            Some("--help") => {
                println!(
                    "logh7-launcher [--config launcher.ini] [--setup | --check | --check-connection | --apply-patch DIR [--trust-key P256] | --revert-patch | --download-patch URL DIR [--expect-manifest SHA256] [--trust-key P256]]\n--setup은 서버 주소와 포트를 설정 파일에 저장합니다. --check는 로컬 설정을 점검합니다.\n--check-connection은 게임을 실행하거나 인증하지 않고 로그인 서버의 응답을 확인합니다.\n--apply-patch는 파일 해시와 지정한 공개키의 목록 서명을 확인하고 원래 파일을 보관한 뒤 적용합니다. --revert-patch는 바뀌지 않은 패치 파일만 되돌립니다."
                );
                return Ok(0);
            }
            Some("--version") => {
                println!("logh7-launcher {}", env!("CARGO_PKG_VERSION"));
                return Ok(0);
            }
            _ => return Err("지원하지 않는 옵션입니다. --help를 확인하세요".into()),
        }
    }
    if apply_trusted.is_some() && apply_patch.is_none() {
        return Err("--trust-key는 --apply-patch 또는 --download-patch와 함께 지정하세요".into());
    }
    let launch = !operation_selected;
    if configure || (launch && !path.try_exists().unwrap_or(true) && io::stdin().is_terminal()) {
        setup::configure(&path, &mut io::stdin().lock(), &mut io::stdout().lock())?;
        if configure {
            return Ok(0);
        }
    }
    let path = path
        .canonicalize()
        .map_err(|_| "설정 파일을 열 수 없습니다. 최초 설정은 --setup으로 시작하세요")?;
    let text = fs::read_to_string(&path).map_err(|_| "설정 파일을 UTF-8로 읽지 못했습니다")?;
    let config = Config::parse(
        &text,
        path.parent().ok_or("설정 디렉터리를 찾지 못했습니다")?,
    )
    .map_err(|error| {
        if apply_patch.is_some() || revert_patch {
            "패치 작업에 필요한 런처 설정이 올바르지 않습니다. --check로 확인하세요".into()
        } else {
            error
        }
    })?;
    if check_connection {
        let address = SocketAddrV4::new(config.host, config.port);
        println!("확인할 로그인 서버 {address}");
        connection::check(address.into())?;
        println!("LOGH7 로그인 서버가 응답합니다. 게임 창에서 로그인하세요.");
        return Ok(0);
    }
    if let Some(patch) = apply_patch {
        let count = patch::apply(&config.client_root, &patch, apply_trusted.as_deref())
            .map_err(|error| format!("패치 적용: {error}"))?;
        println!("패치 파일 {count}개를 적용했습니다");
        return Ok(0);
    }
    if revert_patch {
        let count = patch::revert(&config.client_root)
            .map_err(|error| format!("패치 되돌리기: {error}"))?;
        println!("패치 파일 {count}개를 되돌렸습니다");
        return Ok(0);
    }
    let exe = config.executable()?;
    if let Some((width, height)) = config.resolution {
        // --check only reports; a real launch writes the client's own screen-size setting.
        if check {
            println!("화면 크기 설정: {width}×{height} (실행할 때 GraphicConfig.txt에 씁니다)");
        } else if graphic_config::apply(&config.client_root, width, height)? {
            println!("화면 크기를 {width}×{height}로 설정했습니다");
        }
    }
    audio::check()?;
    println!(
        "접속 대상: {}:{} · 활성 오디오 출력 확인",
        config.host, config.port
    );
    if check {
        println!("실행 준비 완료");
        return Ok(0);
    }
    let mut child = Command::new(&exe)
        .args(config.arguments())
        .env("LOGH7_UI_SCALE", &config.ui_scale)
        .current_dir(exe.parent().unwrap())
        .spawn()
        .map_err(|_| "게임 프로세스를 실행하지 못했습니다")?;
    println!("게임 PID: {}", child.id());
    child
        .wait()
        .map(|status| status.code().unwrap_or(1))
        .map_err(|_| "게임 종료 상태를 읽지 못했습니다".into())
}

fn main() -> ExitCode {
    match run() {
        Ok(code) => {
            if code != 0 {
                eprintln!("게임 종료 코드: {code}");
            }
            ExitCode::from(if code == 0 { 0 } else { 1 })
        }
        Err(error) => {
            eprintln!("실행 실패: {error}");
            ExitCode::FAILURE
        }
    }
}
