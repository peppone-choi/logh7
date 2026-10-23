"""Pure, fail-closed audio coverage judgment; never touches audio or Win32."""
import math
import re

FAMILIES = ('DirectSound', 'WASAPI', 'waveOut', 'PlaySound', 'MIDI', 'MCI', 'beep')
DENIED = {
    'IDirectSoundBuffer.Play': 0, 'IAudioClient.Start': 0,
    'DirectSoundFullDuplexCreate': -2147024891,
    'ActivateAudioInterfaceAsync': -2147024891,
    'waveOutWrite': 8, 'waveOutOpen': 8,
    'PlaySoundA': 1, 'PlaySoundW': 1, 'sndPlaySoundA': 1, 'sndPlaySoundW': 1,
    'midiOutShortMsg': 8, 'midiOutLongMsg': 8, 'midiStreamOut': 8,
    'mciSendStringA': 1, 'mciSendStringW': 1, 'mciSendCommandA': 1, 'mciSendCommandW': 1,
    'Beep': 1, 'MessageBeep': 1,
}
INITIALIZERS = {'DirectSoundCreate', 'DirectSoundCreate8', 'CreateSoundBuffer',
                'DuplicateSoundBuffer', 'IMMDevice.Activate'}


def family(api):
    if api.startswith(('DirectSound', 'IDirectSound', 'CreateSound', 'DuplicateSound')):
        return 'DirectSound'
    if api.startswith(('IAudioClient', 'IMMDevice', 'ActivateAudio')):
        return 'WASAPI'
    if api.startswith('waveOut'):
        return 'waveOut'
    if api.startswith(('PlaySound', 'sndPlaySound')):
        return 'PlaySound'
    if api.startswith('midi'):
        return 'MIDI'
    if api.startswith('mci'):
        return 'MCI'
    if api in ('Beep', 'MessageBeep'):
        return 'beep'
    return None  # Generic COM/child-denial and QI are not audio intent proof.


def check_audio(observation, controller):
    """Only target module-attributed calls qualify; fixture/selftest never do."""
    observed = set()
    attempts = 0
    def verdict(status, reason):
        return dict(status=status, reason=reason, attempts=attempts,
                    NOT_OBSERVED=[f for f in FAMILIES if f not in observed])
    try:
        a = observation
        if a['source'] in ('fixture', 'selftest'):
            return verdict('NOT_RUN', 'fixture/selftest is not product evidence')
        if a['source'] != 'product':
            return verdict('FAIL', 'unknown evidence source')
        if (a['ready'] is not True or a['failed'] is not None or
                type(a['originals_called']) is not int or a['originals_called'] != 0):
            return verdict('FAIL', 'guard failure or original playback called')
        if a['unsupported_output_modules']:
            return verdict('FAIL', 'unsupported output module')
        if controller.get('error') is not None:
            return verdict('FAIL', 'controller error')
        receipts = [controller['pre_resume_receipt'], *controller['guard_receipts']]
        times = [r['recorded_mono'] for r in receipts]
        resumed, stopped = controller['resumed_mono'], controller['target_stopped_mono']
        if (len(receipts) < 2 or controller['resumed'] is not True or
                not all(type(t) in (int, float) and math.isfinite(t) for t in [*times, resumed, stopped]) or
                not all(r['ready'] is True and r.get('failed') is None and
                        r['effective_ui_flags'] == 255 for r in receipts) or
                not times[0] <= times[1] <= resumed <= times[1] + 1 or
                not all(0 <= y - x <= 1 for x, y in zip(times, times[1:])) or
                not times[-1] <= stopped <= times[-1] + 1 or
                any(m.get('type') == 'error' or m.get('payload', {}).get('type') == 'audio-fatal'
                    for m in controller['guard_messages'])):
            return verdict('FAIL', 'guard continuity/fatal error')
        modules = {m['path'].casefold(): m for m in a['loaded_modules']}
        if any(re.match(r'^(xaudio|xact|openal|avrt|ksuser|mfplat|mfplay|audioses)',
                        m['name'].lower()) for m in modules.values()):
            return verdict('FAIL', 'unsupported loaded output module')
        if a['main_module'].casefold() not in modules:
            return verdict('FAIL', 'target main module missing')
        for event in a['calls']:
            if event['source'] in ('fixture', 'selftest'):
                continue
            if event['source'] != 'product':
                return verdict('FAIL', 'unknown call source')
            api, value = event['api'], event['return_value']
            f = family(api)
            if f is None:
                continue
            module = modules[event['caller_path'].casefold()]
            rva = event['caller_rva']
            if (module['name'] != event['caller_module'] or
                    not isinstance(rva, str) or not re.fullmatch(r'0x[0-9a-f]+', rva) or
                    not 0 <= int(rva, 16) < module['size'] or
                    type(event['count']) is not int or event['count'] <= 0 or type(value) is not int):
                return verdict('FAIL', 'invalid caller/return/count')
            if api in DENIED:
                if value != DENIED[api] or event['disposition'] != 'suppressed':
                    return verdict('FAIL', 'unblocked audio return')
            elif api in INITIALIZERS:
                if value < 0:
                    continue  # No device/path reached; not evidence of blocking.
                if event['disposition'] != 'guarded-init':
                    return verdict('FAIL', 'unprotected audio object')
            else:
                return verdict('FAIL', 'unrecognized audio API')
            observed.add(f)
            attempts += event['count']
        return verdict('PASS' if attempts else 'NOT_RUN',
                       'target attempts protected' if attempts else 'no target audio path observed')
    except (KeyError, TypeError, ValueError, AttributeError, OverflowError):
        return verdict('FAIL', 'missing/malformed audio evidence')
