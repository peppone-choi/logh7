using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

// Shared wire projection; deliberately has no session or account balance state.
public static class OriginalStoredCharacterProjection
{
    public static OriginalCreateCharacterCommand Create(CharacterReadRecord character)
    {
        if (character.CharacterId is <= 0 or > uint.MaxValue ||
            character.Faction is < 0 or > byte.MaxValue ||
            character.Blood is < 0 or > byte.MaxValue ||
            character.Sex is < 0 or > byte.MaxValue ||
            character.Face < 0 ||
            character.AbilityValues.Length != 8 ||
            character.AbilityValues.Any(value => value is < 0 or > byte.MaxValue) ||
            character.Rank is <= 0 or > byte.MaxValue)
        {
            throw new InvalidOperationException("PERSISTED_CHARACTER_WIRE_RANGE");
        }

        return new OriginalCreateCharacterCommand(
            RequestCategory: 4,
            CharacterId: checked((uint)character.CharacterId),
            Power: checked((byte)character.Faction),
            Blood: checked((byte)character.Blood),
            Sex: checked((byte)character.Sex),
            LastName: character.LastName,
            FirstName: character.FirstName,
            Age: 18,
            BirthMonth: 1,
            BirthDay: 1,
            Face: checked((uint)character.Face),
            AbilityValues: character.AbilityValues.Select(value => checked((byte)value)).ToArray(),
            BonusPoint: 0,
            SpecialAbilityCount: 0,
            Title: 0,
            Rank: checked((byte)character.Rank),
            // Legacy/lottery rows have no recovered selection yet. The nullable
            // store values retain that distinction; zero remains the current
            // compatibility projection, not proof of an original ship choice.
            FlagshipType: character.FlagshipType ?? 0,
            FlagshipKind: character.FlagshipKind ?? 0,
            FlagshipName: character.FlagshipName,
            Check: 0,
            RawPayload: [],
            ReturnBaseId: character.ReturnBaseId,
            Achievement: character.Achievement);
    }
}
