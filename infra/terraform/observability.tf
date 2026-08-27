# CloudWatch logs, metric filters, alarms and a budget.
#
# Deliberately CloudWatch and nothing else. Datadog, New Relic and an APM agent were all considered
# and rejected for 1.0: each costs more per month than the infrastructure it would watch, and a
# closed beta's questions ("is it up", "is it erroring", "is the database filling up") are all
# answerable from what AWS already emits. Sentry for frontend errors is deferred by decision.
#
# The metric filters below key on log lines the application ALREADY writes. Nothing here required an
# application change, because MS1-MS3 happened to leave behind stable, greppable event keys
# (openai.request.exhausted, maps.geocode.rejected, ...). MS5 added one more in the same shape:
# pronto.ratelimit.refused. That convention is now load-bearing -- renaming one of these events
# silently zeroes an alarm, and the alarm will not complain about becoming quiet.

resource "aws_cloudwatch_log_group" "backend" {
  name = "/ecs/${var.project}-backend"

  # The AWS default is "never expire", which is how a log group becomes a line item nobody
  # remembers creating. Also bounds how long resolved client IP addresses are retained -- see
  # auth.security.AuthRateLimitInterceptor, which logs them for proxy validation.
  retention_in_days = var.log_retention_days

  tags = { Name = "${var.project}-backend-logs" }
}

# ==================================================================================================
# Alarm destination
# ==================================================================================================

resource "aws_sns_topic" "alarms" {
  name = "${var.project}-${var.environment_name}-alarms"
  tags = { Name = "${var.project}-alarms" }
}

resource "aws_sns_topic_subscription" "alarms_email" {
  count = var.alarm_email == "" ? 0 : 1

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email

  # AWS sends a confirmation email; until somebody clicks it the subscription is "PendingConfirmation"
  # and delivers nothing. An unconfirmed subscription looks identical to a working one in the
  # console's topic list, so confirm it and then deliberately trigger one alarm -- the runbook's
  # validation section says so for this reason.
}

# ==================================================================================================
# Application-level signals, from log lines the application already writes
# ==================================================================================================

locals {
  # Each entry becomes a metric filter and an alarm. Threshold and period are per-signal because
  # "how many is too many" is a different question for a rate-limit refusal than for an OTP that
  # never arrived.
  log_alarms = {
    application_errors = {
      pattern     = "ERROR"
      description = "Application ERROR log volume. The blunt catch-all: whatever breaks that nothing below anticipated shows up here first."
      threshold   = 10
      period      = 300
    }

    openai_exhausted = {
      # Written by ai.client.OpenAiChatClient after every retry is spent. MS3 measured nine of these
      # consecutively from transient provider failures; MS5 added backoff, and this is how we find
      # out whether that was enough.
      pattern     = "openai.request.exhausted"
      description = "OpenAI requests failing after all retries. Issue classification is degraded for the customers who hit it."
      threshold   = 5
      period      = 900
    }

    otp_delivery_failed = {
      # MS1's report asked for this alarm by name, listed it as a Medium risk with owner MS5, and it
      # is the highest-consequence application failure Pronto has: a customer who never receives a
      # code cannot register or log in, and nothing in the product tells them why.
      pattern     = "OTP_DELIVERY_FAILED"
      description = "Verification code delivery failing. Affected customers cannot register or sign in at all."
      threshold   = 1
      period      = 300
    }

    rate_limit_refusals = {
      # pronto.ratelimit.refused, added by MS5. A spike is either abuse or -- more likely early on --
      # a TRUSTED_PROXIES misconfiguration collapsing every user onto one bucket, which presents as
      # a sudden platform-wide wall of 429s.
      pattern     = "pronto.ratelimit.refused"
      description = "Auth rate-limit refusals. A sudden spike is abuse, or TRUSTED_PROXIES collapsing every client into one bucket."
      threshold   = 50
      period      = 300
    }

    maps_rejected = {
      # MS2's report section 17.5 asked for this to be watched after launch: a rise in
      # PARTIAL_MATCH means the address-strictness rule is refusing real customer addresses.
      pattern     = "maps.geocode.rejected"
      description = "Geocoding rejections. A sustained rise means real customer addresses are being refused."
      threshold   = 25
      period      = 900
    }

    startup_refused = {
      # The guards throw with this prefix. It should be impossible in a steady state -- if it fires,
      # a deploy is crash-looping on configuration and the deploy workflow's wait will also fail.
      pattern     = "Refusing to start"
      description = "A startup guard refused the configuration. The service is not starting."
      threshold   = 1
      period      = 300
    }
  }

  metric_namespace = "Pronto/${var.environment_name}"
}

resource "aws_cloudwatch_log_metric_filter" "app" {
  for_each = local.log_alarms

  name           = "${var.project}-${each.key}"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = each.value.pattern

  metric_transformation {
    name      = each.key
    namespace = local.metric_namespace
    value     = "1"
    # Without this, periods with no matching log line report NO data rather than zero, and an alarm
    # on a sparse metric sits in INSUFFICIENT_DATA instead of OK. That is indistinguishable from
    # "the alarm is broken" at a glance.
    default_value = 0
  }
}

