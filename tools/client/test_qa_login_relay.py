"""Real localhost socket checks; no Windows desktop or game process."""
import socket
import unittest
from qa_login_relay import pump_login_relay


class LoginRelayTest(unittest.TestCase):
    def setUp(self):
        self.listener = self.listen()
        self.listener.setblocking(False)
        self.backend = self.listen()
        self.connection = self.upstream = None
        self.received, self.report = bytearray(), {}

    def listen(self):
        server = socket.socket()
        self.addCleanup(server.close)
        server.bind(("127.0.0.1", 0))
        server.listen(2)
        server.settimeout(1)
        return server

    def client(self):
        client = socket.create_connection(self.listener.getsockname(), timeout=1)
        self.addCleanup(client.close)
        return client

    def accept_backend(self):
        connection, _ = self.backend.accept()
        connection.settimeout(1)
        self.addCleanup(connection.close)
        return connection

    def pump(self, port=True):
        self.connection, self.upstream = pump_login_relay(
            self.listener, self.connection, self.upstream,
            self.backend.getsockname()[1] if port else None, self.received, self.report)

    def tearDown(self):
        for connection in (self.connection, self.upstream):
            if connection is not None:
                connection.close()

    def test_client_reconnect_uses_fresh_backend_and_forwards_both_directions(self):
        for request, reply in ((b"first login", b"first key"), (b"new login", b"new key")):
            client = self.client()
            client.sendall(request)
            self.pump()
            backend = self.accept_backend()
            self.assertEqual(backend.recv(4096), request)
            backend.sendall(reply)
            self.pump()
            self.assertEqual(client.recv(4096), reply)
            client.close()
            self.pump()
            self.assertIsNone(self.connection)
            self.assertIsNone(self.upstream)
            self.assertEqual(backend.recv(1), b"")
        self.assertEqual(self.received, b"first loginnew login")
        self.assertEqual(self.report["server_bytes"], len(b"first keynew key"))
        self.assertEqual(len(self.report["peers"]), 2)

    def test_backend_eof_reaches_client_and_allows_later_login(self):
        client = self.client()
        self.pump()
        backend = self.accept_backend()
        backend.shutdown(socket.SHUT_WR)
        self.pump()
        self.assertEqual(client.recv(1), b"")
        self.assertIsNone(self.upstream)
        self.assertTrue(self.report["upstream_eof"])
        client.close()
        self.pump()
        second = self.client()
        second.sendall(b"retry")
        self.pump()
        self.assertEqual(self.accept_backend().recv(4096), b"retry")

    def test_stub_capture_without_upstream_can_accept_next_client(self):
        for packet in (b"one", b"two"):
            client = self.client()
            client.sendall(packet)
            self.pump(port=False)
            client.close()
            self.pump(port=False)
        self.assertEqual(self.received, b"onetwo")
        self.assertEqual(len(self.report["peers"]), 2)


if __name__ == "__main__":
    unittest.main()
