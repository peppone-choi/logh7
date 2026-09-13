using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    private async Task<NaturalAuthoritySessionResult> ProcessWarehouseQueryAsync(
        byte[] payload, CancellationToken cancellationToken)
    {
        const ushort type = OriginalWarehouseCodec.RequestType;
        if (!_worldEntered) return Invalid("original.warehouse.world-not-entered", type);
        if (!OriginalWarehouseCodec.TryDecodeRequest(payload, out var request) || request.BaseId == 0)
            return Invalid("original.warehouse.request-shape", type);
        var restoreError = await RestorePersistedCharacterAsync(cancellationToken);
        if (restoreError is not null) return Invalid(restoreError, type);
        if (_createdCharacter is null || _worldCharacterId == 0 || _accountId == Guid.Empty)
            return Invalid("original.warehouse.selection-missing", type);

        OriginalWarehouseSnapshot stock;
        try
        {
            // Authenticated identity only. Base/outfit0 is a specific warehouse,
            // not permission to inspect any account or a request to mint stock.
            stock = await _store.ReadOriginalWarehouseAsync(_accountId, _worldCharacterId,
                new(request.BaseId, request.OutfitId), cancellationToken);
        }
        catch (InvalidOperationException error) when (error.Message == "WAREHOUSE_ACCESS_DENIED")
        {
            return Invalid("original.warehouse.access-denied", type);
        }
        catch (NotSupportedException)
        {
            return Invalid("original.warehouse.persistence-not-supported", type);
        }

        if (stock.Key != new OriginalWarehouseKey(request.BaseId, request.OutfitId))
            return Invalid("original.warehouse.persisted-identity", type);
        var response = ProjectWarehouse(stock);
        return EncodeApplicationResponse(OriginalWarehouseCodec.EncodeResponse(response), type, includeLobbyPrefix: true) with
        {
            ResponseMetadata = FormattableString.Invariant(
                $"warehouse;base={stock.Key.BaseId};outfit={stock.Key.OutfitId};warehouse-version={stock.Version};index-design=zero-placeholder")
        };
    }

    private static OriginalWarehouseResponse ProjectWarehouse(OriginalWarehouseSnapshot stock)
    {
        var balances = stock.Balances;
        long Count(OriginalStockKind kind, ushort item = 0, byte grade = 0) =>
            balances.GetValueOrDefault(new(kind, item, grade));
        var ships = balances.Where(x => x.Value > 0 &&
                x.Key.Kind is OriginalStockKind.ShipUnits or OriginalStockKind.ShipBoats)
            .Select(x => x.Key.ItemKind).Distinct().Order()
            .Select(kind => new OriginalWarehouseShip(kind, checked((byte)Count(OriginalStockKind.ShipUnits, kind)),
                checked((ushort)Count(OriginalStockKind.ShipBoats, kind)))).ToArray();
        var troops = balances.Where(x => x.Value > 0 && x.Key.Kind == OriginalStockKind.Troops)
            .OrderBy(x => x.Key.ItemKind).ThenBy(x => x.Key.Grade)
            .Select(x => new OriginalWarehouseTroop(x.Key.ItemKind, x.Key.Grade, checked((ushort)x.Value))).ToArray();
        // NEW_DESIGN placeholder: original Index semantics remain UNSEEN.
        // Never reinterpret our 64-bit optimistic storage version as that field.
        return new(stock.Key.BaseId, stock.Key.OutfitId, 0, ships, troops,
            checked((uint)Count(OriginalStockKind.Supplies)), checked((uint)Count(OriginalStockKind.Food)),
            checked((uint)Count(OriginalStockKind.Mineral)));
    }
}
