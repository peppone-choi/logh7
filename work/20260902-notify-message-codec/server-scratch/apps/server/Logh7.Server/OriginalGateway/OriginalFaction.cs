namespace Logh7.Server.OriginalGateway;

// ORIGINAL_STATIC: constmsg group 1, consumed by 0x0054D7A7 from
// InformationCharacter.power. These are not zero-based playable-side indices.
public static class OriginalFaction
{
    public const byte Unified = 0;
    public const byte Neutral = 1;
    public const byte Empire = 2;
    public const byte Alliance = 3;
    public const byte Pirates = 4;

    // Authored two-side battlefield pairing, using original power codes.
    public static byte OpposingMilitaryPower(byte playerPower) =>
        playerPower == Empire ? Alliance : Empire;
}