resource "aws_cloudwatch_metric_alarm" "app" {
  for_each = local.log_alarms

  alarm_name          = "${var.project}-${each.key}"
  alarm_description   = each.value.description
  namespace           = local.metric_namespace
  metric_name         = each.key
  statistic           = "Sum"
  period              = each.value.period
  evaluation_periods  = 1
  threshold           = each.value.threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

# ==================================================================================================
# Infrastructure signals
# ==================================================================================================

resource "aws_cloudwatch_metric_alarm" "no_running_task" {
  alarm_name        = "${var.project}-backend-not-running"
  alarm_description = "The ALB has no healthy backend target. With desired_count = 1 this is a total outage, not degraded capacity."

  # HealthyHostCount on the ALB, NOT ECS/ContainerInsights RunningTaskCount.
  #
  # This alarm previously watched ECS/ContainerInsights RunningTaskCount. That metric is only
  # published when Container Insights is enabled, and compute.tf disables it for cost. AWS therefore
  # published no datapoints at all, and because treat_missing_data is `breaching` the alarm sat in
  # ALARM permanently -- including while the backend was perfectly healthy. An availability alarm
  # that is always firing conveys nothing, and worse, it makes a real outage indistinguishable from
  # the steady state.
  #
  # The old comment here claimed the UnHealthyHostCount alarm below covered the gap. It does not,
  # and the MS5 bootstrap crash loop demonstrated it: through hours of the service failing to start,
  # that alarm stayed OK apart from three ~2-minute windows. When ECS kills a failed task it
  # DEREGISTERS the target, so the target group holds zero targets and UnHealthyHostCount is 0 --
  # under its `>= 1` threshold. It only fires while a target is registered AND failing checks, which
  # is a narrow slice of a real outage.
  #
  # HealthyHostCount < 1 covers both shapes, which is what makes it the correct availability signal:
  #   * target registered but failing health checks -> HealthyHostCount = 0            -> ALARM
  #   * no target registered at all (task dead/deregistered)  -> no data, treated as breaching -> ALARM
  #
  # UnHealthyHostCount is deliberately kept below. It is a genuinely different, earlier signal --
  # "a target exists and is sick" -- and is useful precisely when this alarm has not yet tripped.
  namespace   = "AWS/ApplicationELB"
  metric_name = "HealthyHostCount"
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.backend.arn_suffix
  }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  # `breaching`, not `notBreaching`: for this one metric an absence of data is itself the bad news --
  # the ALB stops reporting per-target-group health when nothing is registered, which is exactly the
  # total-outage case this alarm exists to catch.
  treat_missing_data = "breaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_unhealthy_targets" {
  alarm_name        = "${var.project}-alb-unhealthy-target"
  alarm_description = "The ALB has no healthy backend target. This is the primary 'the site is down' alarm."

  namespace   = "AWS/ApplicationELB"
  metric_name = "UnHealthyHostCount"
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.backend.arn_suffix
  }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name        = "${var.project}-alb-target-5xx"
  alarm_description = "Backend 5xx responses. Distinct from the log ERROR alarm: this counts what customers actually received."

  namespace   = "AWS/ApplicationELB"
  metric_name = "HTTPCode_Target_5XX_Count"
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.backend.arn_suffix
  }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
}

locals {
  rds_alarms = {
    cpu = {
      metric      = "CPUUtilization"
      statistic   = "Average"
      threshold   = 80
      operator    = "GreaterThanThreshold"
      description = "RDS CPU sustained high. On a burstable class this also means CPU credits are draining."
    }
    storage = {
      metric    = "FreeStorageSpace"
      statistic = "Minimum"
      # 2 GiB in bytes. Storage autoscaling should act first; this fires if it has not.
      threshold   = 2147483648
      operator    = "LessThanThreshold"
      description = "RDS free storage low. A full disk stops writes entirely -- the most common self-inflicted database outage."
    }
    memory = {
      metric      = "FreeableMemory"
      statistic   = "Minimum"
      threshold   = 134217728 # 128 MiB
      operator    = "LessThanThreshold"
      description = "RDS freeable memory low. Precedes swapping and a sharp latency cliff."
    }
    connections = {
      metric    = "DatabaseConnections"
      statistic = "Maximum"
      # HikariCP defaults to a pool of 10 per JVM and there is one JVM. Well above that means
      # something is leaking connections or a second consumer appeared.
      threshold   = 40
      operator    = "GreaterThanThreshold"
      description = "Unexpected database connection count for a single-task deployment."
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "rds" {
  for_each = local.rds_alarms

  alarm_name        = "${var.project}-rds-${each.key}"
  alarm_description = each.value.description

  namespace   = "AWS/RDS"
  metric_name = each.value.metric
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
  statistic           = each.value.statistic
  period              = 300
  evaluation_periods  = 2
  threshold           = each.value.threshold
  comparison_operator = each.value.operator
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

# ==================================================================================================
# Cost
# ==================================================================================================

resource "aws_budgets_budget" "monthly" {
  name         = "${var.project}-${var.environment_name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # AWS provides no way to cap spend. This only tells you -- which is still the difference between
  # noticing a runaway cost in a day and noticing it on the invoice.
  dynamic "notification" {
    for_each = var.alarm_email == "" ? [] : [50, 80, 100]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = [var.alarm_email]
    }
  }
}
