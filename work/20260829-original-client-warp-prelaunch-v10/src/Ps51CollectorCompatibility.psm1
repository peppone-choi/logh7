function ConvertTo-Ps51CollectorSource {
  [CmdletBinding()]
  param([Parameter(Mandatory=$true)][string]$Source)
  $replacements=[ordered]@{
    '[float]::IsFinite([single]$first.scaleX)'='(-not[float]::IsNaN([single]$first.scaleX)-and-not[float]::IsInfinity([single]$first.scaleX))'
    '[float]::IsFinite([single]$first.scaleY)'='(-not[float]::IsNaN([single]$first.scaleY)-and-not[float]::IsInfinity([single]$first.scaleY))'
  }
  $patched=$Source
  foreach($entry in $replacements.GetEnumerator()){
    $count=([regex]::Matches($patched,[regex]::Escape($entry.Key))).Count
    if($count-ne1){throw "PS5.1 compatibility source mismatch for $($entry.Key): expected=1 actual=$count"}
    $patched=$patched.Replace($entry.Key,$entry.Value)
  }
  return $patched
}
Export-ModuleMember -Function ConvertTo-Ps51CollectorSource
