using System.Text.Json;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalBattlefieldSpawn(
    float X,
    float Y,
    float Z,
    float Direction);

public sealed record OriginalBattlefieldTemplate(
    string Id,
    uint Grid,
    string EvidenceStatus,
    OriginalBattlefieldSpawn PlayerSpawn,
    OriginalBattlefieldSpawn EnemySpawn,
    OriginalBattlefieldSpawn BaseSpawn,
    IReadOnlyList<OriginalBlackHoleObstacle> BlackHoles,
    IReadOnlyList<OriginalAsteroidBeltObstacle> AsteroidBelts,
    IReadOnlyList<OriginalGasCloudObstacle> GasClouds,
    IReadOnlyList<OriginalAbnormalGravityObstacle> AbnormalGravities,
    IReadOnlyList<OriginalCircleObstacle> Circles)
{
    // Null means ownership was not authored/recovered, not an empty or captured field.
    public IReadOnlyList<OriginalInformationBaseRecord>? BaseInformation { get; init; }
    public IReadOnlyList<OriginalTacticalBaseRecord>? TacticalBases { get; init; }
    // Same provenance as this template. Null is unrecovered content, not an invented facility.
    public IReadOnlyList<OriginalBaseInstitutions>? BaseInstitutions { get; init; }
    // The NPC's affiliation is battlefield content, never a function of its
    // current viewer. Null is missing content, not an inferred opposing side.
    public byte? EnemyPower { get; init; }
    // AUTHORED initial legacy-NPC merit. These values are independent of viewers.
    public byte EnemyRank { get; init; } = OriginalAuthoredPlayableCatalog.StartingRank;
    public uint EnemyAchievement { get; init; }
    // Authored content controls automatic NPC placement, not permanent invulnerability.
    public bool SpawnEnemy { get; init; } = true;
    public IReadOnlyList<OriginalBattlefieldFleet>? Fleets { get; init; }

    public IReadOnlyList<OriginalInformationBaseRecord> ProjectBaseInformation(uint actualGrid) =>
        BaseInformation?.Where(record => record.Grid == actualGrid).ToArray() ?? [];

    public OriginalTacticalObstacleResponse ProjectObstacles(uint actualGrid) =>
        new(
            actualGrid,
            BlackHoles,
            AsteroidBelts,
            GasClouds,
            AbnormalGravities,
            Circles);
}

/// <summary>
/// Server-owned tactical-field content. The original service database is lost,
/// so every row must state whether it is original evidence or an authored
/// replacement. Grid 0 is the explicit fallback template.
/// </summary>
public sealed class OriginalBattlefieldCatalog
{
    public bool HasFriendlyPublicPort(uint grid, uint baseId, uint power)
    {
        if (!TryResolveBaseGrid(baseId,out var destinationGrid) || destinationGrid != grid) return false;
        var field = Resolve(grid);
        return field.ProjectBaseInformation(grid).Any(b=>b.Id==baseId && b.Power==power) &&
            (field.BaseInstitutions ?? []).Where(b=>b.Id==baseId)
                .SelectMany(b=>b.Institutions).Where(i=>i.Kind==4)
                .SelectMany(i=>i.Spots).Any(s=>s.Kind==6 && s.Id!=0);
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
    };

    private readonly IReadOnlyDictionary<uint, OriginalBattlefieldTemplate> _templates;
    // Global031D definitions, not the per-grid tactical spawn or031F ownership list.
    // Null preserves legacy catalogs; explicit[] means no Base definitions.
    public IReadOnlyList<OriginalStaticBaseRecord>? StaticBases { get; }
    private readonly IReadOnlyDictionary<uint, ushort> _baseGrids;

    private OriginalBattlefieldCatalog(
        IReadOnlyDictionary<uint, OriginalBattlefieldTemplate> templates,
        IReadOnlyList<OriginalStaticBaseRecord>? staticBases)
    {
        _templates = templates;
        StaticBases = staticBases;
        _baseGrids = staticBases is null
            ? new Dictionary<uint, ushort> { [OriginalAuthoredPlayableCatalog.BaseId] = OriginalAuthoredPlayableCatalog.CurrentGridCell }
            : staticBases.ToDictionary(record => record.Id, record => record.Grid);
    }

