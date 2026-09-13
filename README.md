# LOGH7 Greenfield

`LOGH7 Greenfield` is a clean reimplementation of the discontinued 2004 online strategy and tactical game client and its missing server. The repository does not reuse any prior LOGH7 implementation. It treats the archived CD and official manual as primary evidence, imports user-supplied legacy data at runtime, and keeps authored Korean localization and newly implemented systems separate from observed legacy behavior.

## Repository boundaries

- `apps/client`: C++23 client and engine.
- `apps/server`: authoritative C# server.
- `apps/admin`: authenticated Windows server administration tool.
- `contracts`: versioned network and domain contracts.
- `libs/legacy`: legacy resource decoders and immutable source manifests.
- `db`: PostgreSQL migrations and editable seed data.
- `tools`: import, reverse-engineering, and evidence tooling.
- `infra`: Docker Compose and local operations.
- `qa`: VMware multi-client scenarios and manual QA receipts.
- `docs`: architecture, provenance, goals, and evidence.

Legacy game files, database volumes, VM disks, packet captures, secrets, and generated builds are intentionally excluded from Git.

## Supplying the original game data

This repository does not include the original client executable, CD image, manual, artwork, audio, or other copyrighted game data. Development and future game runs require a lawful copy extracted outside the repository. The current legacy probes accept that location through their explicit `--legacy-root <directory>` option.

Obtain the game data from a physical CD you lawfully own or another source you are allowed to use. Research references include the preserved Internet Archive items [LOGH7 client CD](https://archive.org/details/logh-7) and [official manual](https://archive.org/details/gin7manual). Availability on an archive does not grant redistribution rights. Check the law and the archive's terms that apply to you before downloading or using any file.

## Copyright and reverse-engineering policy

The project scope covers an independently written engine, server, compatibility tools, and research code. The current repository records hashes, addresses, formats, and behavioral observations needed for interoperability, but it does not publish the original game's binaries or assets.

> 나는 리버싱을 하지, 저작권 침해는 하지 않습니다.
>
> I reverse engineer software; I do not distribute copyrighted game data.

Do not submit copyrighted game files, extracted asset packs, CD images, credentials, packet captures containing personal data, or proprietary third-party code to this repository.
