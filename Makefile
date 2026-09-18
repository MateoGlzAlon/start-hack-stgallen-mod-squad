# Agent on a Leash — wallet control layer. Run `make` to list targets.

-include .env
export

BACKEND_PORT  ?= 8080
FRONTEND_PORT ?= 3000
s ?=

# The UI (compose profile "ui") is included automatically once frontend/Dockerfile exists.
DC = docker compose $(if $(wildcard frontend/Dockerfile),--profile ui)

.DEFAULT_GOAL := help
.PHONY: help env provision deprovision restart logs status clean

help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage: make \033[36m<target>\033[0m\n\n"} /^[a-zA-Z_-]+:.*##/ {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo

env: ## Create .env from .env.example if missing
	@test -f .env || (cp .env.example .env && echo "Created .env - add your keys")

provision: env ## Build and start the system (backend, plus the UI once it exists)
	$(DC) up -d --build --wait
	@echo ""
	@echo "  API  http://localhost:$(BACKEND_PORT)/status"
	@test ! -f frontend/Dockerfile || echo "  UI   http://localhost:$(FRONTEND_PORT)"
	@grep -q '^TEAM_API_KEY=.' .env    || echo "  (!) TEAM_API_KEY is empty in .env: the Viseca worker stays off"
	@grep -q '^OPENAI_API_KEY=.' .env  || echo "  (!) OPENAI_API_KEY is empty in .env: policies cannot be created"

deprovision: ## Stop the system and delete its volumes (saved policies included)
	$(DC) down -v

restart: ## Rebuild and restart one service: make restart s=backend
	$(DC) up -d --build --no-deps $(s)

logs: ## Tail logs (all, or one service: make logs s=backend)
	$(DC) logs -f --tail=100 $(s)

status: ## Backend status: keys configured, worker running, counters
	@curl -fsS http://localhost:$(BACKEND_PORT)/status && echo

clean: ## Remove local build outputs
	rm -rf backend/target frontend/.next
