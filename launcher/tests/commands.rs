use std::process::Command;

#[test]
fn combined_operations_are_rejected_before_opening_config_or_downloading() {
    let operations: [&[&str]; 6] = [
        &["--setup"],
        &["--check"],
        &["--check-connection"],
        &["--apply-patch", "unused-patch"],
        &["--revert-patch"],
        &["--download-patch", "unused-url", "unused-download"],
    ];

    for first in operations {
        for second in operations {
            let output = Command::new(env!("CARGO_BIN_EXE_logh7-launcher"))
                .args(["--config", "missing-launcher.ini"])
                .args(first)
                .args(second)
                .output()
                .unwrap();
            assert!(!output.status.success(), "{first:?} {second:?}");
            let error = String::from_utf8_lossy(&output.stderr);
            assert!(
                error.contains("명령은 한 번에 하나만 실행하세요"),
                "{first:?} {second:?}: {error}"
            );
        }
    }
}
