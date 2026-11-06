//! 클라이언트 화면 크기 설정. 작성: Peppone Choi.
//!
//! 원본 `G7MTClient.exe`는 클라이언트 루트(exe 폴더의 상위)의 `GraphicConfig.txt`를 읽는다
//! (EXE 문자열 `%s/../GraphicConfig.txt`, 없으면 기본값으로 새로 쓴다). 형식은 머리 세 줄 뒤
//! "키 줄, 값 줄"이 번갈아 나오는 CRLF 텍스트다. 런처는 `ScreenWidth`·`ScreenHeight` 값 줄만
//! 바꾸고 나머지 줄·순서·줄바꿈은 그대로 둔다. 원본은 이 크기로 렌더 크기와 창 크기를 맞추고
//! HUD를 화면 가장자리에 다시 배치한다(1280×720 실기 확인, docs/client/remaster-plan.md).
use std::{fs, path::Path};

pub const FILE: &str = "GraphicConfig.txt";

/// 파일이 없을 때 쓰는 기본 내용. 원본이 처음 실행 때 만드는 파일과 같은 키·순서다.
const DEFAULT: &[&str] = &[
    "EasyGraphicConfigFile",
    "PleaseSetLevels0-3",
    "(*//",
    "UnitModelLevel",
    "2",
    "StarsModelLevel",
    "2",
    "ModelTextureLevel",
    "2",
    "BGTextureLevel",
    "2",
    "EffectTextureLevel",
    "2",
    "ScreenWidth",
    "1024",
    "ScreenHeight",
    "768",
    "ScreenRefreshRate",
    "0",
    "ScreenBit",
    "0",
    "EffectLV",
    "0",
    "BGM Volume",
    "100",
    "SE Volume",
    "100",
    "StrategyBGM",
    "3",
    "TacticsBGM",
    "5",
];

/// `WxH` 문자열을 검사한다. 원본 창·장치가 다룰 수 있는 범위로 제한한다.
pub fn parse_resolution(text: &str) -> Result<(u32, u32), String> {
    let invalid =
        || format!("resolution은 1024x768 같은 형식입니다(가로 800~7680, 세로 600~4320): {text}");
    let (w, h) = text.trim().split_once(['x', 'X']).ok_or_else(invalid)?;
    let width: u32 = w.trim().parse().map_err(|_| invalid())?;
    let height: u32 = h.trim().parse().map_err(|_| invalid())?;
    if !(800..=7680).contains(&width) || !(600..=4320).contains(&height) {
        return Err(invalid());
    }
    Ok((width, height))
}

/// 클라이언트 루트의 설정 파일에 화면 크기를 쓴다. 바뀌었으면 true.
pub fn apply(client_root: &Path, width: u32, height: u32) -> Result<bool, String> {
    let path = client_root.join(FILE);
    let original = match fs::read(&path) {
        Ok(bytes) => Some(bytes),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
        Err(_) => return Err(format!("{FILE}을 읽지 못했습니다")),
    };
    let text = match &original {
        Some(bytes) => String::from_utf8(bytes.clone())
            .map_err(|_| format!("{FILE}이 텍스트 형식이 아닙니다"))?,
        None => DEFAULT.iter().map(|line| format!("{line}\r\n")).collect(),
    };
    let crlf = text.contains("\r\n");
    let mut lines: Vec<String> = text.lines().map(str::to_string).collect();
    for (key, value) in [("ScreenWidth", width), ("ScreenHeight", height)] {
        let index = lines
            .iter()
            .position(|line| line.trim() == key)
            .filter(|i| i + 1 < lines.len())
            .ok_or_else(|| format!("{FILE}에 {key} 항목이 없습니다"))?;
        lines[index + 1] = value.to_string();
    }
    let newline = if crlf || original.is_none() {
        "\r\n"
    } else {
        "\n"
    };
    let next: String = lines
        .iter()
        .map(|line| format!("{line}{newline}"))
        .collect();
    if original.as_deref() == Some(next.as_bytes()) {
        return Ok(false);
    }
    let staging = client_root.join(format!("{FILE}.next"));
    fs::write(&staging, next.as_bytes()).map_err(|_| format!("{FILE}을 쓰지 못했습니다"))?;
    fs::rename(&staging, &path).map_err(|_| format!("{FILE}을 바꾸지 못했습니다"))?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn root(name: &str) -> std::path::PathBuf {
        let path =
            std::env::temp_dir().join(format!("logh7-graphic-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).unwrap();
        path
    }

    #[test]
    fn resolutions_are_checked() {
        assert_eq!((1920, 1080), parse_resolution("1920x1080").unwrap());
        assert_eq!((3840, 2160), parse_resolution(" 3840 X 2160 ").unwrap());
        for bad in [
            "1920",
            "1920x",
            "x1080",
            "640x480",
            "8000x4320",
            "1920x1080x1",
            "-1x768",
        ] {
            assert!(parse_resolution(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn a_missing_file_is_created_with_the_native_defaults() {
        let dir = root("missing");
        assert!(apply(&dir, 1920, 1080).unwrap());
        let text = fs::read_to_string(dir.join(FILE)).unwrap();
        assert!(text.starts_with("EasyGraphicConfigFile\r\nPleaseSetLevels0-3\r\n(*//\r\n"));
        assert!(text.contains("ScreenWidth\r\n1920\r\nScreenHeight\r\n1080\r\n"));
        assert!(text.ends_with("TacticsBGM\r\n5\r\n"));
        assert!(
            !apply(&dir, 1920, 1080).unwrap(),
            "unchanged on the second run"
        );
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn only_the_two_size_values_change_in_an_existing_file() {
        let dir = root("existing");
        let before = DEFAULT
            .iter()
            .map(|l| match *l {
                "100" => "40".to_string(),
                other => other.to_string(),
            })
            .map(|l| format!("{l}\r\n"))
            .collect::<String>();
        fs::write(dir.join(FILE), &before).unwrap();
        assert!(apply(&dir, 1280, 720).unwrap());
        let after = fs::read_to_string(dir.join(FILE)).unwrap();
        assert_eq!(
            before
                .replace("ScreenWidth\r\n1024", "ScreenWidth\r\n1280")
                .replace("ScreenHeight\r\n768", "ScreenHeight\r\n720"),
            after,
            "other settings such as volume stay as the player set them"
        );
        assert!(!dir.join(format!("{FILE}.next")).exists());
        fs::write(dir.join(FILE), "EasyGraphicConfigFile\r\nScreenWidth\r\n").unwrap();
        assert!(
            apply(&dir, 1280, 720).is_err(),
            "a damaged file is not rewritten"
        );
        fs::remove_dir_all(&dir).unwrap();
    }
}
