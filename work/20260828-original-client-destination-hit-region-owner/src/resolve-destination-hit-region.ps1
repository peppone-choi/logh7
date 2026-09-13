[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$SnapshotPath,
    [Parameter(Mandatory=$true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'

if (-not ('DestinationHitRegionMath' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Numerics;

public static class DestinationHitRegionMath {
    private static Matrix4x4 Matrix(float[] v) {
        if (v == null || v.Length != 16) throw new ArgumentException("Matrix requires 16 values");
        return new Matrix4x4(
            v[0],v[1],v[2],v[3], v[4],v[5],v[6],v[7],
            v[8],v[9],v[10],v[11], v[12],v[13],v[14],v[15]);
    }

    private static Matrix4x4 Combined(float[] world, float[] view, float[] projection) {
        return Matrix4x4.Multiply(Matrix4x4.Multiply(Matrix(world), Matrix(view)), Matrix(projection));
    }

    public static bool IsInvertible(float[] world, float[] view, float[] projection) {
        Matrix4x4 inverse;
        return Matrix4x4.Invert(Combined(world, view, projection), out inverse);
    }

    public static float[] Project(float[] world, float[] view, float[] projection,
                                  float viewportX, float viewportY, float viewportWidth, float viewportHeight,
                                  float minDepth, float maxDepth, float x, float y, float z) {
        Vector4 clip = Vector4.Transform(new Vector4(x, y, z, 1.0f), Combined(world, view, projection));
        if (Math.Abs(clip.W) < 1.0e-6f) return new float[] {0,0,0,0};
        float nx = clip.X / clip.W;
        float ny = clip.Y / clip.W;
        float nz = clip.Z / clip.W;
        return new float[] {
            1,
            viewportX + (1.0f + nx) * viewportWidth * 0.5f,
            viewportY + (1.0f - ny) * viewportHeight * 0.5f,
            minDepth + nz * (maxDepth - minDepth)
        };
    }

    private static bool Unproject(Matrix4x4 inverse,
                                  float viewportX, float viewportY, float viewportWidth, float viewportHeight,
                                  float minDepth, float maxDepth, float sx, float sy, float sz, out Vector3 result) {
        result = new Vector3();
        Vector4 source = new Vector4(
            ((sx - viewportX) / viewportWidth) * 2.0f - 1.0f,
            1.0f - ((sy - viewportY) / viewportHeight) * 2.0f,
            (sz - minDepth) / (maxDepth - minDepth),
            1.0f);
        Vector4 transformed = Vector4.Transform(source, inverse);
        if (Math.Abs(transformed.W) < 1.0e-6f) return false;
        result = new Vector3(transformed.X / transformed.W, transformed.Y / transformed.W, transformed.Z / transformed.W);
        return true;
    }

    private static bool CandidateWithInverse(Matrix4x4 inverse,
                                    float viewportX, float viewportY, float viewportWidth, float viewportHeight,
                                    float minDepth, float maxDepth, float sx, float sy,
                                    out int gridX, out int gridY, out Vector3 hit) {
        gridX = 0; gridY = 0; hit = new Vector3();
        Vector3 nearPoint, farPoint;
        if (!Unproject(inverse, viewportX, viewportY, viewportWidth, viewportHeight,
                       minDepth, maxDepth, sx, sy, 0.1f, out nearPoint) ||
            !Unproject(inverse, viewportX, viewportY, viewportWidth, viewportHeight,
                       minDepth, maxDepth, sx, sy, 100.0f, out farPoint)) return false;
        Vector3 direction = farPoint - nearPoint;
        if (Math.Abs(direction.Y) < 1.0e-6f) return false;
        float t = -nearPoint.Y / direction.Y;
        hit = nearPoint + direction * t;
        gridX = (int)Math.Truncate(hit.X + 50.0f);
        gridY = (int)Math.Truncate(25.0f - hit.Z);
        return true;
    }

    public static float[] Candidate(float[] world, float[] view, float[] projection,
                                    float viewportX, float viewportY, float viewportWidth, float viewportHeight,
                                    float minDepth, float maxDepth, float sx, float sy) {
        Matrix4x4 inverse;
        if (!Matrix4x4.Invert(Combined(world, view, projection), out inverse)) return new float[] {0,0,0,0,0,0};
        int gridX, gridY; Vector3 hit;
        if (!CandidateWithInverse(inverse, viewportX, viewportY, viewportWidth, viewportHeight,
                                  minDepth, maxDepth, sx, sy, out gridX, out gridY, out hit)) return new float[] {0,0,0,0,0,0};
        return new float[] {1, gridX, gridY, hit.X, hit.Y, hit.Z};
    }

    public static int[] EnumerateMatchingPixels(float[] world, float[] view, float[] projection,
                                    float viewportX, float viewportY, int viewportWidth, int viewportHeight,
                                    float minDepth, float maxDepth, int targetX, int targetY) {
        Matrix4x4 inverse;
        if (!Matrix4x4.Invert(Combined(world, view, projection), out inverse)) return new int[0];
        List<int> points = new List<int>();
        for (int y = 1; y < viewportHeight; y++) {
            for (int x = 1; x < viewportWidth; x++) {
                int gridX, gridY; Vector3 hit;
                if (CandidateWithInverse(inverse, viewportX, viewportY, viewportWidth, viewportHeight,
                                         minDepth, maxDepth, x, y, out gridX, out gridY, out hit) &&
                    gridX == targetX && gridY == targetY) {
                    points.Add(x); points.Add(y);
                }
            }
        }
        return points.ToArray();
    }
}
'@
}

function Publish([object]$Value) {
    $json = $Value | ConvertTo-Json -Depth 10
    $canonical = ($json -replace "`r?`n", "`n") + "`n"
    $parent = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    [IO.File]::WriteAllText($OutputPath, $canonical, [Text.UTF8Encoding]::new($false))
    $json
}

$snapshot = Get-Content -LiteralPath $SnapshotPath -Raw -Encoding UTF8 | ConvertFrom-Json
$blockers = [Collections.Generic.List[string]]::new()
$sourceMode = if ($snapshot.sourceMode) { [string]$snapshot.sourceMode } else { 'OFFLINE_FIXTURE' }

if ($snapshot.schemaVersion -ne 1) { $blockers.Add('SNAPSHOT_SCHEMA_NOT_1') }
if ($sourceMode -notin @('OFFLINE_FIXTURE','LIVE_READONLY')) { $blockers.Add('SOURCE_MODE_NOT_ALLOWED') }
if ($sourceMode -eq 'LIVE_READONLY') { $blockers.Add('LIVE_SNAPSHOT_INDEPENDENT_BINDING_REQUIRED') }
if (-not $snapshot.stageEligible) { $blockers.Add('DESTINATION_STAGE_NOT_ELIGIBLE') }
if ($snapshot.targetValidity.valid -ne $true) { $blockers.Add('TARGET_GRID_VALIDITY_NOT_PROVEN') }
if ($null -eq $snapshot.viewport -or $snapshot.viewport.width -le 1 -or $snapshot.viewport.height -le 1 -or
    $snapshot.viewport.width -gt 8192 -or $snapshot.viewport.height -gt 8192 -or
    $snapshot.viewport.maxDepth -eq $snapshot.viewport.minDepth) { $blockers.Add('VIEWPORT_INVALID') }
if ($snapshot.viewport.x -ne 0 -or $snapshot.viewport.y -ne 0) { $blockers.Add('VIEWPORT_ORIGIN_NOT_CLIENT_ZERO') }
if ($snapshot.target.gridX -lt 0 -or $snapshot.target.gridX -ge 100 -or
    $snapshot.target.gridY -lt 0 -or $snapshot.target.gridY -ge 50) { $blockers.Add('TARGET_GRID_OUT_OF_RANGE') }

[float[]]$world = @($snapshot.world)
[float[]]$view = @($snapshot.view)
[float[]]$projection = @($snapshot.projection)
if ($world.Count -ne 16 -or $view.Count -ne 16 -or $projection.Count -ne 16) {
    $blockers.Add('MATRIX_SHAPE_INVALID')
}
elseif (-not [DestinationHitRegionMath]::IsInvertible($world, $view, $projection)) {
    $blockers.Add('COMBINED_MATRIX_NOT_INVERTIBLE')
}

$base = [ordered]@{
    schemaVersion = 1
    status = 'UNBOUND'
    bindingEligible = $false
    target = [ordered]@{ gridX=[int]$snapshot.target.gridX; gridY=[int]$snapshot.target.gridY }
    blockers = @($blockers)
    projectedCellCenter = $null
    region = $null
    safePoint = $null
    provenance = [ordered]@{
        sourceMode = $sourceMode
        claimedOriginalRuntimeObserved = $snapshot.provenance.originalRuntimeObserved -eq $true
        originalRuntimeObserved = $false
        playerVisible = $false
    }
    operations = [ordered]@{ writes=0; gameInputs=0; liveOperations=0 }
    permitIssued = $false
}

if ($blockers.Count -ne 0) { Publish $base; return }

$vx = [float]$snapshot.viewport.x; $vy = [float]$snapshot.viewport.y
$vw = [float]$snapshot.viewport.width; $vh = [float]$snapshot.viewport.height
$minDepth = [float]$snapshot.viewport.minDepth; $maxDepth = [float]$snapshot.viewport.maxDepth
$targetX = [int]$snapshot.target.gridX; $targetY = [int]$snapshot.target.gridY
$worldX = [float]($targetX - 49.5); $worldZ = [float](24.5 - $targetY)
$projected = [DestinationHitRegionMath]::Project($world,$view,$projection,$vx,$vy,$vw,$vh,$minDepth,$maxDepth,$worldX,0.0,$worldZ)
if ($projected[0] -eq 0) { $base.blockers = @('TARGET_CENTER_PROJECTION_FAILED'); Publish $base; return }

$matched = @{}
$spans = [Collections.Generic.List[object]]::new()
$minX = [int]::MaxValue; $minY = [int]::MaxValue; $maxX = [int]::MinValue; $maxY = [int]::MinValue
$pointPairs = [DestinationHitRegionMath]::EnumerateMatchingPixels($world,$view,$projection,$vx,$vy,[int]$vw,[int]$vh,$minDepth,$maxDepth,$targetX,$targetY)
for($index=0;$index -lt $pointPairs.Length;$index+=2){
    $x=$pointPairs[$index];$y=$pointPairs[$index+1]
    $candidate=[DestinationHitRegionMath]::Candidate($world,$view,$projection,$vx,$vy,$vw,$vh,$minDepth,$maxDepth,[float]$x,[float]$y)
    $matched["$x,$y"]=$candidate
    if($x -lt $minX){$minX=$x};if($x -gt $maxX){$maxX=$x}
    if($y -lt $minY){$minY=$y};if($y -gt $maxY){$maxY=$y}
}
foreach($y in @($matched.Keys|ForEach-Object{[int]$_.Split(',')[1]}|Sort-Object -Unique)){
    $xs=@($matched.Keys|Where-Object{[int]$_.Split(',')[1] -eq $y}|ForEach-Object{[int]$_.Split(',')[0]}|Sort-Object)
    $spanStart=$xs[0];$previous=$xs[0]
    for($i=1;$i -lt $xs.Count;$i++){
        if($xs[$i] -ne $previous+1){$spans.Add([ordered]@{y=$y;left=$spanStart;rightExclusive=$previous+1});$spanStart=$xs[$i]}
        $previous=$xs[$i]
    }
    $spans.Add([ordered]@{y=$y;left=$spanStart;rightExclusive=$previous+1})
}

if ($matched.Count -eq 0) { $base.blockers = @('TARGET_GRID_HAS_NO_CLIENT_PIXELS'); Publish $base; return }

$safe = $null; $safeDistance = [double]::PositiveInfinity
foreach ($entry in $matched.GetEnumerator()) {
    $parts = $entry.Key.Split(','); $x = [int]$parts[0]; $y = [int]$parts[1]
    $margin = 0
    :marginLoop for ($radius = 1; $radius -le 32; $radius++) {
        for ($dy = -$radius; $dy -le $radius; $dy++) {
            for ($dx = -$radius; $dx -le $radius; $dx++) {
                if ([Math]::Max([Math]::Abs($dx),[Math]::Abs($dy)) -ne $radius) { continue }
                if (-not $matched.ContainsKey("$($x+$dx),$($y+$dy)")) { break marginLoop }
            }
        }
        $margin = $radius
    }
    if ($margin -lt 1) { continue }
    $distance = [Math]::Pow($x - [double]$projected[1],2) + [Math]::Pow($y - [double]$projected[2],2)
    if ($distance -lt $safeDistance -or ($distance -eq $safeDistance -and ($null -eq $safe -or $y -lt $safe.y -or ($y -eq $safe.y -and $x -lt $safe.x)))) {
        $safeDistance = $distance
        $safe = [ordered]@{
            x=$x; y=$y; sameGridChebyshevMargin=$margin
            replayedGridX=[int]$entry.Value[1]; replayedGridY=[int]$entry.Value[2]
        }
    }
}

if ($null -eq $safe) { $base.blockers = @('TARGET_GRID_HAS_NO_MARGIN_1_SAFE_PIXEL'); Publish $base; return }

$base.status = 'BOUND_OFFLINE_FIXTURE'
$base.bindingEligible = $true
$base.blockers = @()
$base.projectedCellCenter = [ordered]@{
    x=[Math]::Round([double]$projected[1], 4)
    y=[Math]::Round([double]$projected[2], 4)
    depth=[Math]::Round([double]$projected[3], 4)
}
$base.region = [ordered]@{
    pixelCount = $matched.Count
    scanlineSpans = @($spans)
    boundingRectangle = [ordered]@{ left=$minX; top=$minY; rightExclusive=$maxX+1; bottomExclusive=$maxY+1 }
}
$base.safePoint = $safe
Publish $base
