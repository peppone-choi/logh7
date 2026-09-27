# 작성: 최병호 | evidence:guess | 생성하지 않은 검토용 초안
terraform {
  required_version = ">= 1.5.0, < 2.0.0"
  required_providers {
    google = {
      source = "hashicorp/google"
      version = ">= 6.0, < 8.0"
    }
  }
}
provider "google" {
  project = var.project_id
  region  = "asia-northeast3"
  zone    = "asia-northeast3-a"
}
variable "project_id" {
  type = string
}
variable "player_cidrs" {
  type = list(string)
  description = "승인된 플레이어 CIDR; 빈 목록이면 게임 방화벽 미생성"
  default = []
}
variable "session_port" {
  type = number
  default = 47903
  validation {
    condition = var.session_port >= 1024 && var.session_port <= 65535 && floor(var.session_port) == var.session_port && !contains([47900, 47901, 47902], var.session_port)
    error_message = "세션 포트는 1024~65535 정수이며 47900/47901/47902와 달라야 합니다."
  }
}
resource "google_compute_network" "game" {
  name = "logh7"
  auto_create_subnetworks = false
}
resource "google_compute_subnetwork" "game" {
  name = "logh7-seoul"
  ip_cidr_range = "10.77.0.0/24"
  network = google_compute_network.game.id
}
resource "google_compute_address" "game" {
  name = "logh7-public"
}
resource "google_compute_firewall" "game" {
  count = length(var.player_cidrs) > 0 ? 1 : 0
  name = "logh7-game"
  network = google_compute_network.game.name
  source_ranges = var.player_cidrs
  target_tags = ["logh7-game"]
  allow {
    protocol = "tcp"
    ports = ["47900", "47902", tostring(var.session_port)]
  }
}
resource "google_compute_firewall" "iap_ssh" {
  name = "logh7-iap-ssh"
  network = google_compute_network.game.name
  source_ranges = ["35.235.240.0/20"]
  target_tags = ["logh7-game"]
  allow {
    protocol = "tcp"
    ports = ["22"]
  }
}
resource "google_compute_disk" "data" {
  name = "logh7-data"
  type = "pd-balanced"
  size = 50
  lifecycle {
    prevent_destroy = true
  }
}
resource "google_compute_resource_policy" "daily" {
  name = "logh7-daily"
  snapshot_schedule_policy {
    schedule {
      daily_schedule {
        days_in_cycle = 1
        start_time = "18:00"
      }
    }
    retention_policy {
      max_retention_days = 14
      on_source_disk_delete = "KEEP_AUTO_SNAPSHOTS"
    }
    snapshot_properties {
      storage_locations = ["asia-northeast3"]
    }
  }
}
resource "google_compute_disk_resource_policy_attachment" "daily" {
  name = google_compute_resource_policy.daily.name
  disk = google_compute_disk.data.name
}
resource "google_compute_instance" "game" {
  name = "logh7-game"
  machine_type = "e2-standard-2"
  deletion_protection = true
  tags = ["logh7-game"]
  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
      size = 20
      type = "pd-balanced"
    }
  }
  attached_disk {
    source = google_compute_disk.data.id
    device_name = "logh7-data"
  }
  network_interface {
    subnetwork = google_compute_subnetwork.game.id
    access_config {
      nat_ip = google_compute_address.game.address
    }
  }
  metadata = {
    enable-oslogin = "TRUE"
    block-project-ssh-keys = "TRUE"
  }
  shielded_instance_config {
    enable_secure_boot = true
  }
}
output "public_ip" {
  value = google_compute_address.game.address
}