    /// <summary>
    /// Grid a base stands in. A retreat needs it: the withdrawing unit leaves
    /// the battlefield for the grid its own base occupies, and that join lives
    /// only in the global base definitions.
    /// </summary>
    public bool TryResolveBaseGrid(uint baseId, out uint grid)
    {
        if (_baseGrids.TryGetValue(baseId, out var resolved))
        {
            grid = resolved;
            return true;
        }
        grid = 0;
        return false;
    }

    public static OriginalBattlefieldCatalog LoadDefault() => Load(
        Path.Combine(AppContext.BaseDirectory, "battlefields", "catalog.json"));

    public static OriginalBattlefieldCatalog LoadConfigured(string? path)
    {
        if(string.IsNullOrWhiteSpace(path)) return LoadDefault();
        if(!Path.IsPathFullyQualified(path))
            throw new ArgumentException("Battlefield catalog path must be absolute.",nameof(path));
        return Load(path);
    }

    // Every authored ship with the grid its content places it on. A unit whose
    // authored grid moved must be reconciled even while the player stands on a
    // different grid, so this is deliberately catalog-wide.
    public IEnumerable<(uint Unit, ushort Kind, uint Grid)> AuthoredShips()
    {
        foreach (var template in _templates.Values)
            foreach (var fleet in template.Fleets ?? [])
                foreach (var ship in fleet.Ships)
                    yield return (ship.Id, ship.Kind, template.Grid);
    }

    // Hull count for one authored ship. An unauthored ship keeps the caller's
    // ordinary-unit default; this never invents a complement of its own.
    public ushort ShipComplement(uint unit, ushort fallback)
    {
        foreach (var template in _templates.Values)
            foreach (var fleet in template.Fleets ?? [])
                foreach (var ship in fleet.Ships)
                    if (ship.Id == unit)
                        // The served per-kind template is the single source of
                        // truth, so the client and the authority never disagree
                        // about how many hulls a unit still has.
                        return OriginalSubordinateShipCatalog.ComplementFor(ship.Kind);
        return fallback;
    }

    // Authored content decides the posture, never the viewer or the current
    // damage state. An unknown outfit keeps the always-engage default.
    public bool IsDefensiveOutfit(uint outfit)
    {
        foreach (var template in _templates.Values)
            foreach (var fleet in template.Fleets ?? [])
                if (fleet.Id == outfit) return fleet.Defensive;
        return false;
    }

    /// <summary>
    /// Whether an outfit carries an authored support role - the 工作艦 of 修理's own
    /// description or the 補給艦 of 補給's. NEW_DESIGN: the original names the
    /// vessel in words and this authority has not recovered which ship kind id is
    /// which, so the battlefield states the role in words too rather than
    /// guessing a class.
    /// </summary>
    public bool OutfitCarriesRole(uint grid, uint outfit, string role)
    {
        if (outfit == 0) return false;
        foreach (var template in _templates.Values)
        {
            if (template.Grid != 0 && template.Grid != grid) continue;
            foreach (var fleet in template.Fleets ?? [])
            {
                if (fleet.Id != outfit) continue;
                return string.Equals(fleet.Role, role, StringComparison.Ordinal);
            }
        }
        return false;
    }

    public OriginalTacticalParticipantSnapshot? ProjectFleetUnit(uint unit, uint grid)
    {
        foreach (var template in _templates.Values)
            foreach (var fleet in template.Fleets ?? [])
                if (fleet.Ships.Any(ship => ship.Id == unit))
                    return fleet.Project(grid).Single(participant => participant.Unit.Id == unit);
        return null;
    }

    /// <summary>
    /// The authored fleet an outfit id names, if the content states one.
    /// </summary>
    /// <remarks>
    /// The tactical label a client draws comes from the fleet's character record,
    /// so the session needs the fleet behind an outfit to find its commander name.
    /// </remarks>
    public OriginalBattlefieldFleet? FindOutfit(uint outfit)
    {
        foreach (var template in _templates.Values)
            foreach (var fleet in template.Fleets ?? [])
                if (fleet.Id == outfit) return fleet;
        return null;
    }

    public static OriginalBattlefieldCatalog Load(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        return Parse(File.ReadAllText(path));
    }

