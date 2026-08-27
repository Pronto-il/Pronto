# VPC, subnets, routing and security groups.
#
# ==================================================================================================
# THERE IS NO NAT GATEWAY IN THIS CONFIGURATION, AND THAT IS DELIBERATE.
# ==================================================================================================
#
# The reference architecture for this application puts the ECS tasks in private subnets and gives
# them outbound internet through a NAT gateway, which costs roughly $33/month before data
# processing -- a fixed cost, larger than the compute it serves, incurred whether or not anybody
# uses the product. For a closed beta that is the wrong shape of bill.
#
# Instead the tasks run in the PUBLIC subnets with a public IP, used for outbound only:
# OpenAI, Google Maps, SES, SNS, ECR and Secrets Manager are all reached over the internet gateway.
#
# What that costs in security, stated plainly rather than glossed: the task's security group becomes
# the ONLY thing standing between the internet and port 8080. In the private-subnet design there are
# two independent controls -- no route from the internet gateway, and the security group -- and
# either one alone is sufficient. Here there is one. It is `aws_vpc_security_group_ingress_rule.app_from_alb`
# below, it allows exactly one source (the ALB's security group, by id, not by CIDR), and there is
# no rule anywhere in this file that admits 0.0.0.0/0 to the application port.
#
# The migration back is small and is why the private subnets below exist already, unused: add a NAT
# gateway plus a default route on the private route table, then point the ECS service's
# network_configuration at the private subnets with assign_public_ip = false. Nothing is
# re-addressed, and TRUSTED_PROXIES does not change, because the ALB does not move.

# ==================================================================================================
# VPC
# ==================================================================================================

resource "aws_vpc" "main" {
  cidr_block = var.vpc_cidr

  # Both required for the RDS endpoint hostname to resolve from inside the VPC.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.project}-vpc" }
}

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.project}-igw" }
}

# ==================================================================================================
# Subnets
# ==================================================================================================

resource "aws_subnet" "public" {
  count = length(var.public_subnet_cidrs)

  vpc_id            = aws_vpc.main.id
  cidr_block        = var.public_subnet_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]

  # Left false on purpose. The ECS service requests a public IP explicitly through
  # assign_public_ip, which keeps that decision visible in compute.tf where the tradeoff is
  # documented. Defaulting it at the subnet would silently hand a public address to anything else
  # ever launched here.
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.project}-public-${data.aws_availability_zones.available.names[count.index]}"
    Tier = "public"
  }
}

resource "aws_subnet" "private" {
  count = length(var.private_subnet_cidrs)

  vpc_id            = aws_vpc.main.id
  cidr_block        = var.private_subnet_cidrs[count.index]
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = {
    Name = "${var.project}-private-${data.aws_availability_zones.available.names[count.index]}"
    Tier = "private"
  }
}

# ==================================================================================================
# Routing
# ==================================================================================================

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.project}-public-rt" }
}

resource "aws_route" "public_default" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# No default route. RDS needs none, and the absence of one is the property that makes these subnets
# private -- there is nothing to remove or misconfigure. The NAT-gateway route that a later
# milestone adds goes here, and this is the only place it goes.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.project}-private-rt" }
}

resource "aws_route_table_association" "private" {
  count = length(aws_subnet.private)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# S3 gateway endpoint. FREE -- gateway endpoints have no hourly or data-processing charge, unlike
# interface endpoints. Worth having even with tasks in public subnets: every issue photo and
# verification document upload and presign then travels the AWS backbone instead of out through the
# internet gateway. It is also already attached to the private route table, so it keeps working
# unchanged when the tasks move there.
#
# Interface endpoints for SES/SNS/Secrets Manager/ECR are deliberately NOT created: at roughly $7
# each per month, six of them would cost more than the NAT gateway this architecture exists to
# avoid.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id, aws_route_table.private.id]

  tags = { Name = "${var.project}-s3-endpoint" }
}

# ==================================================================================================
# Security groups
#
# Three groups, each referencing the next by ID rather than by CIDR. That matters: an ALB scales its
# nodes and replaces their addresses without warning, so a CIDR-based rule is either too wide or
# wrong after the first scaling event, while a security-group reference stays exactly correct.
# ==================================================================================================

resource "aws_security_group" "alb" {
  name        = "${var.project}-alb-sg"
  description = "Public ingress to the load balancer. The only group in this VPC that accepts traffic from the internet."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.project}-alb-sg" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from the internet"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from the internet -- redirected to HTTPS once a certificate exists (see compute.tf)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to the backend tasks, and nowhere else"
  referenced_security_group_id = aws_security_group.app.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "app" {
  name        = "${var.project}-app-sg"
  description = "Backend ECS tasks. Reachable ONLY from the load balancer."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.project}-app-sg" }

  lifecycle {
    create_before_destroy = true
  }
}

# ------------------------------------------------------------------------------------------------
# THE SINGLE MOST SECURITY-SENSITIVE RULE IN THIS CONFIGURATION.
#
# Because the tasks run in public subnets with public IPs (see this file's header), this rule is the
# only thing preventing a direct connection to port 8080 from the internet. Two consequences follow,
# and both are load-bearing:
#
#  1. It closes the HIGH-severity risk MS4 recorded and could not close in code
#     (prod-MS4-report.md section 12, "ALB not the only ingress path"). Anyone who could open a TCP
#     connection to the task directly could send a forged X-Forwarded-For from a source address that
#     ClientIpResolver has been told to trust, and thereby evade the auth rate limiter entirely or
#     spend another user's bucket.
#
#  2. `referenced_security_group_id`, never `cidr_ipv4`. Naming the ALB's security group means "any
#     network interface the load balancer owns, now and after it scales". A CIDR would have to be
#     the whole subnet, which is also where the tasks themselves live -- so one task could reach
#     another's port 8080, and any future resource placed in these subnets could too.
#
# If this rule is ever widened to a CIDR, and especially to 0.0.0.0/0, TRUSTED_PROXIES becomes
# spoofable and the rate limiter stops being a control. There is no application-level mitigation.
# ------------------------------------------------------------------------------------------------
resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "Backend port 8080 -- from the ALB security group ONLY. Never a CIDR, never 0.0.0.0/0."
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "app_to_db" {
  security_group_id            = aws_security_group.app.id
  description                  = "PostgreSQL to RDS"
  referenced_security_group_id = aws_security_group.database.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

# Outbound HTTPS to the internet: OpenAI (api.openai.com), Google Maps (Geocoding + Routes), SES,
# SNS, ECR, Secrets Manager and CloudWatch Logs. All of them are public endpoints reached over the
# internet gateway in this topology, and none of them publishes a stable address range worth
# pinning -- so this is 0.0.0.0/0 on 443 and nothing else. Note this is EGRESS: it lets the task
# start connections out, and admits nothing in.
resource "aws_vpc_security_group_egress_rule" "app_https_out" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS to OpenAI, Google Maps and the AWS APIs"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_security_group" "database" {
  name        = "${var.project}-db-sg"
  description = "RDS PostgreSQL. Reachable only from the backend tasks; no egress at all."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.project}-db-sg" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.database.id
  description                  = "PostgreSQL from the backend tasks ONLY"
  referenced_security_group_id = aws_security_group.app.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

# Deliberately no egress rule for the database group. A security group with no egress rules permits
# no outbound traffic, which is correct: PostgreSQL answers connections, it does not make them.
