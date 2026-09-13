param([Parameter(Mandatory=$true)][int]$ExpectedPid,[Parameter(Mandatory=$true)][string]$ReceiptPath)
$ErrorActionPreference='Continue'
$tcp=@(Get-NetTCPConnection -OwningProcess $ExpectedPid -ErrorAction SilentlyContinue | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)->$($_.RemoteAddress):$($_.RemotePort) $($_.State)" })
$udp=@(Get-NetUDPEndpoint -OwningProcess $ExpectedPid -ErrorAction SilentlyContinue | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" })
$dnsCache=@(Get-DnsClientCache -ErrorAction SilentlyContinue | Select-Object -First 40 | ForEach-Object { "$($_.Entry) -> $($_.Data) ($($_.Status))" })
$dnsServers=@(Get-DnsClientServerAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | ForEach-Object { "$($_.InterfaceAlias): $($_.ServerAddresses -join ',')" })
$ip=@(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | ForEach-Object { "$($_.InterfaceAlias) $($_.IPAddress)/$($_.PrefixLength)" })
$gw=@(Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | ForEach-Object { "$($_.InterfaceAlias) via $($_.NextHop)" })
$sw=[Diagnostics.Stopwatch]::StartNew(); $dnsTest=$null; try { $dnsTest = @(Resolve-DnsName 'www.msftconnecttest.com' -DnsOnly -QuickTimeout -ErrorAction Stop | Select-Object -First 1 | ForEach-Object { "$($_.Name) $($_.IPAddress)" }) } catch { $dnsTest = @("DNS_FAIL: $($_.Exception.Message)") }; $dnsMs=$sw.ElapsedMilliseconds
$sw.Restart(); $inet=$null; try { $inet = (Test-NetConnection -ComputerName 8.8.8.8 -Port 53 -InformationLevel Quiet -WarningAction SilentlyContinue) } catch { $inet='ERR' }; $inetMs=$sw.ElapsedMilliseconds
$hosts=@(Get-Content 'C:\Windows\System32\drivers\etc\hosts' -ErrorAction SilentlyContinue | Where-Object { $_ -match '^\s*[0-9]' } | ForEach-Object { [string]$_ })
$p=Get-Process -Id $ExpectedPid -ErrorAction SilentlyContinue
$threads=@(); try { $threads=@($p.Threads | ForEach-Object { [ordered]@{ id=$_.Id; state=[string]$_.ThreadState; wait=$(if($_.ThreadState -eq 'Wait'){[string]$_.WaitReason}else{$null}); cpuMs=[int]$_.TotalProcessorTime.TotalMilliseconds } }) } catch {}
$res=[ordered]@{ capturedAtUtc=[datetime]::UtcNow.ToString('o'); cpuSeconds=$(if($p){[math]::Round($p.TotalProcessorTime.TotalSeconds,2)}else{$null}); tcp=$tcp; udp=$udp; dnsCache=$dnsCache; dnsServers=$dnsServers; ip=$ip; gateway=$gw; dnsTest=$dnsTest; dnsMs=$dnsMs; internet53=$inet; internetMs=$inetMs; hosts=$hosts; threads=$threads }
[IO.File]::WriteAllText($ReceiptPath,($res|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false)); exit 0
