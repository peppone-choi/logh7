mod audio;
mod config;
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
    let mut args = env::args_os().skip(1);
    while let Some(arg) = args.next() {
        match arg.to_str() {
            Some("--config") => {
                path = PathBuf::from(args.next().ok_or("--config 뒤에 파일 경로를 지정하세요")?)
            }
            Some("--check") => check = true,
            Some("--help") => {
                println!(
                    "logh7-launcher [--config launcher.ini] [--check]\n설정·오디오 출력 점검 후 원본 클라이언트를 실행합니다. --check는 실행 없이 점검합니다."
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
    let exe = config.executable()?;
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
