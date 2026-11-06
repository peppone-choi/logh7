mod audio;
mod config;
mod download;
mod graphic_config;
mod patch;
mod signature;
use config::Config;
use std::{
    env, fs,
    path::PathBuf,
    process::{Command, ExitCode},
};

fn run() -> Result<i32, String> {
    let mut path = env::current_exe()
        .map_err(|_| "런처 경로를 찾지 못했습니다")?
        .with_file_name("launcher.ini");
    let mut check = false;
    let mut apply_patch: Option<PathBuf> = None;
    let mut revert_patch = false;
    let mut args = env::args_os().skip(1);
    while let Some(arg) = args.next() {
        match arg.to_str() {
            Some("--config") => {
                path = PathBuf::from(args.next().ok_or("--config 뒤에 파일 경로를 지정하세요")?)
            }
            Some("--check") => check = true,
            Some("--apply-patch") => {
                apply_patch = Some(PathBuf::from(
                    args.next()
                        .ok_or("--apply-patch 뒤에 패치 디렉터리를 지정하세요")?,
                ))
            }
            Some("--revert-patch") => revert_patch = true,
            Some("--download-patch") => {
                let url = args
                    .next()
                    .ok_or("--download-patch 뒤에 http 주소를 지정하세요")?;
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
                )?;
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
                    "logh7-launcher [--config launcher.ini] [--check | --apply-patch DIR | --revert-patch | --download-patch URL DIR [--expect-manifest SHA256] [--trust-key P256]]\n설정·오디오 출력 점검 후 원본 클라이언트를 실행합니다. --check는 실행 없이 점검합니다.\n--apply-patch는 패치 목록의 해시를 확인하고 원래 파일을 보관한 뒤 적용하며, --revert-patch는 바뀌지 않은 패치 파일만 되돌립니다."
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
    let path = path
        .canonicalize()
        .map_err(|_| "설정 파일을 찾지 못했습니다. launcher.ini 또는 --config 경로를 확인하세요")?;
    let text = fs::read_to_string(&path).map_err(|_| "설정 파일을 UTF-8로 읽지 못했습니다")?;
    let config = Config::parse(
        &text,
        path.parent().ok_or("설정 디렉터리를 찾지 못했습니다")?,
    )?;
    if let Some(patch) = apply_patch {
        let count = patch::apply(&config.client_root, &patch)?;
        println!("패치 파일 {count}개를 적용했습니다");
        return Ok(0);
    }
    if revert_patch {
        let count = patch::revert(&config.client_root)?;
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