    public static OriginalBattlefieldCatalog Parse(string json)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(json);
        var document = JsonSerializer.Deserialize<OriginalBattlefieldCatalogDocument>(
            json,
            JsonOptions) ?? throw new InvalidDataException("battlefield catalog is empty");
        if (document.SchemaVersion != 1)
        {
            throw new InvalidDataException(
                $"unsupported battlefield schema {document.SchemaVersion}");
        }
        if (document.Templates is null || document.Templates.Count == 0)
        {
            throw new InvalidDataException("battlefield catalog has no templates");
        }

        var templates = new Dictionary<uint, OriginalBattlefieldTemplate>();
        var fleetIdentities = new HashSet<uint>();
        foreach (var template in document.Templates)
        {
            Validate(template);
            var fleets = template.Fleets ?? [];
            if (fleets.Count > 100 || (fleets.Count > 0 && template.Grid == 0))
                throw new InvalidDataException("Fleet content requires an exact grid and at most 100 outfits");
            if (fleets.Sum(f => f.Ships?.Count ?? 0) > 598)
                throw new InvalidDataException("Fleet content exceeds native unit capacity with player/legacy NPC reserve");
            foreach (var fleet in fleets)
            {
                ValidateIdentity(fleet.Id);
                if (fleet.Power > OriginalFaction.Pirates || fleet.Ships is null || fleet.Ships.Count == 0)
                    throw new InvalidDataException("Fleet requires defined power and nonempty ships");
                foreach (var ship in fleet.Ships)
                {
                    ValidateIdentity(ship.Id);
                    ValidateSpawn(template.Id, "fleetShip", ship.Spawn);
                    // An authored complement is a readable assertion of what the
                    // served template already says; it must not contradict it.
                    if (ship.Complement is { } complement &&
                        complement != OriginalSubordinateShipCatalog.ComplementFor(ship.Kind))
                        throw new InvalidDataException(
                            "Authored ship complement must match the served template for its kind");
                    // Only normal-ship slots actually served by today's030B catalog.
                    // Extend with the content catalog, not an assumed native table length.
                    if (!OriginalSubordinateShipCatalog.Supports(ship.Kind))
                        throw new InvalidDataException("Fleet ship kind has no served normal-ship template");
                }
            }
            if (!templates.TryAdd(template.Grid, template))
            {
                throw new InvalidDataException(
                    $"duplicate battlefield grid {template.Grid}");
            }
        }
        if (!templates.ContainsKey(0))
        {
            throw new InvalidDataException(
                "battlefield catalog requires an explicit grid 0 fallback");
        }
        if (document.StaticBases is not null)
            OriginalStaticBaseRecord.ValidateAll(document.StaticBases);
        var catalog = new OriginalBattlefieldCatalog(templates, document.StaticBases);
        foreach (var template in templates.Values) catalog.ValidateBaseJoins(template);
        return catalog;

