"""Validate controlled IME results for an owned ANSI view. Author: 최병호."""


def cp949_ui_bytes(value, acp):
    # Controlled results are separate from physical IME composition testing.
    # Validate the entire action before calling IME APIs in the owned thread.
    if acp != 949:
        raise ValueError('Korean UI characters require the existing CP949 ACP')
    if not isinstance(value, str) or not 1 <= len(value) <= 511:
        raise ValueError('Use one to 511 printable CP949 UI characters')
    if any(ord(char) < 32 or 0x7f <= ord(char) < 0xa0 for char in value):
        raise ValueError('Control characters require separate owned UI actions')
    try:
        encoded = value.encode('cp949', errors='strict')
    except UnicodeEncodeError as error:
        raise ValueError('UI characters must be representable in CP949') from error
    if encoded.decode('cp949', errors='strict') != value:
        raise ValueError('Lossy CP949 UI characters are unsupported')
    return encoded
