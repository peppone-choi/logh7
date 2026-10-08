use std::{
    collections::BTreeMap,
    net::Ipv4Addr,
    path::{Path, PathBuf},
};

#[derive(Debug)]
pub struct Config {
    pub client_root: PathBuf,
    pub host: Ipv4Addr,
    pub port: u16,
    pub account: String,
    pub session: u16,
    pub credential: String,
    pub login_ui: bool,
}

impl Config {
    pub fn parse(text: &str, directory: &Path) -> Result<Self, String> {
        let mut values = BTreeMap::new();
        for (index, line) in text.trim_start_matches('\u{feff}').lines().enumerate() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let (key, value) = line
                .split_once('=')
                .ok_or_else(|| format!("설정 {}행에 =가 없습니다", index + 1))?;
            let key = key.trim();
            if ![
                "client_root",
                "host",
                "port",
                "account",
                "session",
                "credential",
                "login_ui",
            ]
            .contains(&key)
            {
                return Err(format!("알 수 없는 설정 키: {key}"));
            }
            if values.insert(key, value.trim()).is_some() {
                return Err(format!("중복 설정 키: {key}"));
            }
        }
        let required = |key| {
            values
                .get(key)
                .copied()
                .filter(|v| !v.is_empty())
                .ok_or_else(|| format!("필수 설정 누락: {key}"))
        };
        let host: Ipv4Addr = required("host")?
            .parse()
            .map_err(|_| "host에는 IPv4 주소가 필요합니다")?;
        if host.is_unspecified() || host.is_multicast() || host.is_broadcast() {
            return Err("접속 가능한 IPv4 주소를 지정하세요".into());
        }
        let port = required("port")?
            .parse::<u16>()
            .ok()
            .filter(|p| *p > 0)
            .ok_or("port 범위는 1~65535입니다")?;
        let session = values
            .get("session")
            .unwrap_or(&"1")
            .parse::<u16>()
            .ok()
            .filter(|p| *p > 0)
            .ok_or("session 범위는 1~65535입니다")?;
        let account = required("account")?.to_owned();
        let credential = values.get("credential").unwrap_or(&"dummy").to_string();
        for (key, value, limit) in [("account", &account, 30), ("credential", &credential, 10)] {
            if value.is_empty()
                || value.len() > limit
                || !value
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"_-.@".contains(&b))
            {
                return Err(format!(
                    "{key}: 영문·숫자·_-.@ 문자 1~{limit}자를 사용하세요"
                ));
            }
        }
        let login_ui = match values.get("login_ui").copied().unwrap_or("false") {
            "true" => true,
            "false" => false,
            _ => return Err("login_ui는 true 또는 false입니다".into()),
        };
        Ok(Self {
            client_root: directory.join(required("client_root")?),
            host,
            port,
            account,
            session,
            credential,
            login_ui,
        })
    }

    pub fn arguments(&self) -> Vec<String> {
        let mut args = vec![
            self.host.to_string(),
            self.port.to_string(),
            self.account.clone(),
        ];
        if !self.login_ui {
            args.extend([self.session.to_string(), self.credential.clone()]);
        }
        args
    }

    pub fn executable(&self) -> Result<PathBuf, String> {
        let root = self
            .client_root
            .canonicalize()
            .map_err(|_| "client_root 경로가 없습니다")?;
        if !root.join("data").is_dir() {
            return Err("client_root에 data 디렉터리가 없습니다".into());
        }
        let exe = root.join("exe/G7MTClient.exe");
        if !exe.is_file() {
            return Err("client_root에 exe/G7MTClient.exe가 없습니다".into());
        }
        Ok(exe)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const BASE: &str = "client_root=client copy\nhost=127.0.0.1\nport=47900\naccount=ginei00";
    #[test]
    fn config_uses_config_directory_and_exact_client_arguments() {
        let config = Config::parse(BASE, Path::new("config")).unwrap();
        assert_eq!(config.client_root, Path::new("config").join("client copy"));
        assert_eq!(
            config.arguments(),
            ["127.0.0.1", "47900", "ginei00", "1", "dummy"]
        );
        let config =
            Config::parse(&format!("\u{feff}{BASE}\nlogin_ui=true\n"), Path::new(".")).unwrap();
        assert_eq!(config.arguments(), ["127.0.0.1", "47900", "ginei00"]);
    }
    #[test]
    fn rejects_ambiguous_or_unrepresentable_configuration_without_echoing_secrets() {
        for suffix in [
            "\nport=12",
            "\nunknown=value",
            "\ncredential=bad password",
            "\ncredential=verylongpassword",
            "\nlogin_ui=yes",
        ] {
            assert!(Config::parse(&format!("{BASE}{suffix}"), Path::new(".")).is_err());
        }
        for port in ["0", "65536", "-1", "abc"] {
            assert!(Config::parse(&BASE.replace("47900", port), Path::new(".")).is_err());
        }
        assert!(Config::parse(&BASE.replace("127.0.0.1", "::1"), Path::new(".")).is_err());
        let error = Config::parse(
            &format!("{BASE}\ncredential=secret with spaces"),
            Path::new("."),
        )
        .unwrap_err();
        assert!(!error.contains("secret"));
    }
}
