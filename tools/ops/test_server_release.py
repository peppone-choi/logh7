"""Checks for server_release.py. Author: Peppone Choi."""
import hashlib
import io
import struct
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import server_release  # noqa: E402


class ServerReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.build = root / "install" / "app"
        (self.build / "lib").mkdir(parents=True)
        (self.build / "bin").mkdir()
        (self.build / "lib" / "app.jar").write_bytes(b"v1")
        self.releases = root / "releases"

    def tearDown(self):
        self.temp.cleanup()

    def tool(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            server_release.main(["--health", "", *args])
        return out.getvalue().strip()

    def test_activate_rollback_and_classpath(self):
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "a")
        (self.build / "lib" / "app.jar").write_bytes(b"v2")
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "b")
        self.tool("activate", "a", "--releases", str(self.releases))
        self.tool("activate", "b", "--releases", str(self.releases))
        self.assertTrue(self.tool("classpath", "--releases", str(self.releases)).endswith("/b/lib/*"))
        self.tool("rollback", "--releases", str(self.releases))
        self.assertTrue(self.tool("classpath", "--releases", str(self.releases)).endswith("/a/lib/*"))
        self.assertEqual("b", (self.releases / "PREVIOUS").read_text().strip())

    def test_damaged_release_duplicate_name_and_running_server_are_refused(self):
        for name in ("current", "Previous", "CURRENT."):
            with self.assertRaisesRegex(SystemExit, "bad release name"):
                self.tool("install", str(self.build), "--releases", str(self.releases), "--name", name)
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "a")
        with self.assertRaises(SystemExit):
            self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "a")
        (self.releases / "a" / "lib" / "app.jar").write_bytes(b"tampered")
        with self.assertRaises(SystemExit):
            self.tool("activate", "a", "--releases", str(self.releases))
        self.assertFalse((self.releases / "CURRENT").exists())
        original = server_release.server_running
        server_release.server_running = lambda _: True
        try:
            with self.assertRaises(SystemExit):
                self.tool("rollback", "--releases", str(self.releases))
        finally:
            server_release.server_running = original

    def test_rollback_to_a_release_that_cannot_read_v2_characters_is_refused(self):
        import zipfile
        with zipfile.ZipFile(self.build / "lib" / "gateway.jar", "w") as jar:
            jar.writestr("org/logh7/gateway/CharacterCreation.class", b"")
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old")
        with zipfile.ZipFile(self.build / "lib" / "gateway.jar", "w") as jar:
            jar.writestr("org/logh7/gateway/CharacterProfile.class", b"")
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new")
        characters = Path(self.temp.name) / "characters.properties"
        characters.write_text("#x\nmulti0=1008040000\n", encoding="latin-1")
        self.tool("activate", "old", "--releases", str(self.releases), "--characters", str(characters))
        self.tool("activate", "new", "--releases", str(self.releases), "--characters", str(characters))
        characters.write_text("#x\nmulti0=v2\\:generated\\:3\\:alliance\\:1008\n", encoding="latin-1")
        with self.assertRaises(SystemExit) as refused:
            self.tool("rollback", "--releases", str(self.releases), "--characters", str(characters))
        self.assertIn("character profile v2", str(refused.exception))
        self.assertEqual("new", (self.releases / "CURRENT").read_text().strip())
        # A later session generation is stored beside the base file and is checked too.
        characters.write_text("#x\nmulti0=1008040000\n", encoding="latin-1")
        later = characters.with_name(characters.name + ".session-2")
        later.write_text("#x\nmulti0=v2\\:generated\\:3\\:alliance\\:1008\n", encoding="latin-1")
        characters.with_name(characters.name + ".session-generation").write_text("2\n", encoding="latin-1")
        with self.assertRaises(SystemExit):
            self.tool("rollback", "--releases", str(self.releases), "--characters", str(characters))
        # After restoring the pre-upgrade characters the same rollback goes through.
        later.write_text("#x\nmulti0=1008040000\n", encoding="latin-1")
        self.tool("rollback", "--releases", str(self.releases), "--characters", str(characters))
        self.assertEqual("old", (self.releases / "CURRENT").read_text().strip())

    def test_store_header_must_be_an_exact_constant_of_the_target_reader(self):
        import zipfile

        def constant(text):
            data = text.encode()
            return b"\x01" + len(data).to_bytes(2, "big") + data

        reader = "org/logh7/persistence/FilePlanetStockStore.class"
        with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("LOGH7-STOCK-4") + constant("LOGH7-STOCK-41"))
            jar.writestr("org/logh7/persistence/FileWorldStateStore.class", b"\xca\xfe" + constant("LOGH7-WORLD-1"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old")
        with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("LOGH7-STOCK-41") + constant("LOGH7-STOCK-42"))
            jar.writestr("org/logh7/persistence/FileWorldStateStore.class", b"\xca\xfe" + constant("LOGH7-WORLD-1"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new")
        world = Path(self.temp.name) / "world"
        (world / "slot-0").mkdir(parents=True)
        (world / "slot-1").mkdir()
        (world / "CURRENT").write_text("LOGH7-WORLD-1\t0\t1\n", encoding="latin-1")
        # A newer file in the inactive slot (a failed update before CURRENT moved) does not block.
        (world / "slot-1" / "stocks.tsv").write_text("LOGH7-STOCK-99\t1\t2\n", encoding="latin-1")
        stocks = world / "slot-0" / "stocks.tsv"
        stocks.write_text("LOGH7-STOCK-41\t1\t2\n", encoding="latin-1")
        self.tool("activate", "old", "--releases", str(self.releases), "--world", str(world))
        stocks.write_text("LOGH7-STOCK-42\t1\t2\n", encoding="latin-1")
        self.tool("activate", "new", "--releases", str(self.releases), "--world", str(world))
        # "LOGH7-STOCK-4" is a prefix of the new header, but only an exact constant counts.
        with self.assertRaises(SystemExit) as refused:
            self.tool("rollback", "--releases", str(self.releases), "--world", str(world))
        self.assertIn("LOGH7-STOCK-42", str(refused.exception))
        self.assertEqual("new", (self.releases / "CURRENT").read_text().strip())

    def test_country_succession_header_rejects_a_release_with_only_country_offices(self):
        import zipfile
        reader = "org/logh7/persistence/FilePlanetStockStore.class"
        for name, header in (("offices", "LOGH7-STOCK-43"), ("succession", "LOGH7-STOCK-44")):
            data = header.encode()
            with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
                jar.writestr(reader, b"\x01" + len(data).to_bytes(2, "big") + data)
            self.tool("install", str(self.build), "--releases", str(self.releases), "--name", name)
        characters = Path(self.temp.name) / "characters.properties"
        stocks = characters.with_name(characters.name + ".planet-stocks")
        stocks.write_text("LOGH7-STOCK-44\t7\t0\n", encoding="utf-8")
        self.tool("activate", "succession", "--releases", str(self.releases), "--characters", str(characters))
        with self.assertRaises(SystemExit) as refused:
            self.tool("activate", "offices", "--releases", str(self.releases), "--characters", str(characters))
        self.assertIn("LOGH7-STOCK-44", str(refused.exception))
        self.assertEqual("succession", (self.releases / "CURRENT").read_text().strip())

    def test_country_trade_header_rejects_a_release_with_only_country_succession(self):
        import zipfile
        reader = "org/logh7/persistence/FilePlanetStockStore.class"
        for name, header in (("succession", "LOGH7-STOCK-44"), ("trade", "LOGH7-STOCK-45")):
            data = header.encode()
            with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
                jar.writestr(reader, b"\x01" + len(data).to_bytes(2, "big") + data)
            self.tool("install", str(self.build), "--releases", str(self.releases), "--name", name)
        characters = Path(self.temp.name) / "characters.properties"
        stocks = characters.with_name(characters.name + ".planet-stocks")
        stocks.write_text("LOGH7-STOCK-45\t7\t0\n", encoding="utf-8")
        self.tool("activate", "trade", "--releases", str(self.releases), "--characters", str(characters))
        with self.assertRaises(SystemExit) as refused:
            self.tool("activate", "succession", "--releases", str(self.releases), "--characters", str(characters))
        self.assertIn("LOGH7-STOCK-45", str(refused.exception))
        self.assertEqual("trade", (self.releases / "CURRENT").read_text().strip())

    def test_country_diplomacy_header_rejects_a_release_with_only_country_trade(self):
        import zipfile
        reader = "org/logh7/persistence/FilePlanetStockStore.class"
        for name, header in (("trade", "LOGH7-STOCK-45"), ("diplomacy", "LOGH7-STOCK-46")):
            data = header.encode()
            with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
                jar.writestr(reader, b"\x01" + len(data).to_bytes(2, "big") + data)
            self.tool("install", str(self.build), "--releases", str(self.releases), "--name", name)
        characters = Path(self.temp.name) / "characters.properties"
        stocks = characters.with_name(characters.name + ".planet-stocks")
        stocks.write_text("LOGH7-STOCK-46\t7\t0\n", encoding="utf-8")
        self.tool("activate", "diplomacy", "--releases", str(self.releases), "--characters", str(characters))
        with self.assertRaises(SystemExit) as refused:
            self.tool("activate", "trade", "--releases", str(self.releases), "--characters", str(characters))
        self.assertIn("LOGH7-STOCK-46", str(refused.exception))
        self.assertEqual("diplomacy", (self.releases / "CURRENT").read_text().strip())

    def test_country_suppression_and_independent_owner_require_matching_readers(self):
        import zipfile
        reader = "org/logh7/persistence/FilePlanetStockStore.class"
        releases = (("diplomacy", "LOGH7-STOCK-46"), ("suppression", "LOGH7-STOCK-47"),
                    ("independent", "LOGH7-STOCK-48"))
        for name, header in releases:
            data = header.encode()
            with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
                jar.writestr(reader, b"\x01" + len(data).to_bytes(2, "big") + data)
            self.tool("install", str(self.build), "--releases", str(self.releases), "--name", name)
        characters = Path(self.temp.name) / "characters.properties"
        stocks = characters.with_name(characters.name + ".planet-stocks")
        for (previous, _), (current, header) in zip(releases, releases[1:]):
            stocks.write_text(f"{header}\t7\t0\n", encoding="utf-8")
            self.tool("activate", current, "--releases", str(self.releases), "--characters", str(characters))
            with self.assertRaises(SystemExit) as refused:
                self.tool("activate", previous, "--releases", str(self.releases), "--characters", str(characters))
            self.assertIn(header, str(refused.exception))
            self.assertEqual(current, (self.releases / "CURRENT").read_text().strip())

    def test_definitions_database_needs_the_postgres_reader(self):
        import os
        import zipfile
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old")
        with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
            jar.writestr("org/logh7/persistence/PostgresCountryDefinitions.class", b"\xca\xfe")
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new")
        self.tool("activate", "new", "--releases", str(self.releases), "--definitions-db")
        with self.assertRaises(SystemExit) as refused:
            self.tool("activate", "old", "--releases", str(self.releases), "--definitions-db")
        self.assertIn("country definitions database", str(refused.exception))
        self.assertEqual("new", (self.releases / "CURRENT").read_text().strip())
        # The same JDBC variable also serves the color-only database, so it alone does not refuse.
        saved = os.environ.get("LOGH7_DEFINITIONS_JDBC_URL")
        os.environ["LOGH7_DEFINITIONS_JDBC_URL"] = "jdbc:postgresql://127.0.0.1/test"
        try:
            self.tool("activate", "old", "--releases", str(self.releases))
        finally:
            if saved is None:
                os.environ.pop("LOGH7_DEFINITIONS_JDBC_URL")
            else:
                os.environ["LOGH7_DEFINITIONS_JDBC_URL"] = saved

    def test_world_definition_schema_needs_a_matching_reader(self):
        import zipfile

        def constant(text):
            data = text.encode()
            return b"\x01" + len(data).to_bytes(2, "big") + data

        reader = "org/logh7/engine/Galaxy$Companion.class"
        with zipfile.ZipFile(self.build / "lib" / "engine.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("Country starts require world schema 1"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old")
        with zipfile.ZipFile(self.build / "lib" / "engine.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("Country starts require world schema 2"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new")
        world = Path(self.temp.name) / "world.properties"
        world.write_text("schema=1\nsystems=1\n", encoding="utf-8")
        self.tool("activate", "old", "--releases", str(self.releases), "--world-definition", str(world))
        world.write_text("schema=2\nstart.country.42=100\n", encoding="utf-8")
        self.tool("activate", "new", "--releases", str(self.releases), "--world-definition", str(world))
        with self.assertRaises(SystemExit) as refused:
            self.tool("rollback", "--releases", str(self.releases), "--world-definition", str(world))
        self.assertIn("world schema 2", str(refused.exception))
        # Properties semantics: a colon separator and a later duplicate both yield schema 2.
        for text in ("schema:2\n", "schema=1\nschema=2\n", "# schema=1\nsche\\\n  ma = 2\n"):
            world.write_text(text, encoding="utf-8")
            with self.assertRaises(SystemExit):
                self.tool("activate", "old", "--releases", str(self.releases), "--world-definition", str(world))
        world.write_text("systems=1\n", encoding="utf-8")
        with self.assertRaises(SystemExit):
            self.tool("activate", "new", "--releases", str(self.releases), "--world-definition", str(world))
        world.write_text("schema=5\n", encoding="utf-8")
        with self.assertRaises(SystemExit) as unknown:
            self.tool("activate", "new", "--releases", str(self.releases), "--world-definition", str(world))
        self.assertIn("unknown world schema 5", str(unknown.exception))
        self.assertEqual("new", (self.releases / "CURRENT").read_text().strip())
        world.write_text("schema=4\nstart.unaffiliated=2489\nstart.unaffiliated.style=alliance\n", encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "world schema 4"):
            self.tool("activate", "new", "--releases", str(self.releases), "--world-definition", str(world))
        with zipfile.ZipFile(self.build / "lib" / "engine.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("Unaffiliated starts require world schema 4"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "unaffiliated")
        self.tool("activate", "unaffiliated", "--releases", str(self.releases), "--world-definition", str(world))
        self.assertEqual("unaffiliated", (self.releases / "CURRENT").read_text().strip())

    def test_initial_owner_world_requires_schema_three_reader_and_preserves_current_on_refusal(self):
        import zipfile
        reader = "org/logh7/engine/Galaxy$Companion.class"
        def constant(text):
            encoded = text.encode("utf-8")
            return b"\x01" + len(encoded).to_bytes(2, "big") + encoded
        with zipfile.ZipFile(self.build / "lib" / "engine.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("Country starts require world schema 2"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old-world")
        with zipfile.ZipFile(self.build / "lib" / "engine.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("Country starts require world schema 2")
                         + constant("Initial owners require world schema 3"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new-world")
        world = Path(self.temp.name) / "world.properties"
        world.write_text("schema=3\nbase.0.owner=C:42\n", encoding="utf-8")
        self.tool("activate", "new-world", "--releases", str(self.releases), "--world-definition", str(world))
        with self.assertRaises(SystemExit) as refused:
            self.tool("activate", "old-world", "--releases", str(self.releases), "--world-definition", str(world))
        self.assertIn("world schema 3", str(refused.exception))
        self.assertEqual("new-world", (self.releases / "CURRENT").read_text().strip())

    def test_country_mail_blocks_an_old_reader_across_all_saved_generations(self):
        import zipfile
        reader = "org/logh7/persistence/FileCommunicationStore.class"
        characters = Path(self.temp.name) / "characters.properties"
        characters.write_text("", encoding="utf-8")
        mail = characters.with_name(characters.name + ".mail.session-7")
        mail.write_bytes(bytes.fromhex("4c374d34") + b"fixture")
        with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe\x03" + bytes.fromhex("4c374d33"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old-mail")
        with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe\x03" + bytes.fromhex("4c374d34"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new-mail")
        self.tool("activate", "new-mail", "--releases", str(self.releases), "--characters", str(characters))
        with self.assertRaises(SystemExit) as refused:
            self.tool("activate", "old-mail", "--releases", str(self.releases), "--characters", str(characters))
        self.assertIn("mail country format 4", str(refused.exception))
        self.assertEqual("new-mail", (self.releases / "CURRENT").read_text().strip())
        mail.write_bytes(bytes.fromhex("4c374d33") + b"old fixture")
        self.tool("activate", "old-mail", "--releases", str(self.releases), "--characters", str(characters))

    def test_rollback_checks_all_session_frames_and_does_not_change_pointers_on_refusal(self):
        def codec_class(schema):
            # Minimal class-file field table with a SCHEMA int ConstantValue and an unrelated larger int.
            return (bytes.fromhex("cafebabe0000003d0006") +
                    b"\x01\x00\x06SCHEMA\x01\x00\x01I\x01\x00\x0dConstantValue" +
                    b"\x03" + struct.pack(">I", schema) + b"\x03" + struct.pack(">I", 99) +
                    bytes.fromhex("00000000000000000001001a0001000200010003000000020004"))

        def frame(schema):
            payload = struct.pack(">I", schema) + b"fixture"
            return struct.pack(">I", len(payload)) + payload + hashlib.sha256(payload).digest()

        for schema, name in ((2, "old"), (3, "new")):
            with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
                jar.writestr(server_release.SESSION_CODEC, codec_class(schema))
            self.tool("install", str(self.build), "--releases", str(self.releases), "--name", name)
        alpha, beta = (Path(self.temp.name) / name for name in ("alpha", "beta"))
        for folder in (alpha, beta):
            folder.mkdir()
            (folder / "session.checkpoint").write_bytes(frame(2))
            (folder / "session.journal").write_bytes(frame(1))
        options = ("--releases", str(self.releases), "--session", str(alpha), "--session", str(beta))
        self.tool("activate", "old", *options)
        journal = beta / "session.journal"
        journal.write_bytes(frame(2) + frame(3))
        self.tool("activate", "new", *options)
        with self.assertRaisesRegex(SystemExit, "unsupported session schema 3"):
            self.tool("rollback", *options)
        journal.write_bytes(frame(2)[:-1])
        with self.assertRaisesRegex(SystemExit, "invalid session frame checksum"):
            self.tool("rollback", *options)
        journal.write_bytes(frame(2))
        checkpoint = beta / "session.checkpoint"
        checkpoint.write_bytes(frame(2) * 2)
        with self.assertRaisesRegex(SystemExit, "invalid session checkpoint frame count"):
            self.tool("rollback", *options)
        self.assertEqual("new", (self.releases / "CURRENT").read_text().strip())
        self.assertEqual("old", (self.releases / "PREVIOUS").read_text().strip())
        checkpoint.write_bytes(frame(2))
        self.tool("rollback", *options)
        self.assertEqual("old", (self.releases / "CURRENT").read_text().strip())

    def test_hashed_accounts_reserved_names_and_shared_fame_are_checked_without_printing_secrets(self):
        def constant(value):
            data = value.encode()
            return b"\x01" + len(data).to_bytes(2, "big") + data

        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old")
        jar_path = self.build / "lib" / "gateway.jar"
        with zipfile.ZipFile(jar_path, "w") as jar:
            jar.writestr(server_release.ACCOUNT_PASSWORD, constant("pbkdf2-sha256"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "hashed")
        with zipfile.ZipFile(jar_path, "a") as jar:
            jar.writestr(server_release.FILE_ACCOUNTS, constant("pbkdf2-sha256:removed") + constant(":recovery-v1:"))
            jar.writestr(server_release.FAME_STORAGE, constant("LOGH7-ACCOUNT-FAME-1"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new")
        accounts = Path(self.temp.name) / "accounts.properties"
        fame = accounts.with_name("account-fame.properties")
        accounts.write_text("test=plain\n", encoding="utf-8")
        options = ("--releases", str(self.releases), "--accounts", str(accounts))
        self.tool("activate", "old", *options)
        accounts.write_text("test=pbkdf2-sha256\\:1\\:salt\\:secret-test-hash\n", encoding="utf-8")
        self.tool("activate", "hashed", *options)
        with self.assertRaisesRegex(SystemExit, "hashed account passwords") as refused:
            self.tool("rollback", *options)
        self.assertNotIn("secret-test-hash", str(refused.exception))
        accounts.write_text("test=pbkdf2-sha256\\:removed\n", encoding="utf-8")
        fame.write_text("@format=LOGH7-ACCOUNT-FAME-1\n", encoding="utf-8")
        options += ("--account-fame", str(fame))
        self.tool("activate", "new", *options)
        with self.assertRaisesRegex(SystemExit, "reserved account names.*unsupported shared account fame format"):
            self.tool("rollback", *options)
        fame.write_text("@format=LOGH7-ACCOUNT-FAME-10\n", encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "unsupported shared account fame format"):
            self.tool("activate", "new", *options)
        accounts.write_text("test=pbkdf2-sha256:1:salt:hash:recovery-v1:private-digest\n", encoding="utf-8")
        options = ("--releases", str(self.releases), "--accounts", str(accounts))
        self.tool("activate", "new", *options)
        with self.assertRaisesRegex(SystemExit, "account recovery codes") as refused:
            self.tool("rollback", *options)
        self.assertNotIn("private-digest", str(refused.exception))

if __name__ == "__main__":
    unittest.main()
