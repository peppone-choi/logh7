use std::{
    io::{self, Read, Write},
    net::{SocketAddr, TcpStream},
    time::{Duration, Instant},
};

const REQUEST: &[u8; 10] = b"\0\x08\0\x38LOGH7?";
const READY: &[u8; 10] = b"\0\x08\0\x38LOGH7!";
const MAINTENANCE: &[u8; 10] = b"\0\x08\0\x38LOGH7-";
const LIMIT: Duration = Duration::from_secs(3);

pub fn check(address: SocketAddr) -> Result<(), &'static str> {
    let deadline = Instant::now() + LIMIT;
    let mut stream = TcpStream::connect_timeout(&address, LIMIT).map_err(|error| {
        if error.kind() == io::ErrorKind::ConnectionRefused {
            "이 주소와 포트에서 연결을 받지 않습니다. 로그인 서버 주소와 포트를 확인하세요"
        } else {
            "서버에 연결할 수 없습니다. 네트워크와 로그인 서버 주소를 확인하세요"
        }
    })?;

    match response(&mut stream, deadline).as_ref() {
        Ok(bytes) if bytes == READY => Ok(()),
        Ok(bytes) if bytes == MAINTENANCE => {
            Err("로그인 서버가 점검 중입니다. 점검이 끝난 뒤 다시 확인하세요")
        }
        _ => Err(
            "TCP 연결은 됐지만 LOGH7 로그인 응답을 확인하지 못했습니다. 로그인 포트와 서버 버전을 확인하세요",
        ),
    }
}

fn response(stream: &mut TcpStream, deadline: Instant) -> io::Result<[u8; 10]> {
    let remaining = || {
        deadline
            .checked_duration_since(Instant::now())
            .filter(|duration| !duration.is_zero())
            .ok_or_else(|| io::Error::from(io::ErrorKind::TimedOut))
    };
    stream.set_write_timeout(Some(remaining()?))?;
    stream.write_all(REQUEST)?;
    let mut reply = [0; 10];
    let mut received = 0;
    while received < reply.len() {
        stream.set_read_timeout(Some(remaining()?))?;
        let read = stream.read(&mut reply[received..])?;
        if read == 0 {
            return Err(io::Error::from(io::ErrorKind::UnexpectedEof));
        }
        received += read;
    }
    Ok(reply)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::TcpListener, thread};

    #[test]
    fn check_sends_only_the_probe_and_distinguishes_each_response() {
        for (response, expected) in [
            (&READY[..], None),
            (&MAINTENANCE[..], Some("점검 중")),
            (
                &b"HTTP/1.1 2"[..],
                Some("LOGH7 로그인 응답을 확인하지 못했습니다"),
            ),
            (&b""[..], Some("LOGH7 로그인 응답을 확인하지 못했습니다")),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                stream.set_read_timeout(Some(LIMIT)).unwrap();
                let mut request = [0; 10];
                stream.read_exact(&mut request).unwrap();
                assert_eq!(&request, REQUEST);
                stream.write_all(response).unwrap();
                stream.shutdown(std::net::Shutdown::Write).unwrap();
                assert_eq!(stream.read(&mut request).unwrap(), 0);
            });
            let outcome = check(address);
            match expected {
                Some(expected) => assert!(outcome.unwrap_err().contains(expected)),
                None => assert!(outcome.is_ok()),
            }
            server.join().unwrap();
        }
    }
}