        void ValidateIdentity(uint id)
        {
            // Authored namespace, distinct from persisted player IDs and legacy7F NPC.
            if (id < 0x7e000000 || id > 0x7effffff || !fleetIdentities.Add(id))
                throw new InvalidDataException("Fleet/ship identity outside reserved namespace or duplicated");
        }
    }

    public OriginalBattlefieldTemplate Resolve(uint grid) =>
        _templates.TryGetValue(grid, out var exact) ? exact : _templates[0];

    public byte[] EncodeInstitutionFrame(uint grid) => OriginalInstitutionCodec.EncodeResponse(
        (Resolve(grid).BaseInstitutions ?? []).Where(record => _baseGrids[record.Id] == grid).ToArray());

    public IReadOnlyList<OriginalTacticalBaseRecord> ProjectTacticalBases(uint grid,
        IReadOnlyList<uint>? requestedIds = null)
    {
        var template = Resolve(grid);
        var instances = template.TacticalBases;
        if (instances is null)
        {
            // Compatibility for catalogs predating explicit global and tactical definitions.
            // An explicit empty list or modern catalog never fabricates an instance.
            if (StaticBases is not null) return [];
            var spawn = template.BaseSpawn;
            instances = [new(OriginalAuthoredPlayableCatalog.BaseId,
                spawn.X, spawn.Y, spawn.Z, 0, 0, 0, 100)];
        }
        return instances.Where(record => _baseGrids.TryGetValue(record.Id, out var baseGrid) &&
            baseGrid == grid && (requestedIds is null || requestedIds.Contains(record.Id))).ToArray();
    }

    public IReadOnlyList<OriginalBasePositionRecord> ProjectBasePositions(uint grid,
        IReadOnlyList<uint>? requestedIds = null) =>
        ProjectTacticalBases(grid, requestedIds).Select(record =>
            new OriginalBasePositionRecord(record.Id,record.X,record.Y,record.Z)).ToArray();

    public IReadOnlyList<OriginalInformationBaseRecord>? ProjectBaseObjectives(uint grid)
    {
        var information = Resolve(grid).BaseInformation;
        if (information is null) return null;
        var local = information.Where(record => record.Grid == grid).ToArray();
        var known = local.Select(record => record.Id).ToHashSet();
        return _baseGrids.Any(pair => pair.Value == grid && !known.Contains(pair.Key)) ? null : local;
    }

    public IReadOnlyList<byte[]> EncodeBaseInformationFrames(uint grid) =>
        EncodeBatches(Resolve(grid).ProjectBaseInformation(grid), 4, OriginalInformationBaseCodec.EncodeResponse);

    public IReadOnlyList<byte[]> EncodeBasePositionFrames(uint grid) =>
        EncodeBatches(ProjectBasePositions(grid), OriginalSystemSceneCodec.MaximumBasePositionCount,
            records => OriginalSystemSceneCodec.EncodeBasePositions(new(records)));

    private static IReadOnlyList<byte[]> EncodeBatches<T>(IReadOnlyList<T> records, int maximum,
        Func<IReadOnlyList<T>, byte[]> encode) =>
        records.Count == 0 ? [encode([])] : records.Chunk(maximum).Select(chunk => encode(chunk)).ToArray();

    private void ValidateBaseJoins(OriginalBattlefieldTemplate template)
    {
        var seen = new HashSet<uint>();
        foreach (var record in template.TacticalBases ?? [])
        {
            if (!_baseGrids.ContainsKey(record.Id) || !seen.Add(record.Id))
                throw new InvalidDataException("Tactical Base has an undefined or duplicate ID");
            if (!float.IsFinite(record.X) || !float.IsFinite(record.Y) || !float.IsFinite(record.Z))
                throw new InvalidDataException("Tactical Base coordinates must be finite");
        }
        // ORIGINAL_STATIC: 004C32A0 creates category0 entities from tactical
        // Base records; 004C7EF0 and 004BE4D0 use ten slots at manager+174124.
        // This scene capacity is separate from the sixteen-record wire array.
        if ((template.TacticalBases ?? []).GroupBy(record => _baseGrids[record.Id])
            .Any(group => group.Count() > 10))
            throw new InvalidDataException("Tactical Base count exceeds native capacity10 on one grid");
        seen.Clear();
        foreach (var record in template.BaseInformation ?? [])
        {
            if (record is null || !_baseGrids.TryGetValue(record.Id, out var grid) ||
                grid != record.Grid || !seen.Add(record.Id))
                throw new InvalidDataException("Base ownership has an undefined, duplicate or wrong-grid ID");
        }
        seen.Clear();
        foreach (var record in template.BaseInstitutions ?? [])
        {
            if (record is null || !_baseGrids.ContainsKey(record.Id) || !seen.Add(record.Id))
                throw new InvalidDataException("Institution Base has an undefined or duplicate ID");
            if (record.Institutions is null || record.Institutions.Count > 36)
                throw new InvalidDataException("Institution array is missing or exceeds capacity36");
            var institutionIds = new HashSet<uint>();
            var spotIds = new HashSet<uint>();
            foreach (var institution in record.Institutions)
            {
                if (institution is null || institution.Id == 0 || !institutionIds.Add(institution.Id))
                    throw new InvalidDataException("Institution has a missing, zero or duplicate ID");
                if (institution.Spots is null || institution.Spots.Count > 20)
                    throw new InvalidDataException("Institution spots are missing or exceed capacity20");
                foreach (var spot in institution.Spots)
                    if (spot is null || spot.Id == 0 || !spotIds.Add(spot.Id))
                        throw new InvalidDataException("Institution spot has a missing, zero or duplicate ID");
            }
        }
        // 004BAE8E/004C4170 replace the entire four-base cache, not append batches.
        if ((template.BaseInstitutions ?? []).GroupBy(record => _baseGrids[record.Id])
            .Any(group => group.Count() > 4))
            throw new InvalidDataException("Institution Base count exceeds native capacity4 on one grid");
    }

    private static void Validate(OriginalBattlefieldTemplate template)
    {
        if (template.EnemyPower is null or > OriginalFaction.Pirates)
            throw new InvalidDataException($"battlefield {template.Id} requires a defined enemyPower (0..4)");
        if (string.IsNullOrWhiteSpace(template.Id))
        {
            throw new InvalidDataException("battlefield id is required");
        }
        if (template.EvidenceStatus is not (
            "ORIGINAL_OBSERVED" or
            "ORIGINAL_STATIC" or
            "CANDIDATE" or
            "NEW_DESIGN"))
        {
            throw new InvalidDataException(
                $"battlefield {template.Id} has invalid evidenceStatus");
        }
        ValidateSpawn(template.Id, "playerSpawn", template.PlayerSpawn);
        ValidateSpawn(template.Id, "enemySpawn", template.EnemySpawn);
        ValidateSpawn(template.Id, "baseSpawn", template.BaseSpawn);
        ValidateList(template.BlackHoles, OriginalSystemSceneCodec.MaximumBlackHoleCount, template.Id);
        ValidateList(template.AsteroidBelts, OriginalSystemSceneCodec.MaximumAsteroidBeltCount, template.Id);
        ValidateList(template.GasClouds, OriginalSystemSceneCodec.MaximumGasCloudCount, template.Id);
        ValidateList(
            template.AbnormalGravities,
            OriginalSystemSceneCodec.MaximumAbnormalGravityCount,
            template.Id);
        ValidateList(template.Circles, OriginalSystemSceneCodec.MaximumCircleCount, template.Id);

        // ORIGINAL_STATIC: 004C7EF0(category2) allocates ten shared slots at
        // manager+17991C, stride8E0. 004BE520 rendering and 004B2C80 effects
        // consume that same pool. Packed0347 per-family limits are separate.
        var obstacleCount = template.BlackHoles.Count + template.AsteroidBelts.Count +
            template.GasClouds.Count + template.AbnormalGravities.Count + template.Circles.Count;
        if (obstacleCount > 10)
        {
            throw new InvalidDataException(
                $"battlefield {template.Id} has {obstacleCount} obstacles; native shared capacity is 10");
        }

        var ids = new HashSet<uint>();
        var modelSelections = template.BlackHoles.Select(value => (value.Id, value.ModelFile))
            .Concat(template.AsteroidBelts.Select(value => (value.Id, value.ModelFile)))
            .Concat(template.GasClouds.Select(value => (value.Id, value.ModelFile)))
            .Concat(template.AbnormalGravities.Select(value => (value.Id, value.ModelFile)))
            .Concat(template.Circles.Select(value => (value.Id, value.ModelFile)));
        foreach (var (id, modelFile) in modelSelections)
        {
            if (id == 0 || !ids.Add(id))
            {
                throw new InvalidDataException(
                    $"battlefield {template.Id} has a zero or duplicate obstacle id");
            }
            if (modelFile > OriginalSystemSceneCodec.MaximumObstacleModelFile)
            {
                throw new InvalidDataException(
                    $"battlefield {template.Id} modelFile {modelFile} has no client model table entry");
            }
        }
    }

    private static void ValidateSpawn(
        string id,
        string name,
        OriginalBattlefieldSpawn spawn)
    {
        if (!float.IsFinite(spawn.X) ||
            !float.IsFinite(spawn.Y) ||
            !float.IsFinite(spawn.Z) ||
            !float.IsFinite(spawn.Direction))
        {
            throw new InvalidDataException($"battlefield {id} {name} is not finite");
        }
    }

    private static void ValidateList<T>(
        IReadOnlyList<T>? records,
        int maximum,
        string id)
    {
        if (records is null || records.Count > maximum)
        {
            throw new InvalidDataException(
                $"battlefield {id} has a missing or oversized obstacle array");
        }
    }

    private sealed record OriginalBattlefieldCatalogDocument(
        int SchemaVersion,
        IReadOnlyList<OriginalBattlefieldTemplate> Templates,
        IReadOnlyList<OriginalStaticBaseRecord>? StaticBases);
}
