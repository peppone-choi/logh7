# Handoff: D01 BootFirst RESOURCE_LOADER

## Completed unit

- `RECOVERY:D01:RESOURCE_LOADER:C7063B6F6EE54AC7`
- bounded status: `PASS`
- overall status: `INCOMPLETE / STRUCTURAL_FATAL`

`BootFirst.exe` is now proven to be an update-client process bootstrap rather than a game asset. Its resource-loader boundary is scope-qualified `NOT_APPLICABLE`, while the graph preserves the exact structural relation `BootFirst.exe LAUNCHES_PROCESS Gin7UpdateClient.exe`. No runtime or gameplay state was promoted.

## Evidence and verification

- BootFirst SHA-256: `23D01278CAABE2AF2C0BC240EF62742B506C1DB9484A2B380E9BD63BCA411096`
- Gin7UpdateClient SHA-256: `EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D`
- adjudications SHA-256: `FBF7430AEDDF1AD7555B4B637BEE450465D139113FE5165E9ED59134107B057A`
- static receipt SHA-256: `CFE22BEE90C04E3BDBB4FCE461132925607CD1B8C041EA5EF70E7D402D318B38`
- resource inventory SHA-256: `FF734315A63714E356A1F7BE5FDF7C5C71670D776F0915DB794EB2B199895344`
- graph SHA-256: `8C81E94ECE5F93C2215E24F9D03247D3904DCD001FF2AD16B50741FE5D39910B`
- coverage SHA-256: `9FA4CB065DDBA7E1DE3CFCA3F1A74FDFB946364F13B4D8F102CF9925FB63B448`
- work-package SHA-256: `F8ECB488BDA3217F6CB7BBE3E73BA7C61411F5237FD45DF1A98DA0F14A567E31`
- recovery SHA-256: `930987C1744A085C39E685F52F63EB65E7D6CB9BD971961DDECF86CCAB984AEC`
- aggregate receipt SHA-256: `6DA901377E32FBC6BF179905437A55BE0B7A675AD5A2B4745DCCE09BBD2D3F81`
- independent receipt SHA-256: `2334C9E1511935D817DB9354FF21621D700016E59B76AECE89760FD2F4823FE4`
- independent verdict: `APPROVE`
- tests: 276/276 PASS
- deterministic artifacts: 32/32 matched
- protected inputs: 88/88 unchanged
- fatal: exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`
- evidence gaps: 25,607

## State transition

- old unit count: 0
- target loader: `NOT_APPLICABLE`
- target format/role: `PE32_X86_GUI_EXECUTABLE / UPDATE_CLIENT_BOOTSTRAP`
- target launch: `PROVEN`
- target next boundary: `RESOURCE_OWNER`
- target next unit: `RECOVERY:D01:RESOURCE_OWNER:6A88913B2B850300`
- global next unit: `RECOVERY:D01:RESOURCE_LOADER:E8B07A6802B17EEF`
- global next path: `RESOURCE:FILE:original-installshield-payload:doc/___p_`_v_}_j___a__.pdf`

## Next start

Start one newly authorized bounded unit only. Deterministic ordering selects the PDF resource-loader adjudication. If continuity on BootFirst is explicitly preferred, take its `RESOURCE_OWNER` unit instead.

## Forbidden retries and promotions

- Do not emit a game-asset `LOADS` relation for BootFirst.
- Do not claim BootFirst directly launches `G7MTClient.exe`; current evidence proves only `.\Gin7UpdateClient.exe`.
- Do not infer that the InstallShield shell object automatically ran BootFirst during setup.
- Do not reuse the failed missing-parent Ghidra invocation; create and verify an owned project parent first.
- Do not promote static mapping, runtime observation, player visibility, gameplay, authority, persistence, both factions, Gate-A, or Gate-B.
- Do not treat this structural updater-chain closure as original-client playability or remake implementation progress.
