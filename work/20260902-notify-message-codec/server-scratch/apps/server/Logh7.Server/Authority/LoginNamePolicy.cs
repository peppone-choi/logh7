using System.Diagnostics.CodeAnalysis;

namespace Logh7.Server.Authority;

public static class LoginNamePolicy
{
    public const int MinimumElements = 3;
    public const int MaximumElements = 30;

    public static bool TryNormalize(
        ReadOnlySpan<ushort> elements,
        [NotNullWhen(true)] out string? normalized)
    {
        normalized = null;
        if (elements.Length is < MinimumElements or > MaximumElements)
        {
            return false;
        }

        var characters = new char[elements.Length];
        for (var index = 0; index < elements.Length; index++)
        {
            var element = elements[index];
            var isLowercaseLetter = element is >= 'a' and <= 'z';
            var isDigit = element is >= '0' and <= '9';
            var isSuffixPunctuation = index > 0 && element is '_' or '-';
            if (!isLowercaseLetter && !isDigit && !isSuffixPunctuation)
            {
                return false;
            }

            characters[index] = (char)element;
        }

        normalized = new string(characters);
        return true;
    }
}
