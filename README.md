# Pronto

Pronto is a smart home-services platform designed to connect customers with trusted service professionals quickly and efficiently.

The platform helps users report home issues, receive AI-assisted problem classification, and get matched with the most suitable professionals based on service type, availability, and location.

---

## Features

* Service request creation
* AI-assisted issue classification
* Professional matching
* SOS emergency requests
* Image uploads
* Professional availability management

---

## Project Goals

Pronto aims to simplify the process of finding reliable home-service professionals while helping small businesses gain access to new customers.

The platform focuses on:

* Reducing the time required to find a professional
* Improving issue classification using AI
* Increasing transparency throughout the service process
* Creating a better experience for both customers and professionals

---

## Tech Stack

### Frontend

* React

### Backend

* Spring Boot

### Cloud Services

* AWS Amplify
* AWS EC2
* AWS Lambda
* Amazon DynamoDB
* Amazon S3
* Amazon SQS

---

## Project Structure

```text
frontend/
backend/
docs/
```

### frontend

React application for customers and service professionals.

### backend

Spring Boot services and business logic.

### docs

Project documentation, research, wireframes, and design artifacts.

---

## Local Development

```bash
docker compose up -d          # PostgreSQL on host port 5433
cd backend  && mvn spring-boot:run   # http://localhost:8080
cd frontend && npm install && npm run dev   # http://localhost:5173
```

No environment variables are required. `docker-compose.yml` and
`backend/src/main/resources/application.yml` read the same five variables with the same
defaults, so the two agree out of the box:

| Variable | Default | Meaning |
| --- | --- | --- |
| `DB_HOST` | `localhost` | Host the backend dials |
| `DB_PORT` | `5433` | Host port published by the container, and dialled by the backend |
| `DB_NAME` | `pronto` | Database created by the container |
| `DB_USER` | `pronto` | Role created by the container |
| `DB_PASSWORD` | `pronto` | Local-development password only — never a deployed secret |

**Why 5433 and not the usual 5432**: a native Windows PostgreSQL service occupies 5432 on
this project's development machine, and publishing the container there too meant whichever
started first won the port — with the backend silently reading the wrong database. Setting
`DB_PORT` overrides both sides at once, so `DB_PORT=5432` works on a machine with no such
conflict.

---

## Current Status

This project is currently under development as part of a Software Engineering academic project.

---

## Contributors

* Or Cohen
* Yuval Harel
