"""Transport rejection checks only; never runs F1, Frida, games or audio APIs."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import verify_qa_bundle as verifier


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'WINDOWS-QA-BUNDLE.zip'
        self.manifest = Path(self.temp.name) / 'manifest.json'

    def make_bundle(self, mutation=None):
        rows = []
        payloads = {}
        for i in range(16):
            name = f'logh-audio/file{i}.json'
            data = json.dumps({'fixture': i}).encode()
            rows.append({'archive_path': name, 'size': len(data),
                         'sha256': hashlib.sha256(data).hexdigest()})
            payloads[name] = data
        manifest = {'files': rows}
        self.manifest.write_text(json.dumps(manifest))
        payloads['logh-audio/manifest.json'] = json.dumps(manifest).encode()
        if mutation:
            mutation(payloads)
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, data in payloads.items():
                archive.writestr(name, data)
        blob = stream.getvalue()
        self.path.write_bytes(blob)
        return blob

    def verify_synthetic(self, mutation=None):
        blob = self.make_bundle(mutation)
        # Synthetic pins exercise checks beneath the production whole-ZIP pin.
        with patch.object(verifier, 'MANIFEST', self.manifest), \
             patch.object(verifier, 'PINNED_BYTES', len(blob)), \
             patch.object(verifier, 'PINNED_SHA256', hashlib.sha256(blob).hexdigest()):
            return verifier.verify(self.path)

    def test_positive_stays_closed(self):
        result = self.verify_synthetic()
        self.assertEqual(result['readiness'], 'CLOSED')
        self.assertEqual(result['upload'], 'NOT_RUN')

    def test_unapproved_secret_path_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unapproved'):
            self.verify_synthetic(lambda p: p.update({'state/openclaw.json': b'fixture'}))

    def test_missing_member_rejected(self):
        with self.assertRaisesRegex(ValueError, 'missing'):
            self.verify_synthetic(lambda p: p.pop('logh-audio/file0.json'))

    def test_member_hash_rejected(self):
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            self.verify_synthetic(lambda p: p.update({'logh-audio/file0.json': b'x' * len(p['logh-audio/file0.json'])}))

    def test_member_size_rejected(self):
        with self.assertRaisesRegex(ValueError, "size mismatch"):
            self.verify_synthetic(lambda p: p.update({"logh-audio/file0.json": b"x"}))

    def test_manifest_replacement_rejected(self):
        with self.assertRaisesRegex(ValueError, 'manifest mismatch'):
            self.verify_synthetic(lambda p: p.update({'logh-audio/manifest.json': b'{"files": []}'}))

    def test_whole_zip_pin_rejected(self):
        self.make_bundle()
        with self.assertRaisesRegex(ValueError, 'Pinned source ZIP'):
            verifier.verify(self.path)

    def test_resource_destination_rejected(self):
        with self.assertRaisesRegex(ValueError, 'basename'):
            verifier.verify(Path(self.temp.name) / 'WINDOWS-BUNDLE.zip')


if __name__ == '__main__':
    unittest.main()
