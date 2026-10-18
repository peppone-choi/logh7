"""ADR-0005 GCP draft monthly cost model (Seoul asia-northeast3, USD, on-demand).

Unit prices were read from the public Google Cloud pricing pages on 2026-10-18
with the region selector set to "Seoul (asia-northeast3)". See
docs/ops/adr-0005-gcp-draft.md for the source table. Quantities (disk sizes,
snapshot GiB, egress GiB) are planning assumptions, not measurements.

Run: python3 docs/ops/gcp_cost_model.py
"""

HOURS_PER_MONTH = 730

# Official Seoul list prices (USD), retrieved 2026-10-18.
E2_STANDARD_2_PER_HOUR = 0.08596572
PD_BALANCED_PER_GIB_HOUR = 0.000178082
STANDARD_SNAPSHOT_PER_GIB_HOUR = 0.000087671
IPV4_IN_USE_PER_HOUR = 0.005
IPV4_RESERVED_UNUSED_PER_HOUR = 0.015
PREMIUM_EGRESS_KOREA_PER_GIB = 0.19  # 0-10,240 GiB tier, destination Korea
SQL_ENT_VCPU_PER_HOUR = 0.0537  # Enterprise, general purpose
SQL_ENT_RAM_PER_GIB_HOUR = 0.0091
SQL_ENT_HA_VCPU_PER_HOUR = 0.1074
SQL_ENT_HA_RAM_PER_GIB_HOUR = 0.0182
SQL_SSD_PER_GIB_HOUR = 0.00030274
SQL_HA_SSD_PER_GIB_HOUR = 0.000605479
SQL_BACKUP_PER_GIB_HOUR = 0.000142466

# Planning assumptions (not official values).
BOOT_GIB = 20  # assumed pd-balanced
DATA_GIB = 50
SNAPSHOT_GIB = 70  # assumed stored snapshot volume, unmeasured
EGRESS_GIB = 100  # unmeasured; all to Korea
SQL_VCPU = 2
SQL_RAM_GIB = 8
SQL_STORAGE_GIB = 20  # assumed
SQL_BACKUP_GIB = 20  # assumed


def monthly(hourly, qty=1.0, hours=HOURS_PER_MONTH):
    return hourly * qty * hours


def running_vm_month():
    disk_gib = BOOT_GIB + DATA_GIB
    items = {
        "e2-standard-2": monthly(E2_STANDARD_2_PER_HOUR),
        "pd-balanced 70 GiB": monthly(PD_BALANCED_PER_GIB_HOUR, disk_gib),
        "IPv4 in use": monthly(IPV4_IN_USE_PER_HOUR),
        "snapshot 70 GiB": monthly(STANDARD_SNAPSHOT_PER_GIB_HOUR, SNAPSHOT_GIB),
        "egress 100 GiB to Korea": PREMIUM_EGRESS_KOREA_PER_GIB * EGRESS_GIB,
    }
    return items, sum(items.values())


def stopped_vm_month():
    """VM stopped all month: no vCPU/RAM charge, but disks, snapshots and the
    static IPv4 still bill (a static IP attached to a stopped VM is still
    'in use' at $0.005/h per the network pricing page)."""
    disk_gib = BOOT_GIB + DATA_GIB
    items = {
        "pd-balanced 70 GiB": monthly(PD_BALANCED_PER_GIB_HOUR, disk_gib),
        "static IPv4 on stopped VM": monthly(IPV4_IN_USE_PER_HOUR),
        "snapshot 70 GiB": monthly(STANDARD_SNAPSHOT_PER_GIB_HOUR, SNAPSHOT_GIB),
    }
    return items, sum(items.values())


def cloud_sql_month(ha=False):
    vcpu = SQL_ENT_HA_VCPU_PER_HOUR if ha else SQL_ENT_VCPU_PER_HOUR
    ram = SQL_ENT_HA_RAM_PER_GIB_HOUR if ha else SQL_ENT_RAM_PER_GIB_HOUR
    ssd = SQL_HA_SSD_PER_GIB_HOUR if ha else SQL_SSD_PER_GIB_HOUR
    compute = monthly(vcpu, SQL_VCPU) + monthly(ram, SQL_RAM_GIB)
    storage = monthly(ssd, SQL_STORAGE_GIB) + monthly(SQL_BACKUP_PER_GIB_HOUR, SQL_BACKUP_GIB)
    return compute, storage


def legacy_cloud_sql_example():
    """Re-derive the 2026-09-27 draft number (non-Seoul default-region example)."""
    return HOURS_PER_MONTH * (2 * 0.0413 + 8 * 0.007)


def main():
    for label, fn in (("running", running_vm_month), ("stopped", stopped_vm_month)):
        items, total = fn()
        print(f"[{label}]")
        for k, v in items.items():
            print(f"  {k:28s} ${v:8.2f}")
        print(f"  {'total':28s} ${total:8.2f}")
    for ha in (False, True):
        c, s = cloud_sql_month(ha)
        print(f"[cloud sql {'HA' if ha else 'single'}] compute ${c:.2f} storage+backup ${s:.2f}")
    print(f"[legacy draft cloud sql example] ${legacy_cloud_sql_example():.2f}")


if __name__ == "__main__":
    main()
