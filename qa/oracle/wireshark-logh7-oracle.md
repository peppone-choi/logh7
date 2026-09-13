# Wireshark capture filters for LOGH7 oracle runs

Status: PREPARED. Runtime packet captures are UNSEEN until the XP oracle VM exists and runs.

## Known target

- Original endpoint observed statically: `202.8.80.179`
- Original port observed statically: `47900`
- Isolation: host-only network only; do not expose the XP oracle VM to the public internet.

## Capture filters

Preferred sealed-oracle host-only capture:

```text
tcp port 47900 or host 202.8.80.179
```

Broader first-launch capture, if endpoint/port confirmation is the goal:

```text
tcp or udp
```

## Display filters

Primary:

```text
tcp.port == 47900 || ip.addr == 202.8.80.179
```

Handshake and reset triage:

```text
(tcp.port == 47900 || ip.addr == 202.8.80.179) && (tcp.flags.syn == 1 || tcp.flags.reset == 1 || tcp.analysis.retransmission)
```

Payload-bearing packets:

```text
(tcp.port == 47900 || ip.addr == 202.8.80.179) && tcp.len > 0
```

Exploratory redirect conversation filter after guest and fake-server IPs are assigned:

```text
ip.addr == <xp_guest_ip> && ip.addr == <fake_server_ip> && tcp.port == 47900
```

Use this only for `EXPLORATORY_REDIRECT` runs. A fake server IP, local controlled endpoint, hosts-file mapping, DNS change, route override, or host-only remapping of the hardcoded service address is endpoint redirection and cannot be promoted as `SEALED_ORACLE`.

## Evidence requirements

- Save raw `pcapng`.
- Export packet dissection as JSON only as an auxiliary view; raw capture remains authority.
- Record capture interface, guest IP, MAC addresses, original target endpoint, and wall-clock sync for every run.
- For `EXPLORATORY_REDIRECT`, additionally record fake-server IP, local controlled endpoint, and the exact route/DNS/IP override method.
- For `SEALED_ORACLE`, host-only networking means isolation only: target endpoint remains `202.8.80.179:47900`, and fake endpoints plus IP/DNS/route overrides are prohibited. Do not patch endpoint bytes.
