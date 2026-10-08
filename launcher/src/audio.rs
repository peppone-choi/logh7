#[cfg(windows)]
mod windows {
    use std::{ffi::c_void, ptr};
    #[repr(C)]
    struct Guid(u32, u16, u16, [u8; 8]);
    const ENUMERATOR: Guid = Guid(
        0xbcde0395,
        0xe52f,
        0x467c,
        [0x8e, 0x3d, 0xc4, 0x57, 0x92, 0x91, 0x69, 0x2e],
    );
    const INTERFACE: Guid = Guid(
        0xa95664d2,
        0x9614,
        0x4f35,
        [0xa7, 0x46, 0xde, 0x8d, 0xb6, 0x36, 0x17, 0xe6],
    );
    #[link(name = "ole32")]
    unsafe extern "system" {
        fn CoInitializeEx(reserved: *mut c_void, flags: u32) -> i32;
        fn CoUninitialize();
        fn CoCreateInstance(
            class: *const Guid,
            outer: *mut c_void,
            context: u32,
            interface: *const Guid,
            output: *mut *mut c_void,
        ) -> i32;
    }
    struct Apartment;
    impl Drop for Apartment {
        fn drop(&mut self) {
            unsafe {
                CoUninitialize();
            }
        }
    }
    struct Com(*mut c_void);
    impl Com {
        unsafe fn slot(&self, index: usize) -> *const c_void {
            unsafe { *(*(self.0 as *const *const *const c_void)).add(index) }
        }
    }
    impl Drop for Com {
        fn drop(&mut self) {
            unsafe {
                let release: unsafe extern "system" fn(*mut c_void) -> u32 =
                    std::mem::transmute(self.slot(2));
                release(self.0);
            }
        }
    }
    pub fn check() -> Result<(), String> {
        unsafe {
            if CoInitializeEx(ptr::null_mut(), 0) < 0 {
                return Err("오디오 장치 점검을 초기화하지 못했습니다".into());
            }
            let _apartment = Apartment;
            let mut enumerator = ptr::null_mut();
            if CoCreateInstance(&ENUMERATOR, ptr::null_mut(), 1, &INTERFACE, &mut enumerator) < 0
                || enumerator.is_null()
            {
                return Err("Windows 오디오 장치를 조회하지 못했습니다".into());
            }
            let enumerator = Com(enumerator);
            let get_default: unsafe extern "system" fn(
                *mut c_void,
                i32,
                i32,
                *mut *mut c_void,
            ) -> i32 = std::mem::transmute(enumerator.slot(4));
            let mut device = ptr::null_mut();
            if get_default(enumerator.0, 0, 1, &mut device) < 0 || device.is_null() {
                return Err(
                    "활성 기본 오디오 출력 장치가 없습니다. 출력 장치를 연결한 뒤 다시 실행하세요"
                        .into(),
                );
            }
            let device = Com(device);
            let get_state: unsafe extern "system" fn(*mut c_void, *mut u32) -> i32 =
                std::mem::transmute(device.slot(6));
            let mut state = 0;
            if get_state(device.0, &mut state) < 0 || state & 1 == 0 {
                return Err("기본 오디오 출력 장치가 활성 상태가 아닙니다".into());
            }
            Ok(())
        }
    }
}

#[cfg(windows)]
pub use windows::check;
#[cfg(not(windows))]
pub fn check() -> Result<(), String> {
    Err("게임 실행은 Windows에서 지원합니다".into())
}
