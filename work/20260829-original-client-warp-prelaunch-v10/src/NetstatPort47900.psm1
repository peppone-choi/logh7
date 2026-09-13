function ConvertFrom-NetstatPort47900 {
  [CmdletBinding()]
  param([Parameter(Mandatory=$true)][AllowEmptyString()][string[]]$Lines)
  $pattern=[regex]::new('^\s*(TCP)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)\s*$',[Text.RegularExpressions.RegexOptions]::CultureInvariant)
  foreach($line in $Lines){
    $match=$pattern.Match($line)
    if(-not$match.Success){continue}
    $localEndpoint=$match.Groups[2].Value;$remoteEndpoint=$match.Groups[3].Value
    if(-not($localEndpoint.EndsWith(':47900',[StringComparison]::Ordinal)-or$remoteEndpoint.EndsWith(':47900',[StringComparison]::Ordinal))){continue}
    [pscustomobject][ordered]@{protocol=$match.Groups[1].Value;localEndpoint=$localEndpoint;remoteEndpoint=$remoteEndpoint;state=$match.Groups[4].Value;pid=[int]$match.Groups[5].Value}
  }
}
Export-ModuleMember -Function ConvertFrom-NetstatPort47900
