"""One polling step for the inactive client's localhost login relay."""
import socket


def pump_login_relay(listener, connection, upstream, upstream_port, received, report):
    try:
        if connection is None:
            try:
                connection, peer = listener.accept()
            except BlockingIOError:
                return connection, upstream
            connection.setblocking(False)
            # Creation cancellation opens a fresh LG connection with fresh cipher state.
            if upstream is None and upstream_port is not None:
                upstream = socket.create_connection(("127.0.0.1", upstream_port), timeout=2)
                upstream.settimeout(0.05)
            report["peer"] = peer
            report.setdefault("peers", []).append(peer)
        try:
            chunk = connection.recv(4096)
        except BlockingIOError:
            chunk = None
        except ConnectionResetError:
            chunk = b""
        if chunk == b"":
            connection.close()
            if upstream is not None:
                upstream.close()
            return None, None
        if chunk:
            received.extend(chunk)
            if upstream is not None:
                upstream.sendall(chunk)
        if upstream is not None:
            try:
                reply = upstream.recv(4096)
            except socket.timeout:
                return connection, upstream
            except ConnectionResetError:
                reply = b""
                report["upstream_reset"] = True
            if reply:
                connection.sendall(reply)
                report["server_bytes"] = report.get("server_bytes", 0) + len(reply)
            else:
                connection.shutdown(socket.SHUT_WR)
                upstream.close()
                upstream = None
                report["upstream_eof"] = True
        return connection, upstream
    except BaseException:
        if connection is not None:
            connection.close()
        if upstream is not None:
            upstream.close()
        raise
