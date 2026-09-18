# Agent on a Leash — wallet control layer. Run `make` to list targets.

-include .env
export

BACKEND_PORT  ?= 8080
FRONTEND_PORT ?= 3000
s ?=

# The UI (compose profile "ui") is included automatically once frontend/Dockerfile exists.
DC = docker compose $(if $(wildcard frontend/Dockerfile),--profile ui)

.DEFAULT_GOAL := help
.PHONY: help env build provision deprovision restart logs status example-policies check-cases clean

help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage: make \033[36m<target>\033[0m\n\n"} /^[a-zA-Z_-]+:.*##/ {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo

env: ## Create .env from .env.example if missing
	@test -f .env || (cp .env.example .env && echo "Created .env - add your keys")

build: env ## Build the Docker images (backend, plus the UI once it exists) without starting them
	$(DC) build

provision: env ## Build and start the system (backend, plus the UI once it exists)
	docker compose up -d --wait
	@echo ""
	@echo "  API  http://localhost:$(BACKEND_PORT)/status"
	@echo "  Docs http://localhost:$(BACKEND_PORT)/swagger-ui.html"
	@test ! -f frontend/Dockerfile || echo "  UI   http://localhost:$(FRONTEND_PORT)"

deprovision: ## Stop the system and delete its volumes (saved policies included)
	docker compose down -v

restart: ## Rebuild and restart one service: make restart s=backend
	docker compose up -d --build --no-deps $(s)

logs: ## Tail logs (all, or one service: make logs s=backend)
	docker compose logs -f --tail=100 $(s)

status: ## Backend status: keys configured, worker running, counters
	@curl -fsS http://localhost:$(BACKEND_PORT)/status && echo

example-policies: ## Create the five example policies from plans/EXAMPLE_POLICY_CURLS.md (skips ones that exist)
	@./scripts/create-example-policies.sh

check-cases: ## Send 25 purchases to /check against the example policies and print pass/fail
	@./scripts/run-check-cases.sh

clean: ## Remove local build outputs
	rm -rf backend/target frontend/.next
