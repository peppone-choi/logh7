$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$module=Join-Path $root 'src/NetstatPort47900.psm1'
if(-not(Test-Path -LiteralPath $module)){throw 'RED: netstat port47900 parser module missing'}
Import-Module $module -Force
$lines=@(
 '',
 'Active Connections',
 '  TCP    0.0.0.0:47900          0.0.0.0:0              LISTENING       8668',
 '  TCP    202.8.80.179:49722     202.8.80.179:47900     ESTABLISHED     3448',
 '  TCP    202.8.80.179:47900     202.8.80.179:49722     ESTABLISHED     8668',
 '  TCP    127.0.0.1:50000        127.0.0.1:50001        ESTABLISHED     1000'
)
$rows=@(ConvertFrom-NetstatPort47900 -Lines $lines)
$n=0
function Eq($name,$actual,$expected){$script:n++;if($actual-ne$expected){throw "$name expected=$expected actual=$actual"}}
Eq 'row count' $rows.Count 3
Eq 'listener protocol' $rows[0].protocol 'TCP';Eq 'listener local' $rows[0].localEndpoint '0.0.0.0:47900';Eq 'listener remote' $rows[0].remoteEndpoint '0.0.0.0:0';Eq 'listener state' $rows[0].state 'LISTENING';Eq 'listener pid' $rows[0].pid 8668
Eq 'client local' $rows[1].localEndpoint '202.8.80.179:49722';Eq 'client remote' $rows[1].remoteEndpoint '202.8.80.179:47900';Eq 'client state' $rows[1].state 'ESTABLISHED';Eq 'client pid' $rows[1].pid 3448
Eq 'server local' $rows[2].localEndpoint '202.8.80.179:47900';Eq 'server remote' $rows[2].remoteEndpoint '202.8.80.179:49722';Eq 'server pid' $rows[2].pid 8668
[ordered]@{result='PASS';cases=1;assertions=$n}|ConvertTo-Json
