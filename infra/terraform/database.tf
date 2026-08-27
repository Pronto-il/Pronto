# Amazon RDS for PostgreSQL 16.
#
# Version 16 is not a preference: docker-compose.yml runs postgres:16 locally and
# .github/workflows/backend-ci.yml runs postgres:16 in CI, so this is the third instance of the same
# major. All 52 Flyway migrations have only ever been exercised against 16.

resource "aws_db_subnet_group" "main" {
  name        = "${var.project}-db-subnets"
  description = "Private subnets with no route to the internet gateway."
  subnet_ids  = aws_subnet.private[*].id

  tags = { Name = "${var.project}-db-subnets" }
}

resource "aws_db_parameter_group" "postgres16" {
  name        = "${var.project}-postgres16"
  family      = "postgres16"
  description = "Pronto PostgreSQL 16 parameters."

  # ------------------------------------------------------------------------------------------
  # Refuse unencrypted connections at the SERVER.
  #
  # This is one half of a pair, and neither half is sufficient alone. The other half is
  # DB_URL_PARAMS=?sslmode=require in the task definition (compute.tf), which makes the CLIENT
  # refuse to fall back to plaintext. Without force_ssl, a client misconfiguration silently
  # downgrades; without sslmode=require, pgjdbc's default of `prefer` would negotiate TLS but
  # accept plaintext if the server ever stopped offering it. Both together mean neither side can
  # be the one that quietly gives up.
  #
  # This is `require`, not `verify-full`: RDS presents an Amazon-issued certificate that the JVM's
  # default trust store does not contain, so verify-full additionally needs the RDS CA bundle
  # baked into the image and sslrootcert= pointing at it. That is real hardening against an
  # in-VPC man-in-the-middle and it is recorded as post-1.0 work in the runbook rather than
  # skipped silently.
  # ------------------------------------------------------------------------------------------
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  # Log statements that take longer than a second. Cheap, bounded, and the first thing anybody
  # wants when the application feels slow and nobody knows why.
  parameter {
    name         = "log_min_duration_statement"
    value        = "1000"
    apply_method = "immediate"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "main" {
  identifier = "${var.project}-postgres"

  engine = "postgres"
  # Major only, so AWS selects the current 16.x minor at creation and auto_minor_version_upgrade
  # keeps it patched. Pinning a full minor here would mean a Terraform change for every security
  # patch, which in practice means never taking one.
  engine_version              = "16"
  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false

  instance_class = var.db_instance_class

  # gp3 rather than gp2: same or lower price, and its baseline IOPS is not tied to volume size, so
  # a 20 GiB volume is not also a slow volume.
  storage_type          = "gp3"
  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage

  storage_encrypted = true

  db_name = var.db_name

  # ------------------------------------------------------------------------------------------
  # The master password is never seen by Terraform.
  #
  # manage_master_user_password hands generation, storage and rotation to RDS, which writes the
  # value into a Secrets Manager secret it owns. The alternative -- a `password` argument, however
  # it is sourced -- puts the plaintext into the Terraform state file forever, which would defeat
  # the point of keeping secret values out of this configuration (see secrets.tf).
  #
  # THE APPLICATION DOES NOT USE THIS CREDENTIAL. It connects as var.db_app_username, whose role is
  # created manually against the running instance and whose password lives in the secret container
  # in secrets.tf. This master account exists to create that role, and for break-glass.
  # ------------------------------------------------------------------------------------------
  username                    = var.db_master_username
  manage_master_user_password = true

  multi_az               = var.db_multi_az
  publicly_accessible    = false
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.postgres16.name

  # Any retention above zero also enables point-in-time recovery, which is the control that actually
  # matters: it is what turns "we have last night's backup" into "we can go back to the minute
  # before the bad migration". Backup storage up to the size of the database is free.
  backup_retention_period = var.db_backup_retention_days
  backup_window           = "01:00-02:00" # UTC; roughly 03:00-04:00 Israel time
  maintenance_window      = "sun:02:30-sun:03:30"
  copy_tags_to_snapshot   = true

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.project}-postgres-final"

  # Applying a change immediately can restart the instance in the middle of the day. With one
  # backend task there is no second instance to serve through it, so changes wait for the
  # maintenance window unless somebody deliberately says otherwise.
  apply_immediately = false

  enabled_cloudwatch_logs_exports = ["postgresql"]

  # Performance Insights is deliberately not enabled. Its free tier is genuinely free, but it is not
  # supported on every burstable class and a plan that fails on instance-class grounds is worse than
  # a diagnostic that can be switched on the day it is wanted. Recorded in the runbook as a
  # one-line change.

  tags = { Name = "${var.project}-postgres" }

  lifecycle {
    # Changing either of these forces a replacement of the database, which for a production instance
    # means data loss unless somebody has planned a migration. Terraform should refuse rather than
    # offer it.
    prevent_destroy = true

    ignore_changes = [
      # AWS selects the minor version at creation and auto_minor_version_upgrade moves it. Without
      # this, every plan after a patch shows a spurious downgrade back to what was recorded.
      engine_version,
    ]
  }
}
