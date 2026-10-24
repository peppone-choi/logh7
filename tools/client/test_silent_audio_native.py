"""Windows-only memory DirectSound check using a harmless owned x86 sleeper."""
import json
import os
from pathlib import Path
import tempfile
import unittest
import uuid

from qa_prestart_fixture import image


@unittest.skipUnless(os.name == 'nt' and os.environ.get('LOGH7_SILENT_AUDIO_TEST') == '1',
                     'Explicit Windows native fixture test only')
class SilentAudioNativeTest(unittest.TestCase):
    def test_real_export_returns_only_memory_objects_before_main_thread_resume(self):
        import win32con
        import win32event
        import win32job
        import win32process
        import win32service
        from silent_audio import SilentAudio
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        executable = Path(directory.name) / 'silent-audio-fixture.exe'
        executable.write_bytes(image('hang'))
        name = 'LOGH7_AUDIO_FIXTURE_' + uuid.uuid4().hex
        desktop = win32service.CreateDesktop(name, 0, 0x10000000, None)
        job = win32job.CreateJobObject(None, name + '_JOB')
        info = win32job.QueryInformationJobObject(job, win32job.JobObjectExtendedLimitInformation)
        info['BasicLimitInformation']['LimitFlags'] = win32job.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        win32job.SetInformationJobObject(job, win32job.JobObjectExtendedLimitInformation, info)
        startup = win32process.STARTUPINFO()
        startup.lpDesktop = 'winsta0\\' + name
        process = thread = guard = None
        try:
            process, thread, pid, _ = win32process.CreateProcess(str(executable), None, None, None,
                False, win32con.CREATE_SUSPENDED, None, str(executable.parent), startup)
            win32job.AssignProcessToJobObject(job, process)
            guard = SilentAudio(pid, executable,
                on_failure=lambda: win32process.TerminateProcess(process, 0xA002), fixture=True)
            self.assertTrue(guard.assert_ready()['ready'])
            script = guard.session.create_script(r'''
              rpc.exports.probe=function(){
                const p=Memory.alloc(32), none=ptr(0);
                const load=new NativeFunction(Process.getModuleByName('kernel32.dll').getExportByName('LoadLibraryW'),'pointer',['pointer'],'stdcall');
                load(Memory.allocUtf16String('dsound.dll'));
                const create=new NativeFunction(Process.getModuleByName('dsound.dll').getExportByName('DirectSoundCreate8'),'int',['pointer','pointer','pointer'],'stdcall');
                const createResult=create(none,p,none),device=p.readPointer();
                const method=(o,n,args)=>new NativeFunction(o.readPointer().add(4*n).readPointer(),'int',args,'stdcall');
                const fmt=Memory.alloc(18);fmt.writeByteArray(new Uint8Array(18));
                fmt.writeU16(1);fmt.add(2).writeU16(1);fmt.add(4).writeU32(22050);fmt.add(8).writeU32(44100);fmt.add(12).writeU16(2);fmt.add(14).writeU16(16);
                const desc=Memory.alloc(36);desc.writeByteArray(new Uint8Array(36));
                desc.writeU32(36);desc.add(8).writeU32(1024);desc.add(16).writePointer(fmt);
                const bufferResult=method(device,3,['pointer','pointer','pointer','pointer'])(device,desc,p,none),buffer=p.readPointer();
                const lock=method(buffer,11,['pointer','uint','uint','pointer','pointer','pointer','pointer','uint'])(buffer,1000,128,p,p.add(4),p.add(8),p.add(12),0);
                const regions=[p.add(4).readU32(),p.add(12).readU32()];
                const play=method(buffer,12,['pointer','uint','uint','uint'])(buffer,0,0,1);
                method(buffer,9,['pointer','pointer'])(buffer,p);const playing=p.readU32();
                const stop=method(buffer,18,['pointer'])(buffer);
                method(buffer,9,['pointer','pointer'])(buffer,p);const stopped=p.readU32();
                const duplicate=method(device,5,['pointer','pointer','pointer'])(device,buffer,p);
                const distinct=!p.readPointer().equals(buffer);
                const wave=new NativeFunction(Process.getModuleByName('winmm.dll').getExportByName('waveOutOpen'),
                    'uint',['pointer','uint','pointer','pointer','pointer','uint'],'stdcall');
                const waveDenied=wave(p,0,fmt,none,none,0);
                const com=new NativeFunction(Process.getModuleByName('ole32.dll').getExportByName('CoCreateInstance'),
                    'int',['pointer','pointer','uint','pointer','pointer'],'stdcall');
                const comDenied=com(none,none,1,none,p);
                return {createResult,bufferResult,lock,regions,play,playing,stop,stopped,duplicate,distinct,waveDenied,comDenied};
              };
            ''')
            script.load()
            result = script.exports_sync.probe()
            self.assertEqual(result, dict(createResult=0, bufferResult=0, lock=0, regions=[24, 104],
                play=0, playing=5, stop=0, stopped=0, duplicate=0, distinct=True,
                waveDenied=8, comDenied=-2147024891))
            status = guard.assert_ready()
            self.assertEqual(status['native_audio_factories_called'], 0)
            self.assertEqual(status['plays'], 1)
            self.assertIn('DirectSoundCreate8', status['installed'])
            self.assertEqual(status['blocked']['waveOutOpen'], 1)
            self.assertEqual(status['blocked']['CoCreateInstance'], 1)
            print(json.dumps({'fixture': result, 'audio': status}))
        finally:
            if guard is not None:
                guard.closing = True
            win32job.TerminateJobObject(job, 0)
            if process is not None:
                self.assertEqual(win32event.WaitForSingleObject(process, 5000), win32con.WAIT_OBJECT_0)
            if guard is not None:
                guard.close()
            for handle in (thread, process, job):
                if handle is not None:
                    handle.Close()
            desktop.CloseDesktop()


if __name__ == '__main__':
    unittest.main()
